package com.swiftcheck.aml.plugins.sepep.riksdagen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.pf4j.Extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>se_pep_parliament</b>, <b>se_pep_government</b>, <b>se_pep_parties</b> et <b>se_pep_oversight</b> – députés du Riksdag,
 * ministres, chefs de parti, Riksrevisor et médiateurs parlementaires (JO) suédois, personnes politiquement exposées au sens
 * de la loi suédoise sur les mesures contre le blanchiment (penningtvättslagen).
 *
 * <p>Source officielle : l'API ouverte du Riksdag {@code https://data.riksdagen.se/personlista/?utformat=json&rdlstatus=samtliga}
 * (un seul appel JSON, environ 19 Mo, sans clé) : toutes les personnes ayant eu un mandat au Riksdag, avec naissance (année),
 * sexe, statut (« Avliden AAAA-MM-JJ » pour les décédés) et la liste datée de leurs missions ({@code personuppdrag}) :
 * mandat de député ({@code kam}), présidence du Riksdag, fonctions de ministre ({@code Statsråd}, organe = département),
 * chef de parti ({@code Partiledare}, {@code Språkrör}), Riksrevisionen ({@code RR}) et médiateurs ({@code JO}).
 *
 * <p>Le paramètre {@code scope} choisit la couche : {@code parliament}, {@code government}, {@code parties} ou
 * {@code oversight}. {@code monthsAfterEnd} fixe la fenêtre après la fin des fonctions (12 mois par défaut, 600 pour un
 * historique). Les personnes décédées sont ignorées. Une mission dont la fin est dans le futur (mandat de quatre ans) est en
 * cours. Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"apiUrl": "https://data.riksdagen.se", "scope": "parliament", "monthsAfterEnd": "12"}
 * }</pre>
 */
@Extension
public class SePepRiksdagenParser implements Iparser {

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String FILE = "personlista.json";
    static final List<String> SCOPES = List.of("parliament", "government", "parties", "oversight");

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Suede - Riksdagen)";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> DEPARTMENTS = Map.ofEntries(
            Map.entry("SB", "Statsrådsberedningen (cabinet du Premier ministre)"), Map.entry("UD", "Utrikesdepartementet"),
            Map.entry("Ju", "Justitiedepartementet"), Map.entry("Fö", "Försvarsdepartementet"),
            Map.entry("S", "Socialdepartementet"), Map.entry("Fi", "Finansdepartementet"),
            Map.entry("U", "Utbildningsdepartementet"), Map.entry("N", "Näringsdepartementet"),
            Map.entry("Jo", "Jordbruksdepartementet"), Map.entry("A", "Arbetsmarknadsdepartementet"),
            Map.entry("KN", "Kulturdepartementet"), Map.entry("I", "Infrastrukturdepartementet"),
            Map.entry("M", "Miljödepartementet"), Map.entry("LI", "Landsbygds- och infrastrukturdepartementet"),
            Map.entry("IJ", "Integrations- och jämställdhetsdepartementet"));

    private static final Map<String, String> PARTIES = Map.of("S", "Socialdemokraterna", "M", "Moderaterna",
            "SD", "Sverigedemokraterna", "C", "Centerpartiet", "V", "Vänsterpartiet", "KD", "Kristdemokraterna",
            "L", "Liberalerna", "MP", "Miljöpartiet");

    /** Une mission datée. */
    record Term(String organ, String role, String type, String status, LocalDate start, LocalDate end) {
    }

    /** Une personne de l'API. */
    record Member(String id, String firstName, String lastName, String sex, String birthYear, boolean dead, String party,
            List<Term> terms) {

        String fullName() {
            return (firstName + " " + lastName).trim();
        }
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String scope = scope(p);
        download(httpFetcher(), apiUrl(p), ctx.rawDir());
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

    static void download(Fetcher fetcher, String apiUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        Files.writeString(rawDir.resolve(FILE), fetcher.get(base + "/personlista/?utformat=json&rdlstatus=samtliga"), StandardCharsets.UTF_8);
    }

    /** Lit {@code personlista.json} et écrit la couche demandée. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, LocalDate today, int monthsAfterEnd, String scope, FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(FILE);
        if (!Files.exists(file)) {
            throw new IllegalStateException(FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        List<Member> members = members(Files.readString(file, StandardCharsets.UTF_8));
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucune personne dans " + FILE + " : le format de l'API a peut-être changé.");
        }
        int dead = 0;
        int kept = 0;
        for (Member m : members) {
            if (m.dead()) {
                dead++;
                continue;
            }
            List<Term> terms = m.terms().stream().filter(t -> role(scope, t) != null
                    && (t.end() == null || !t.end().isBefore(cutoff)) && !t.start().isAfter(today)).toList();
            if (terms.isEmpty()) {
                continue;
            }
            write(m, terms, scope, dataset);
            kept++;
        }
        return String.format("%d personnes lues, %d décédées écartées, %d personnes gardées (%s).", members.size(), dead, kept, scope);
    }

    private static void write(Member m, List<Term> terms, String scope, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", m.id());
        person.addValue("name", m.fullName());
        person.addValue("firstName", m.firstName());
        person.addValue("lastName", m.lastName());
        person.addValue("gender", "man".equals(m.sex()) ? "male" : "kvinna".equals(m.sex()) ? "female" : null);
        person.addValue("birthDate", m.birthYear());
        person.addValue("country", "se");
        for (String r : new TreeSet<>(terms.stream().map(t -> role(scope, t)).toList())) {
            person.addValue("position", r);
        }
        dataset.addEntity(person);

        for (Term t : terms) {
            String role = role(scope, t);
            FtmEntity position = dataset.newEntity("Position", scope, role);
            position.addValue("name", role);
            position.addValue("country", "se");
            position.addValue("organization", organization(scope, t));
            position.addValue("topics", "gov.national");
            position.addValue("topics", topic(scope));
            dataset.addEntity(position);

            LocalDate end = t.end() != null && t.end().isBefore(LocalDate.now()) ? t.end() : null;
            FtmEntity occupancy = dataset.newEntity("Occupancy", m.id(), scope, role, String.valueOf(t.start()));
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", t.start().toString());
            occupancy.addValue("endDate", end == null ? null : end.toString());
            occupancy.addValue("status", end == null ? "current" : "ended");
            List<String> parts = new ArrayList<>();
            if (t.end() != null && end == null) {
                parts.add("Fin prévue : " + t.end());
            }
            if ("Ledig".equals(t.status())) {
                parts.add("Période de congé (le siège est occupé par un remplaçant)");
            }
            if ("parliament".equals(scope) && m.party() != null) {
                parts.add("Parti : " + m.party());
            }
            if (!parts.isEmpty()) {
                occupancy.addValue("description", String.join(" ; ", parts));
            }
            dataset.addEntity(occupancy);
        }
    }

    /** Libellé de la fonction pour la couche demandée, {@code null} si la mission n'en fait pas partie. */
    static String role(String scope, Term t) {
        String r = t.role();
        switch (scope) {
            case "parliament":
                if ("kam".equals(t.organ())) {
                    return switch (r) {
                        case "Riksdagsledamot" -> "Député au Riksdag (riksdagsledamot)";
                        case "Talman" -> "Président du Riksdag (talman)";
                        case "Förste vice talman" -> "Premier vice-président du Riksdag (förste vice talman)";
                        case "Andre vice talman" -> "Deuxième vice-président du Riksdag (andre vice talman)";
                        case "Tredje vice talman" -> "Troisième vice-président du Riksdag (tredje vice talman)";
                        default -> null;
                    };
                }
                return null;
            case "government":
                if ("Departement".equals(t.type()) && r != null) {
                    return "Statsminister".equals(r) ? "Premier ministre (statsminister)"
                            : "Ministre (" + r.toLowerCase() + ") – " + department(t.organ());
                }
                return null;
            case "parties":
                if ("partiuppdrag".equals(t.type()) && ("Partiledare".equals(r) || "Språkrör".equals(r))) {
                    String party = PARTIES.getOrDefault(t.organ(), t.organ());
                    return ("Partiledare".equals(r) ? "Chef de parti (partiledare) – " : "Porte-parole du parti (språkrör) – ") + party;
                }
                return null;
            case "oversight":
                if ("RR".equals(t.organ())) {
                    return switch (r) {
                        case "Riksrevisor" -> "Riksrevisor (auditeur général, Riksrevisionen)";
                        case "Riksrevisor med administrativt ansvar" -> "Riksrevisor avec responsabilité administrative (Riksrevisionen)";
                        case "Riksrevisionsdirektör" -> "Directeur de Riksrevisionen (riksrevisionsdirektör)";
                        default -> null;
                    };
                }
                if ("JO".equals(t.organ())) {
                    return switch (r) {
                        case "Chefsjustitieombudsman" -> "Médiateur parlementaire en chef (chefsjustitieombudsman)";
                        case "Justitieombudsman" -> "Médiateur parlementaire (justitieombudsman)";
                        case "Ställföreträdande justitieombudsman", "Ställföreträdande ombudsman" ->
                            "Médiateur parlementaire adjoint (ställföreträdande justitieombudsman)";
                        default -> null;
                    };
                }
                return null;
            default:
                return null;
        }
    }

    private static String organization(String scope, Term t) {
        return switch (scope) {
            case "parliament" -> "Riksdagen";
            case "government" -> department(t.organ());
            case "parties" -> PARTIES.getOrDefault(t.organ(), t.organ());
            default -> "RR".equals(t.organ()) ? "Riksrevisionen" : "Riksdagens ombudsmän (JO)";
        };
    }

    private static String topic(String scope) {
        return switch (scope) {
            case "parliament" -> "gov.legislative";
            case "government" -> "gov.executive";
            case "parties" -> "gov.national";
            default -> "gov.financial";
        };
    }

    private static String department(String organ) {
        String code = organ == null ? "" : organ.replaceAll("\\s+t\\.o\\.m\\..*$", "").trim();
        return DEPARTMENTS.getOrDefault(code, "Département " + code);
    }

    /** Les personnes du fichier JSON de l'API. */
    static List<Member> members(String json) throws IOException {
        JsonNode persons = MAPPER.readTree(json).path("personlista").path("person");
        List<Member> out = new ArrayList<>();
        for (JsonNode p : nodes(persons)) {
            String id = text(p, "intressent_id") != null ? text(p, "intressent_id") : text(p, "sourceid");
            if (id == null) {
                continue;
            }
            String status = text(p, "status");
            List<Term> terms = new ArrayList<>();
            JsonNode uppdrag = p.path("personuppdrag").path("uppdrag");
            for (JsonNode u : nodes(uppdrag)) {
                LocalDate start = date(text(u, "from"));
                if (start != null) {
                    terms.add(new Term(text(u, "organ_kod"), text(u, "roll_kod"), text(u, "typ"), text(u, "status"), start,
                            date(text(u, "tom"))));
                }
            }
            String birth = text(p, "fodd_ar");
            out.add(new Member(id, text(p, "tilltalsnamn"), text(p, "efternamn"), text(p, "kon"),
                    birth != null && birth.matches("\\d{4}") ? birth : null, status != null && status.startsWith("Avliden"),
                    text(p, "parti"), terms));
        }
        return out;
    }

    /** Les éléments d'un nœud JSON : le tableau, ou le nœud seul quand il n'y en a qu'un (l'API ne met alors pas de tableau). */
    private static List<JsonNode> nodes(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(out::add);
        } else if (n.isObject()) {
            out.add(n);
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate date(String v) {
        if (v == null || v.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(v.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
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
        return "se-pep-" + scope;
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

    private static String apiUrl(Map<String, String> p) {
        String v = p.get("apiUrl");
        return v == null || v.isBlank() ? "https://data.riksdagen.se" : v.trim();
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
