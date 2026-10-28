package com.swiftcheck.aml.plugins.frpep.localexecutives;

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
 * <b>fr_pep_local_executives</b> – dirigeants des collectivités territoriales d'au moins 50 000 habitants : maires,
 * présidents d'intercommunalité, de conseil départemental, de conseil régional et des collectivités à statut
 * particulier (catégorie FR-AMLR ; règlement (UE) 2024/1624, applicable le 10 juillet 2027).
 *
 * <p>Sources (data.gouv.fr, Licence ouverte) : le Répertoire national des élus du ministère de l'Intérieur, un fichier
 * CSV par mandat (séparateur {@code ;}, UTF-8), et les populations municipales Insee des communes, via l'API
 * Découpage administratif (voir {@link Populations}). Type {@code PLUGIN_MANAGED} : six fichiers, que le plugin
 * télécharge et dépose dans {@code rawDir}. Paramètres :
 * <pre>{@code
 * {"populationsUrl": "https://geo.api.gouv.fr/communes?fields=code,nom,population,codeDepartement,codeRegion,codeEpci&format=json",
 *  "mayorsUrl": "…/elus-maires-mai.csv", "epciUrl": "…/elus-conseillers-communautaires-epci.csv",
 *  "departmentsUrl": "…/elus-conseillers-departementaux-cd.csv", "regionsUrl": "…/elus-conseillers-regionaux-cr.csv",
 *  "specialUrl": "…/elus-membres-assemblee-ma.csv", "minPopulation": "50000"}
 * }</pre>
 *
 * <p>Seul le chef de l'exécutif est repris (maire, président), pas les adjoints ni les vice-présidents. Le RNE donne
 * la date de naissance : l'ID est le nom, le prénom et la date de naissance, comme dans les autres parsers.
 */
@Extension
public class FrPepLocalExecutivesParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-local-executives";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final long DEFAULT_MIN_POPULATION = 50_000;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - executifs locaux)";

    /** Paramètre d'adresse → fichier déposé dans {@code rawDir}. */
    static final Map<String, String> FILES = new LinkedHashMap<>();
    static {
        FILES.put("populationsUrl", "populations.json");
        FILES.put("mayorsUrl", "maires.csv");
        FILES.put("epciUrl", "epci.csv");
        FILES.put("departmentsUrl", "departements.csv");
        FILES.put("regionsUrl", "regions.csv");
        FILES.put("specialUrl", "collectivites.csv");
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    /** Un dirigeant gardé. */
    record Executive(String lastName, String firstName, String sex, String birthDate, String function,
            String collectivity, String collectivityCode, long population, String startDate, String topic) {
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int mayors, int epciPresidents, int departmentPresidents, int regionPresidents, int specialPresidents,
            long minPopulation) {

        String message() {
            return String.format("Collectivités de %d habitants ou plus : %d maires, %d présidents d'intercommunalité, "
                    + "%d présidents de conseil départemental, %d présidents de conseil régional, %d présidents de "
                    + "collectivité à statut particulier.", minPopulation, mayors, epciPresidents, departmentPresidents,
                    regionPresidents, specialPresidents);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(), p, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), minPopulation(p), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque dirigeant gardé, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), minPopulation(p), dataset);
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
            Files.write(rawDir.resolve(file.getValue()), fetcher.get(url.trim()));
        }
    }

    /** Lit les six fichiers de {@code rawDir} et écrit les dirigeants. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, long minPopulation, FtmDatasetBuilder dataset) throws Exception {
        Populations pop = Populations.read(file(rawDir, "populationsUrl"));
        List<Executive> mayors = new ArrayList<>();
        CsvRecordReader.readEach(file(rawDir, "mayorsUrl"), ';', row -> {
            long n = pop.commune(row.get("Code de la commune"));
            if (n >= minPopulation) {
                mayors.add(executive(row, "Maire", row.get("Libellé de la commune"), row.get("Code de la commune"), n,
                        row.get("Date de début de la fonction"), "gov.muni"));
            }
        });
        List<Executive> epci = presidents(file(rawDir, "epciUrl"), "Président du conseil communautaire",
                "Libellé de l'EPCI", "N° SIREN", pop::epci, minPopulation, "gov.muni");
        List<Executive> departments = presidents(file(rawDir, "departmentsUrl"), "Président du conseil départemental",
                "Libellé du département", "Code du département", pop::department, minPopulation, "gov.state");
        List<Executive> regions = presidents(file(rawDir, "regionsUrl"), "Président du conseil régional",
                "Libellé de la région", "Code de la région", pop::region, minPopulation, "gov.state");
        List<Executive> special = new ArrayList<>();
        CsvRecordReader.readEach(file(rawDir, "specialUrl"), ';', row -> {
            String function = row.get("Libellé de la fonction");
            String code = row.get("Code de la collectivité à statut particulier");
            if (function != null && function.startsWith("Président de l'") && code != null
                    && pop.specialCollectivity(code) >= minPopulation) {
                special.add(executive(row, function, row.get("Libellé de la collectivité à statut particulier"), code,
                        pop.specialCollectivity(code), row.get("Date de début de la fonction"), "gov.state"));
            }
        });
        if (mayors.isEmpty()) {
            throw new IllegalStateException("Aucun maire d'une commune de " + minPopulation + " habitants ou plus : "
                    + "fichiers incomplets ou format changé.");
        }
        for (List<Executive> list : List.of(mayors, epci, departments, regions, special)) {
            list.forEach(e -> write(e, dataset));
        }
        return new Report(mayors.size(), epci.size(), departments.size(), regions.size(), special.size(), minPopulation);
    }

    private interface PopulationOf {
        long of(String code);
    }

    /** Lignes dont la fonction est exactement {@code function}, pour une collectivité assez peuplée. */
    private static List<Executive> presidents(Path csv, String function, String labelColumn, String codeColumn,
            PopulationOf population, long minPopulation, String topic) throws Exception {
        List<Executive> list = new ArrayList<>();
        CsvRecordReader.readEach(csv, ';', row -> {
            long n = population.of(row.get(codeColumn));
            if (function.equals(row.get("Libellé de la fonction")) && n >= minPopulation) {
                list.add(executive(row, function, row.get(labelColumn), row.get(codeColumn), n,
                        row.get("Date de début de la fonction"), topic));
            }
        });
        return list;
    }

    private static Executive executive(CsvRow row, String function, String collectivity, String code, long population,
            String start, String topic) {
        return new Executive(row.get("Nom de l'élu"), row.get("Prénom de l'élu"), row.get("Code sexe"),
                toIsoDate(row.get("Date de naissance")), function, collectivity, code, population, toIsoDate(start), topic);
    }

    private static void write(Executive e, FtmDatasetBuilder dataset) {
        String title = e.function() + " – " + e.collectivity();               // « Maire – Lyon »

        // ID = nom + prénom + date de naissance : le RNE ne donne pas d'identifiant d'élu
        FtmEntity person = dataset.newEntity("Person", e.lastName(), e.firstName(), e.birthDate());
        person.addValue("name", joinNonBlank(" ", e.firstName(), e.lastName()));
        person.addValue("firstName", e.firstName());
        person.addValue("lastName", e.lastName());
        person.addValue("birthDate", e.birthDate());
        person.addValue("gender", toFtmGender(e.sex()));                       // « F » → female
        person.addValue("country", "fr");
        person.addValue("position", title);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", e.function(), e.collectivityCode());
        position.addValue("name", title);
        position.addValue("country", "fr");
        position.addValue("subnationalArea", e.collectivity());
        position.addValue("description", "Collectivité de " + e.population() + " habitants (population municipale Insee)");
        position.addValue("topics", e.topic());
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", e.lastName(), e.firstName(), e.birthDate(), e.function(),
                e.collectivityCode());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", e.startDate());
        occupancy.addValue("status", "current");
        dataset.addEntity(occupancy);
    }

    private static Path file(Path rawDir, String key) {
        Path path = rawDir.resolve(FILES.get(key));
        if (!Files.exists(path)) {
            throw new IllegalStateException(FILES.get(key) + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        return path;
    }

    static long minPopulation(Map<String, String> p) {
        String value = p.get("minPopulation");
        if (value == null || value.isBlank()) {
            return DEFAULT_MIN_POPULATION;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « minPopulation » invalide : « " + value + " » (nombre attendu).");
        }
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
