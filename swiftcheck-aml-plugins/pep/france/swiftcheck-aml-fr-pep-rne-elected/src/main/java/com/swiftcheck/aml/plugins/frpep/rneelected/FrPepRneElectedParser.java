package com.swiftcheck.aml.plugins.frpep.rneelected;

import static com.swiftcheck.aml.sdk.DateNormalizer.toIsoDate;
import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.CsvRecordReader;
import com.swiftcheck.aml.sdk.CsvRow;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_rne_elected</b> – élus locaux français du Répertoire national des élus (ministère de l'Intérieur, 
 * data.gouv.fr, Licence ouverte), sans limite de taille de commune : tous les maires et maires délégués, tous les
 * adjoints au maire, tous les conseillers départementaux, régionaux et des assemblées des collectivités à statut
 * particulier, et les membres du bureau des intercommunalités (président, vice-présidents, membres du bureau).
 * Les conseillers municipaux sans fonction ne sont repris que si {@code includeCouncillors} vaut {@code true}.
 *
 * <p>Complète {@code fr_pep_local_executives} (chefs d'exécutif des collectivités de 50 000 habitants ou plus) : les
 * deux parsers donnent la même identité (nom, prénom, date de naissance) et leurs fiches se rapprochent à l'écran.
 *
 * <p>Type {@code PLUGIN_MANAGED} : le plugin télécharge les fichiers dans {@code rawDir}. Paramètres :
 * <pre>{@code
 * {"municipalUrl": "…/elus-conseillers-municipaux-cm.csv",
 *  "epciUrl": "…/elus-conseillers-communautaires-epci.csv", "departmentsUrl": "…/elus-conseillers-departementaux-cd.csv",
 *  "regionsUrl": "…/elus-conseillers-regionaux-cr.csv", "specialUrl": "…/elus-membres-assemblee-ma.csv",
 *  "includeCouncillors": "false"}
 * }</pre>
 */
@Extension
public class FrPepRneElectedParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-rne-elected";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - elus locaux)";

    /** Paramètre d'adresse → fichier déposé dans {@code rawDir}. */
    static final Map<String, String> FILES = new LinkedHashMap<>();
    static {
        FILES.put("municipalUrl", "municipaux.csv");
        FILES.put("epciUrl", "epci.csv");
        FILES.put("departmentsUrl", "departements.csv");
        FILES.put("regionsUrl", "regions.csv");
        FILES.put("specialUrl", "collectivites.csv");
    }

    /** « 3ème adjoint au Maire », « 1er Vice-président du conseil communautaire » : rang puis fonction. */
    private static final Pattern RANK = Pattern.compile("(?iu)^(\\d+)\\s?(?:er|ère|e|ème|eme)\\s+(.+)$");

    /** Téléchargement d'une adresse ; remplacé dans les tests. */
    interface Fetcher {
        /** Télécharge {@code url} dans {@code target}. */
        void get(String url, Path target) throws IOException, InterruptedException;
    }

    /** Un élu gardé. */
    record Elected(String lastName, String firstName, String sex, String birthDate, String function, String rank,
            String collectivity, String collectivityCode, String startDate, String topic) {
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int mayors, int deputyMayors, int epci, int departments, int regions, int special, int councillors) {

        String message() {
            return String.format("%d maires délégués, %d adjoints au maire, %d membres du bureau d'intercommunalité, "
                    + "%d conseillers départementaux, %d conseillers régionaux, %d élus de collectivités à statut "
                    + "particulier, %d conseillers municipaux.", mayors, deputyMayors, epci, departments, regions,
                    special, councillors);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(), p, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), includeCouncillors(p), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), includeCouncillors(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    static void download(Fetcher fetcher, Map<String, String> p, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        for (Map.Entry<String, String> file : FILES.entrySet()) {
            String url = p.get(file.getKey());
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("Paramètre « " + file.getKey() + " » manquant dans la configuration du "
                        + "parser (voir le README du plugin).");
            }
            fetcher.get(url.trim(), rawDir.resolve(file.getValue()));
        }
    }

    /** Lit les fichiers de {@code rawDir} et écrit les élus. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, boolean includeCouncillors, FtmDatasetBuilder dataset) throws Exception {
        int[] n = new int[7];
        CsvRecordReader.readEach(file(rawDir, "municipalUrl"), ';', row -> {
            String label = row.get("Libellé de la fonction");
            String commune = row.get("Libellé de la commune");
            String code = row.get("Code de la commune");
            if (label == null || label.isBlank()) {
                if (includeCouncillors) {
                    write(elected(row, "Conseiller municipal", null, commune, code, "gov.muni"), dataset);
                    n[6]++;
                }
                return;
            }
            if (label.equals("Maire")) {
                return;                                  // maire : déjà dans fr_pep_local_executives_all
            }
            Matcher m = RANK.matcher(label.trim());
            boolean ranked = m.matches();
            String function = capitalize(ranked ? m.group(2) : label.trim());
            write(elected(row, function, ranked ? m.group(1) : null, commune, code, "gov.muni"), dataset);
            if (function.startsWith("Maire délégué")) {
                n[0]++;
            } else {
                n[1]++;
            }
        });
        CsvRecordReader.readEach(file(rawDir, "epciUrl"), ';', row -> {
            String label = row.get("Libellé de la fonction");
            if (label != null && !label.isBlank() && !covered(label.trim())) {
                Matcher m = RANK.matcher(label.trim());
                boolean ranked = m.matches();
                write(elected(row, capitalize(ranked ? m.group(2) : label.trim()), ranked ? m.group(1) : null,
                        row.get("Libellé de l'EPCI"), row.get("N° SIREN"), "gov.muni"), dataset);
                n[2]++;
            }
        });
        CsvRecordReader.readEach(file(rawDir, "departmentsUrl"), ';', row -> {
            String function = functionOr(row, "Conseiller départemental");
            if (!covered(function)) {
                write(elected(row, function, null, row.get("Libellé du département"), row.get("Code du département"),
                        "gov.state"), dataset);
                n[3]++;
            }
        });
        CsvRecordReader.readEach(file(rawDir, "regionsUrl"), ';', row -> {
            String function = functionOr(row, "Conseiller régional");
            if (!covered(function)) {
                write(elected(row, function, null, row.get("Libellé de la région"), row.get("Code de la région"),
                        "gov.state"), dataset);
                n[4]++;
            }
        });
        CsvRecordReader.readEach(file(rawDir, "specialUrl"), ';', row -> {
            String name = row.get("Libellé de la collectivité à statut particulier");
            String code = row.get("Code de la collectivité à statut particulier");
            String function = functionOr(row, "Conseiller de l'assemblée");
            if (code != null && !code.isBlank() && !covered(function)) {
                write(elected(row, function, null, name, code, "gov.state"), dataset);
                n[5]++;
            }
        });
        if (n[1] == 0) {
            throw new IllegalStateException("Aucun adjoint au maire dans le fichier des conseillers municipaux : fichier incomplet ou format changé.");
        }
        return new Report(n[0], n[1], n[2], n[3], n[4], n[5], n[6]);
    }

    /** Chefs d'exécutif déjà repris par fr_pep_local_executives et fr_pep_local_executives_all (mêmes personnes). */
    static boolean covered(String function) {
        return function.equals("Président du conseil communautaire") || function.equals("Président du conseil départemental")
                || function.equals("Président du conseil régional") || function.startsWith("Président de l'");
    }

    private static String functionOr(CsvRow row, String fallback) {
        String f = row.get("Libellé de la fonction");
        return f == null || f.isBlank() ? fallback : capitalize(f.trim());
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Elected elected(CsvRow row, String function, String rank, String collectivity, String code,
            String topic) {
        String start = row.get("Date de début de la fonction");
        if (start == null || start.isBlank()) {
            start = row.get("Date de début du mandat");
        }
        return new Elected(row.get("Nom de l'élu"), row.get("Prénom de l'élu"), row.get("Code sexe"),
                toIsoDate(row.get("Date de naissance")), function, rank, collectivity, code, toIsoDate(start), topic);
    }

    private static void write(Elected e, FtmDatasetBuilder dataset) {
        String title = e.function() + " – " + e.collectivity();               // « Maire – Lyon »

        // ID = nom + prénom + date de naissance (comme fr_pep_local_executives) : le RNE ne donne pas d'identifiant
        FtmEntity person = dataset.newEntity("Person", e.lastName(), e.firstName(), e.birthDate());
        person.addValue("name", joinNonBlank(" ", e.firstName(), e.lastName()));
        person.addValue("firstName", e.firstName());
        person.addValue("lastName", e.lastName());
        person.addValue("birthDate", e.birthDate());
        person.addValue("gender", toFtmGender(e.sex()));
        person.addValue("country", "fr");
        person.addValue("position", title);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", e.function(), e.collectivityCode());
        position.addValue("name", title);
        position.addValue("country", "fr");
        position.addValue("subnationalArea", e.collectivity());
        position.addValue("topics", e.topic());
        if (e.function().startsWith("Maire") || e.function().startsWith("Président")) {
            position.addValue("topics", "gov.executive");
        }
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", e.lastName(), e.firstName(), e.birthDate(), e.function(),
                e.collectivityCode());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", e.startDate());
        occupancy.addValue("status", "current");
        if (e.rank() != null) {
            occupancy.addValue("description", "Rang " + e.rank());
        }
        dataset.addEntity(occupancy);
    }

    private static Path file(Path rawDir, String key) {
        Path path = rawDir.resolve(FILES.get(key));
        if (!Files.exists(path)) {
            throw new IllegalStateException(FILES.get(key) + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        return path;
    }

    static boolean includeCouncillors(Map<String, String> p) {
        String v = p.get("includeCouncillors");
        return v != null && "true".equalsIgnoreCase(v.trim());
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return (url, target) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                    .header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
        };
    }
}
