package com.swiftcheck.aml.plugins.nopep.stateenterprises;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
 * <b>no_pep_state_enterprises</b> – dirigeants (directeur général, président, vice-président et membres du conseil
 * d'administration) des entreprises détenues par l'État norvégien, d'après le registre officiel de Brønnøysund
 * ({@code data.brreg.no/enhetsregisteret}, données ouvertes sous licence NLOD).
 *
 * <p>Les entreprises sont celles du secteur institutionnel 1120 (sociétés non financières publiques, filiales comprises)
 * et des formes juridiques demandées (les « statsforetak », SF) ; pour chacune, l'API {@code /enheter/{orgnr}/roller} donne
 * les rôles : {@code DAGL} (directeur général), {@code LEDE} (président du conseil), {@code NEST} (vice-président),
 * {@code MEDL} (membre) et {@code VARA} (suppléant, repris seulement avec {@code includeAlternates}). Seules les personnes
 * physiques en poste et non décédées sont reprises. Le registre ne donne pas d'identifiant de personne : l'identité est le nom
 * et la date de naissance.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"apiUrl": "https://data.brreg.no/enhetsregisteret/api", "sectors": "1120", "forms": "SF",
 *  "includeAlternates": "false", "delayMs": "200"}
 * }</pre>
 * Environ 730 entreprises : plusieurs minutes d'exécution. Les rôles des filiales d'une même entreprise donnent souvent les
 * mêmes personnes : elles ne sont publiées qu'une fois avec toutes leurs fonctions.
 */
@Extension
public class NoPepStateEnterprisesParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "no-pep-state-enterprises";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String ENTITIES_PREFIX = "entities-";
    static final String ROLES_PREFIX = "roles-";
    static final int PAGE_SIZE = 100;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Norvege - entreprises d'Etat)";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> ROLES = Map.of("DAGL", "Directeur général (Daglig leder)",
            "LEDE", "Président du conseil (Styrets leder)", "NEST", "Vice-président du conseil (Nestleder)",
            "MEDL", "Membre du conseil (Styremedlem)", "VARA", "Suppléant du conseil (Varamedlem)");

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Un dirigeant. */
    record Executive(String orgNumber, String company, String roleCode, String name, String firstName, String lastName, String birthDate) {
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int companies, int persons, int roles, int skippedEnded, int skippedDead) {

        String message() {
            return String.format("%d entreprises, %d personnes, %d fonctions ; %d fonctions terminées et %d personnes décédées écartées.",
                    companies, persons, roles, skippedEnded, skippedDead);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(delayMs(p)), apiUrl(p), list(p.getOrDefault("sectors", "1120")), list(p.getOrDefault("forms", "SF")), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), alternates(p), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque dirigeant, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), alternates(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Télécharge la liste des entreprises (secteurs et formes demandés) puis les rôles de chacune. */
    static void download(Fetcher fetcher, String apiUrl, List<String> sectors, List<String> forms, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Map<String, String> companies = new LinkedHashMap<>();
        List<String> filters = new ArrayList<>();
        sectors.forEach(s -> filters.add("institusjonellSektorkode=" + s));
        forms.forEach(f -> filters.add("organisasjonsform=" + f));
        for (String filter : filters) {
            for (int page = 0; ; page++) {
                String body = fetcher.get(apiUrl + "/enheter?" + filter + "&size=" + PAGE_SIZE + "&page=" + page);
                Files.writeString(rawDir.resolve(ENTITIES_PREFIX + filter.replace('=', '-') + "-" + String.format("%03d", page) + ".json"),
                        body, StandardCharsets.UTF_8);
                JsonNode root = JSON.readTree(body);
                JsonNode enheter = root.path("_embedded").path("enheter");
                for (JsonNode e : enheter) {
                    companies.putIfAbsent(e.path("organisasjonsnummer").asText(), e.path("navn").asText());
                }
                int totalPages = root.path("page").path("totalPages").asInt(0);
                if (page + 1 >= totalPages || enheter.isEmpty()) {
                    break;
                }
            }
        }
        if (companies.isEmpty()) {
            throw new IllegalStateException("Aucune entreprise pour les secteurs " + sectors + " et formes " + forms
                    + " : le format de l'API a peut-être changé.");
        }
        for (Map.Entry<String, String> c : companies.entrySet()) {
            Files.writeString(rawDir.resolve(ROLES_PREFIX + c.getKey() + ".json"),
                    fetcher.get(apiUrl + "/enheter/" + c.getKey() + "/roller"), StandardCharsets.UTF_8);
        }
    }

    /** Lit les fichiers de {@code rawDir} et écrit les dirigeants. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, boolean includeAlternates, FtmDatasetBuilder dataset) throws IOException {
        Map<String, String> companies = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(rawDir)) {
            files = s.filter(f -> f.getFileName().toString().startsWith(ENTITIES_PREFIX)).sorted().toList();
        }
        for (Path f : files) {
            for (JsonNode e : JSON.readTree(Files.readString(f, StandardCharsets.UTF_8)).path("_embedded").path("enheter")) {
                companies.putIfAbsent(e.path("organisasjonsnummer").asText(), e.path("navn").asText());
            }
        }
        if (companies.isEmpty()) {
            throw new IllegalStateException("Aucune entreprise dans " + rawDir + " (fichiers entities-*.json) : le téléchargement n'a pas eu lieu.");
        }
        Set<String> persons = new java.util.HashSet<>();
        int roles = 0, ended = 0, dead = 0, read = 0;
        for (Map.Entry<String, String> c : companies.entrySet()) {
            Path file = rawDir.resolve(ROLES_PREFIX + c.getKey() + ".json");
            if (!Files.exists(file)) {
                continue;
            }
            read++;
            for (JsonNode group : JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)).path("rollegrupper")) {
                for (JsonNode role : group.path("roller")) {
                    String code = role.path("type").path("kode").asText();
                    JsonNode person = role.path("person");
                    if (person.isMissingNode() || !ROLES.containsKey(code) || (code.equals("VARA") && !includeAlternates)) {
                        continue;                                   // revisor, comptable, observateur, entreprises…
                    }
                    if (role.path("fratraadt").asBoolean(false)) {
                        ended++;
                        continue;
                    }
                    if (person.path("erDoed").asBoolean(false)) {
                        dead++;
                        continue;
                    }
                    JsonNode n = person.path("navn");
                    String first = (n.path("fornavn").asText("") + " " + n.path("mellomnavn").asText("")).trim();
                    String last = n.path("etternavn").asText("");
                    Executive e = new Executive(c.getKey(), c.getValue(), code, (first + " " + last).trim(), first, last,
                            person.path("fodselsdato").isNull() || person.path("fodselsdato").asText().isBlank() ? null : person.path("fodselsdato").asText());
                    if (e.name().isBlank()) {
                        continue;
                    }
                    write(e, dataset);
                    persons.add(e.name() + "|" + e.birthDate());
                    roles++;
                }
            }
        }
        if (roles == 0) {
            throw new IllegalStateException("Aucun dirigeant lu dans " + rawDir + " (fichiers roles-*.json) : fichiers incomplets ou format changé.");
        }
        return new Report(read, persons.size(), roles, ended, dead);
    }

    private static void write(Executive e, FtmDatasetBuilder dataset) {
        String label = ROLES.get(e.roleCode()) + ", " + e.company();

        // ID = nom + date de naissance : le registre ne donne pas d'identifiant de personne
        FtmEntity person = dataset.newEntity("Person", e.name(), e.birthDate());
        person.addValue("name", e.name());
        person.addValue("firstName", e.firstName());
        person.addValue("lastName", e.lastName());
        person.addValue("birthDate", e.birthDate());
        person.addValue("country", "no");
        person.addValue("position", label);
        person.addValue("sourceUrl", "https://data.brreg.no/enhetsregisteret/api/enheter/" + e.orgNumber() + "/roller");
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", e.orgNumber(), e.roleCode());
        position.addValue("name", label);
        position.addValue("country", "no");
        position.addValue("organization", e.company());
        position.addValue("topics", "gov.soe");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", e.name(), e.birthDate(), e.orgNumber(), e.roleCode());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", "Numéro d'organisation : " + e.orgNumber());
        dataset.addEntity(occupancy);
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<>();
        for (String s : value.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static String apiUrl(Map<String, String> p) {
        String v = p.get("apiUrl");
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Paramètre « apiUrl » manquant dans la configuration du parser (voir le README du plugin).");
        }
        return v.trim().endsWith("/") ? v.trim().substring(0, v.trim().length() - 1) : v.trim();
    }

    private static boolean alternates(Map<String, String> p) {
        return "true".equalsIgnoreCase(p.getOrDefault("includeAlternates", "false").trim());
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 200 : Long.parseLong(v.trim());
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(delayMs);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2)).header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return "{}";                                      // entreprise supprimée entre la liste et les rôles
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
