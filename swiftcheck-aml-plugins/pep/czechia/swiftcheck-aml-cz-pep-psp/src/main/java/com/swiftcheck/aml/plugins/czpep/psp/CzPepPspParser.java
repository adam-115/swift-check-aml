package com.swiftcheck.aml.plugins.czpep.psp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>cz_pep_parliament</b>, <b>cz_pep_government</b>, <b>cz_pep_president</b> et <b>cz_pep_oversight</b> – députés de la Chambre
 * des députés, membres des gouvernements, présidents de la République, dirigeants de la Cour des comptes (NKÚ) et médiateurs
 * tchèques, personnes politiquement exposées au sens de la loi tchèque contre le blanchiment (zákon č. 253/2008 Sb.).
 *
 * <p>Source officielle : les données ouvertes de la Chambre des députés
 * {@code https://www.psp.cz/eknih/cdrom/opendata/poslanci.zip} (fichiers {@code .unl}, séparateur « | », windows-1250) :
 * <ul>
 *   <li>{@code osoby} : personnes (nom, naissance, décès ; la date « 01.01.1900 » signifie « inconnue ») ;</li>
 *   <li>{@code organy} : organes (type 11 = Chambre, une ligne par législature ; 5 = gouvernement ; 18 = président ; 15 =
 *       institutions) avec dates ;</li>
 *   <li>{@code funkce} : fonctions d'un organe (Předseda vlády, Ministr…) ;</li>
 *   <li>{@code zarazeni} : appartenances ({@code cl_funkce} = 0 : membre d'un organe) et fonctions ({@code cl_funkce} = 1) avec
 *       dates exactes de début et de fin.</li>
 * </ul>
 * Les personnes décédées sont ignorées. Le paramètre {@code scope} choisit la couche : {@code parliament}, {@code government},
 * {@code president} ou {@code oversight} ; {@code monthsAfterEnd} fixe la fenêtre après la fin des fonctions (12 par défaut, 600
 * pour un historique). Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"url": "https://www.psp.cz/eknih/cdrom/opendata/poslanci.zip", "scope": "parliament", "monthsAfterEnd": "12"}
 * }</pre>
 */
@Extension
public class CzPepPspParser implements Iparser {

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String FILE = "poslanci.zip";
    static final String URL = "https://www.psp.cz/eknih/cdrom/opendata/poslanci.zip";
    static final List<String> SCOPES = List.of("parliament", "government", "president", "oversight");

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Tchequie - Poslanecka snemovna)";
    private static final Charset CP1250 = Charset.forName("windows-1250");

    /** Une personne du fichier {@code osoby}. */
    record Person(String id, String firstName, String lastName, String sex, String birthDate, boolean dead) {

        String name() {
            return (firstName + " " + lastName).trim();
        }
    }

    /** Un organe du fichier {@code organy}. */
    record Organ(String id, String type, String name) {
    }

    /** Une fonction du fichier {@code funkce}. */
    record Function(String id, String organId, String name) {
    }

    /** Une ligne retenue de {@code zarazeni}. */
    record Term(Person person, String role, String organization, String note, LocalDate start, LocalDate end) {
    }

    /** Lecture d'une adresse en octets ; remplacée dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String scope = scope(p);
        download(httpFetcher(), url(p), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(prefix(scope), TOPIC);
        String message = convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), scope, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String scope = scope(p);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(prefix(scope), TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), scope, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    static void download(Fetcher fetcher, String url, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Files.write(rawDir.resolve(FILE), fetcher.get(url));
    }

    /** Lit l'archive de {@code rawDir} et écrit la couche demandée. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, LocalDate today, int monthsAfterEnd, String scope, FtmDatasetBuilder dataset) throws IOException {
        Path zip = rawDir.resolve(FILE);
        if (!Files.exists(zip)) {
            throw new IllegalStateException(FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        List<Term> terms;
        int dead = 0;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            Map<String, Person> persons = persons(rows(z, "osoby.unl"));
            Map<String, Organ> organs = organs(rows(z, "organy.unl"));
            Map<String, Function> functions = functions(rows(z, "funkce.unl"));
            terms = terms(rows(z, "zarazeni.unl"), persons, organs, functions, scope);
            dead = (int) persons.values().stream().filter(Person::dead).count();
        }
        Map<String, List<Term>> byPerson = new LinkedHashMap<>();
        for (Term t : terms) {
            if (t.person().dead() || (t.end() != null && t.end().isBefore(cutoff)) || t.start().isAfter(today)) {
                continue;
            }
            byPerson.computeIfAbsent(t.person().id(), k -> new ArrayList<>()).add(t);
        }
        if (terms.isEmpty()) {
            throw new IllegalStateException("Aucune ligne d'appartenance lue pour « " + scope + " » : le format des fichiers a peut-être changé.");
        }
        for (List<Term> list : byPerson.values()) {
            write(list, scope, dataset);
        }
        return String.format("%d lignes lues, %d personnes décédées dans le fichier, %d personnes gardées (%s).", terms.size(), dead, byPerson.size(), scope);
    }

    private static void write(List<Term> list, String scope, FtmDatasetBuilder dataset) {
        Person p = list.get(0).person();
        FtmEntity person = dataset.newEntity("Person", p.id());
        person.addValue("name", p.name());
        person.addValue("firstName", p.firstName());
        person.addValue("lastName", p.lastName());
        person.addValue("gender", "M".equals(p.sex()) ? "male" : "Z".equals(p.sex()) ? "female" : null);
        person.addValue("birthDate", p.birthDate());
        person.addValue("country", "cz");
        for (String r : new TreeSet<>(list.stream().map(Term::role).toList())) {
            person.addValue("position", r);
        }
        dataset.addEntity(person);

        for (Term t : list) {
            FtmEntity position = dataset.newEntity("Position", scope, t.role(), t.organization());
            position.addValue("name", t.role());
            position.addValue("country", "cz");
            position.addValue("organization", t.organization());
            position.addValue("topics", "gov.national");
            position.addValue("topics", switch (scope) {
                case "parliament" -> "gov.legislative";
                case "government", "president" -> "gov.executive";
                default -> "gov.financial";
            });
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", p.id(), scope, t.role(), t.organization(), String.valueOf(t.start()));
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", t.start().toString());
            occupancy.addValue("endDate", t.end() == null ? null : t.end().toString());
            occupancy.addValue("status", t.end() == null ? "current" : "ended");
            occupancy.addValue("description", t.note());
            dataset.addEntity(occupancy);
        }
    }

    /** Les lignes retenues de {@code zarazeni} pour la couche demandée. */
    static List<Term> terms(List<String[]> rows, Map<String, Person> persons, Map<String, Organ> organs, Map<String, Function> functions,
            String scope) {
        List<Term> out = new ArrayList<>();
        for (String[] r : rows) {
            Person person = persons.get(col(r, 0));
            LocalDate start = date(col(r, 3));
            if (person == null || start == null) {
                continue;
            }
            LocalDate end = date(col(r, 4));
            boolean isFunction = "1".equals(col(r, 2));
            if (!isFunction) {
                Organ organ = organs.get(col(r, 1));
                if ("parliament".equals(scope) && organ != null && "11".equals(organ.type())) {
                    out.add(new Term(person, "Député à la Chambre des députés (poslanec)", "Poslanecká sněmovna",
                            "Législature : " + organ.name() + " (" + abbreviation(organ) + ")", start, end));
                }
                continue;
            }
            Function f = functions.get(col(r, 1));
            Organ organ = f == null ? null : organs.get(f.organId());
            if (f == null || organ == null) {
                continue;
            }
            String role = role(scope, organ, f);
            if (role != null) {
                out.add(new Term(person, role, organization(scope, organ), "Fonction publiée : " + f.name() + " (" + organ.name() + ")", start, end));
            }
        }
        return out;
    }

    private static String abbreviation(Organ o) {
        return o.name();
    }

    /** Libellé français de la fonction pour la couche demandée, {@code null} si elle n'en fait pas partie. */
    static String role(String scope, Organ organ, Function f) {
        String n = f.name();
        switch (scope) {
            case "parliament":
                if ("11".equals(organ.type())) {
                    if (n.equals("Předseda")) {
                        return "Président de la Chambre des députés (předseda Poslanecké sněmovny)";
                    }
                    if (n.equals("Místopředseda")) {
                        return "Vice-président de la Chambre des députés (místopředseda Poslanecké sněmovny)";
                    }
                }
                return null;
            case "government":
                if (!"5".equals(organ.type())) {
                    return null;
                }
                if (n.equals("Předseda vlády")) {
                    return "Premier ministre (předseda vlády)";
                }
                if (n.equals("Místopředseda vlády")) {
                    return "Vice-premier ministre (místopředseda vlády)";
                }
                return "Ministre : " + n;
            case "president":
                return "18".equals(organ.type()) && n.equals("Prezident") ? "Président de la République tchèque (prezident republiky)" : null;
            case "oversight":
                if (organ.name().contains("Nejvyšší kontrolní úřad")) {
                    return switch (n) {
                        case "Prezident NKÚ" -> "Président de la Cour des comptes (prezident NKÚ)";
                        case "Viceprezident NKÚ" -> "Vice-président de la Cour des comptes (viceprezident NKÚ)";
                        case "Člen NKÚ" -> "Membre du collège de la Cour des comptes (člen NKÚ)";
                        default -> null;
                    };
                }
                if (organ.name().contains("veřejného ochránce práv")) {
                    if (n.equals("Veřejný ochránce práv")) {
                        return "Médiateur (veřejný ochránce práv)";
                    }
                    if (n.startsWith("Zástupce")) {
                        return "Médiateur adjoint (zástupce veřejného ochránce práv)";
                    }
                    if (n.equals("Ochránce práv dětí")) {
                        return "Défenseur des droits de l'enfant (ochránce práv dětí)";
                    }
                }
                return null;
            default:
                return null;
        }
    }

    private static String organization(String scope, Organ organ) {
        return switch (scope) {
            case "parliament" -> "Poslanecká sněmovna";
            case "government" -> "Vláda České republiky";
            case "president" -> "Prezident republiky";
            default -> organ.name();
        };
    }

    static Map<String, Person> persons(List<String[]> rows) {
        Map<String, Person> out = new HashMap<>();
        for (String[] r : rows) {
            String born = col(r, 5);
            LocalDate b = date(born);
            out.put(col(r, 0), new Person(col(r, 0), col(r, 3), col(r, 2), col(r, 6),
                    b == null || b.equals(LocalDate.of(1900, 1, 1)) ? null : b.toString(), col(r, 8) != null));
        }
        return out;
    }

    static Map<String, Organ> organs(List<String[]> rows) {
        Map<String, Organ> out = new HashMap<>();
        rows.forEach(r -> out.put(col(r, 0), new Organ(col(r, 0), col(r, 2), col(r, 4))));
        return out;
    }

    static Map<String, Function> functions(List<String[]> rows) {
        Map<String, Function> out = new HashMap<>();
        rows.forEach(r -> out.put(col(r, 0), new Function(col(r, 0), col(r, 1), col(r, 3))));
        return out;
    }

    /** Les lignes d'un fichier {@code .unl} de l'archive (séparateur « | », windows-1250). */
    static List<String[]> rows(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IllegalStateException("Fichier " + name + " absent de l'archive : le format a peut-être changé.");
        }
        List<String[]> out = new ArrayList<>();
        try (InputStream in = zip.getInputStream(entry)) {
            for (String line : new String(in.readAllBytes(), CP1250).split("\\r?\\n")) {
                if (!line.isBlank()) {
                    out.add(line.split("\\|", -1));
                }
            }
        }
        return out;
    }

    private static String col(String[] r, int i) {
        return i < r.length && !r[i].isBlank() ? r[i].trim() : null;
    }

    /** « 2021-12-17 11 » ou « 17.12.2021 » → date ; {@code null} si vide ou illisible. */
    private static LocalDate date(String v) {
        if (v == null) {
            return null;
        }
        try {
            if (v.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
                return LocalDate.parse(v.substring(0, 10));
            }
            if (v.matches("^\\d{2}\\.\\d{2}\\.\\d{4}$")) {
                return LocalDate.of(Integer.parseInt(v.substring(6)), Integer.parseInt(v.substring(3, 5)), Integer.parseInt(v.substring(0, 2)));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    static String scope(Map<String, String> p) {
        String v = p.get("scope");
        if (v == null || v.isBlank()) {
            return "parliament";
        }
        if (!SCOPES.contains(v.trim())) {
            throw new IllegalArgumentException("Paramètre « scope » invalide : « " + v + " » (valeurs : " + SCOPES + ").");
        }
        return v.trim();
    }

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static String prefix(String scope) {
        return "cz-pep-" + scope;
    }

    static int monthsAfterEnd(Map<String, String> p) {
        String v = p.get("monthsAfterEnd");
        if (v == null || v.isBlank()) {
            return 12;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « monthsAfterEnd » invalide : « " + v + " » (nombre de mois attendu).");
        }
    }

    private static String url(Map<String, String> p) {
        String v = p.get("url");
        return v == null || v.isBlank() ? URL : v.trim();
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
