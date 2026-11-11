package com.swiftcheck.aml.plugins.bepep.publicenterprises;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>be_pep_public_enterprises</b> – Dirigeants, administrateurs et commissaires du gouvernement des entreprises
 * publiques belges (catégorie <b>BE-g</b>, loi du 18 septembre 2017, Annexe IV, point 7).
 *
 * <p>Sources combinées (type {@code PLUGIN_MANAGED}) :
 * <ul>
 *   <li>le registre officiel des mandats de la <b>Cour des comptes</b> ({@code https://public.regimand.be}), interrogé
 *       par entreprise publique configurée dans {@code public-enterprises.json} ;</li>
 *   <li>la page du portefeuille d'investissements de la <b>SFPI</b> ({@code https://www.sfpi-fpim.be/fr/investissements/}).</li>
 * </ul>
 *
 * <p>Seules les fonctions d'administration, de direction et de commissaire du gouvernement sont retenues
 * ({@link #GOVERNANCE_ROLE}) ; les emplois salariés ordinaires (« employé », « ouvrier », « retraité ») sont écartés.
 */
@Extension
public class BePepPublicEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepPublicEnterprisesParser.class);

    static final String ID_PREFIX = "be-pep-public-enterprises";
    static final String TOPIC_PEP = "role.pep";
    static final String TOPIC_SOE = "gov.soe";
    static final String DEFAULT_REGIMAND_URL = "https://public.regimand.be";
    static final String DEFAULT_SFPI_URL = "https://www.sfpi-fpim.be/fr/investissements/";
    static final String SFPI_FILE = "sfpi.html";
    static final String SOURCE_URL = "https://public.regimand.be";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - entreprises publiques)";

    /** Fonctions de gouvernance retenues (Annexe IV, point 7 : CA, comité de direction, commissaire du gouvernement). */
    private static final Pattern GOVERNANCE_ROLE = Pattern.compile(
            "administrateur|administratrice|bestuurder|conseil d['’]administration|raad van bestuur|verwaltungsrat"
                    + "|président|présidente|voorzitter|vice-président|ondervoorzitter"
                    + "|comité de direction|directiecomit[eé]|comité exécutif|uitvoerend comit[eé]"
                    + "|directeur|directrice|gedelegeerd|délégué|ceo|administrateur général"
                    + "|commissaire du gouvernement|regeringscommissaris|commissaire"
                    + "|secrétaire général|secretaris-generaal|membre du bureau|comité d['’]audit|auditcomit[eé]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Fonctions non dirigeantes explicitement exclues. */
    private static final Pattern NON_GOVERNANCE_ROLE = Pattern.compile(
            "^employée?$|^bediende$|^ouvrier|^arbeider|^retraitée?$|^gepensioneerd|^stagiaire|^étudiant",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final ObjectMapper JSON = new ObjectMapper();

    record EnterpriseConfig(String slug, String query, String name, List<String> keywords, String description) {
    }

    record Report(int enterprises, int persons, int mandates, boolean sfpiAvailable) {
        String message() {
            return String.format(
                    "%d entreprises publiques belges contrôlées (Regimand Cour des comptes%s) : %d dirigeants, administrateurs et commissaires du gouvernement (%d mandats).",
                    enterprises, sfpiAvailable ? " + portefeuille SFPI" : "", persons, mandates);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<EnterpriseConfig> configs = loadConfig(p.get("enterprises"));
        download(p.getOrDefault("regimandUrl", DEFAULT_REGIMAND_URL).trim(),
                p.getOrDefault("sfpiUrl", DEFAULT_SFPI_URL).trim(),
                p.get("year"), ctx.rawDir(), configs);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        Report report = convert(ctx.rawDir(), dataset, configs);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<EnterpriseConfig> configs = loadConfig(p.get("enterprises"));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        convert(ctx.rawDir(), dataset, configs);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    void download(String regimandBaseUrl, String sfpiUrl, String yearParam, Path rawDir)
            throws IOException, InterruptedException {
        download(regimandBaseUrl, sfpiUrl, yearParam, rawDir, loadConfig());
    }

    void download(String regimandBaseUrl, String sfpiUrl, String yearParam, Path rawDir, List<EnterpriseConfig> configs)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String token = fetchGuestToken(http, regimandBaseUrl);
        int year = (yearParam != null && !yearParam.isBlank())
                ? Integer.parseInt(yearParam.trim())
                : fetchLatestYear(http, regimandBaseUrl, token);

        for (EnterpriseConfig ent : configs) {
            String body = JSON.writeValueAsString(Map.of("year", year, "institutionName", ent.query()));
            HttpRequest req = HttpRequest.newBuilder(URI.create(regimandBaseUrl + "/api/Statement/PublicSearch"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                throw new IOException("Regimand PublicSearch HTTP " + res.statusCode() + " pour " + ent.query());
            }
            Files.write(rawDir.resolve(statementFileName(ent.slug())), res.body());
        }

        if (sfpiUrl != null && !sfpiUrl.isBlank()) {
            try {
                HttpRequest sfpiReq = HttpRequest.newBuilder(URI.create(sfpiUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", USER_AGENT)
                        .GET().build();
                HttpResponse<byte[]> sfpiRes = http.send(sfpiReq, HttpResponse.BodyHandlers.ofByteArray());
                if (sfpiRes.statusCode() == 200) {
                    Files.write(rawDir.resolve(SFPI_FILE), sfpiRes.body());
                }
            } catch (Exception e) {
                log.warn("Page SFPI indisponible ({}) : {}", sfpiUrl, e.getMessage());
            }
        }
    }

    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        return convert(rawDir, dataset, loadConfig());
    }

    Report convert(Path rawDir, FtmDatasetBuilder dataset, List<EnterpriseConfig> configs) throws IOException {
        Set<String> activeEnterprises = new LinkedHashSet<>();
        Set<String> persons = new LinkedHashSet<>();
        Set<String> writtenMandates = new LinkedHashSet<>();

        for (EnterpriseConfig ent : configs) {
            Path file = rawDir.resolve(statementFileName(ent.slug()));
            if (!Files.exists(file)) {
                continue;
            }
            JsonNode statements = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!statements.isArray()) {
                continue;
            }

            FtmEntity company = dataset.newEntity("Company", "company", ent.slug());
            company.addValue("name", ent.name());
            company.addValue("jurisdiction", "be");
            company.addValue("description", ent.description());
            company.addValue("topics", TOPIC_SOE);
            company.addValue("sourceUrl", SOURCE_URL);

            boolean enterpriseHasLeaders = false;

            for (JsonNode st : statements) {
                JsonNode profile = st.path("profile");
                String profileId = profile.path("id").asText("");
                String firstName = profile.path("firstName").asText("").trim();
                String lastName = profile.path("lastName").asText("").trim();
                String fullName = joinNonBlank(" ", firstName, lastName);
                if (fullName.isBlank()) {
                    continue;
                }

                for (JsonNode tenure : st.path("tenures")) {
                    String instName = translation(tenure.path("institution").path("translations"));
                    if (!matchesEnterprise(instName, ent)) {
                        continue;
                    }
                    String role = translation(tenure.path("functionType").path("translations"));
                    if (!isGovernanceRole(role)) {
                        continue;
                    }
                    String startDate = isoDate(tenure.path("startDate").asText(null));
                    String endDate = isoDate(tenure.path("endDate").asText(null));
                    String mandateKey = profileId + "|" + ent.slug() + "|" + role.toLowerCase(Locale.ROOT) + "|"
                            + (startDate == null ? "" : startDate);
                    if (!writtenMandates.add(mandateKey)) {
                        continue;
                    }

                    enterpriseHasLeaders = true;
                    String personKey = profileId.isBlank() ? fullName.toLowerCase(Locale.ROOT) : profileId;
                    persons.add(personKey);

                    String title = role + " – " + ent.name();
                    FtmEntity person = dataset.newEntity("Person", "regimand", personKey);
                    person.addValue("name", fullName);
                    person.addValue("firstName", firstName);
                    person.addValue("lastName", lastName);
                    person.addValue("country", "be");
                    person.addValue("position", title);
                    person.addValue("topics", TOPIC_PEP);
                    person.addValue("sourceUrl", SOURCE_URL);
                    dataset.addEntity(person);

                    FtmEntity position = dataset.newEntity("Position", "pos", ent.slug(), role.toLowerCase(Locale.ROOT));
                    position.addValue("name", title);
                    position.addValue("organization", ent.name());
                    position.addValue("country", "be");
                    position.addValue("topics", "gov.national");
                    position.addValue("topics", TOPIC_SOE);
                    position.addValue("sourceUrl", SOURCE_URL);
                    dataset.addEntity(position);

                    FtmEntity occupancy = dataset.newEntity("Occupancy", "occ", personKey, ent.slug(),
                            role.toLowerCase(Locale.ROOT), startDate == null ? "" : startDate);
                    occupancy.addValue("holder", person.getId());
                    occupancy.addValue("post", position.getId());
                    occupancy.addValue("startDate", startDate);
                    occupancy.addValue("endDate", endDate);
                    occupancy.addValue("status", endDate == null ? "current" : "ended");
                    occupancy.addValue("sourceUrl", SOURCE_URL);
                    dataset.addEntity(occupancy);

                    FtmEntity directorship = dataset.newEntity("Directorship", "dir", personKey, ent.slug(),
                            role.toLowerCase(Locale.ROOT));
                    directorship.addValue("director", person.getId());
                    directorship.addValue("organization", company.getId());
                    directorship.addValue("role", role);
                    directorship.addValue("startDate", startDate);
                    directorship.addValue("endDate", endDate);
                    directorship.addValue("sourceUrl", SOURCE_URL);
                    dataset.addEntity(directorship);
                }
            }

            if (enterpriseHasLeaders) {
                dataset.addEntity(company);
                activeEnterprises.add(ent.slug());
            }
        }

        if (persons.isEmpty()) {
            throw new IllegalStateException(
                    "Aucun dirigeant ou administrateur d'entreprise publique extrait depuis " + rawDir);
        }

        boolean sfpiAvailable = Files.exists(rawDir.resolve(SFPI_FILE));
        Report report = new Report(activeEnterprises.size(), persons.size(), writtenMandates.size(), sfpiAvailable);
        log.info(report.message());
        return report;
    }

    static boolean matchesEnterprise(String instName, EnterpriseConfig ent) {
        if (instName == null || instName.isBlank()) {
            return false;
        }
        String lower = instName.toLowerCase(Locale.ROOT);
        for (String kw : ent.keywords()) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean isGovernanceRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String trimmed = role.trim();
        if (NON_GOVERNANCE_ROLE.matcher(trimmed).find()) {
            return false;
        }
        return GOVERNANCE_ROLE.matcher(trimmed).find();
    }

    static String translation(JsonNode translations) {
        if (translations == null || !translations.isArray()) {
            return "";
        }
        String fallback = "";
        for (String lang : List.of("FR", "NL", "DE", "FF")) {
            for (JsonNode t : translations) {
                if (lang.equalsIgnoreCase(t.path("languageCode").asText(""))) {
                    String name = t.path("name").asText("").trim();
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            }
        }
        for (JsonNode t : translations) {
            String name = t.path("name").asText("").trim();
            if (!name.isEmpty()) {
                fallback = name;
                break;
            }
        }
        return fallback;
    }

    private static String isoDate(String raw) {
        if (raw == null || raw.length() < 10 || raw.startsWith("0001")) {
            return null;
        }
        return raw.substring(0, 10);
    }

    static String statementFileName(String slug) {
        return "statements-" + slug + ".json";
    }

    static List<EnterpriseConfig> loadConfig() throws IOException {
        return loadConfig(null);
    }

    static List<EnterpriseConfig> loadConfig(String enterprisesParam) throws IOException {
        List<EnterpriseConfig> list = new ArrayList<>();
        JsonNode root;
        if (enterprisesParam != null && !enterprisesParam.isBlank()) {
            root = JSON.readTree(enterprisesParam);
        } else {
            try (InputStream in = BePepPublicEnterprisesParser.class.getResourceAsStream("/public-enterprises.json")) {
                if (in == null) {
                    throw new IllegalStateException("public-enterprises.json absent du plugin");
                }
                root = JSON.readTree(in);
            }
        }
        JsonNode enterprisesNode = root.has("enterprises") ? root.path("enterprises") : root;
        if (enterprisesNode.isArray()) {
            for (JsonNode node : enterprisesNode) {
                List<String> keywords = new ArrayList<>();
                node.path("keywords").forEach(k -> keywords.add(k.asText()));
                list.add(new EnterpriseConfig(
                        node.path("slug").asText(),
                        node.path("query").asText(),
                        node.path("name").asText(),
                        keywords,
                        node.path("description").asText("")));
            }
        }
        return list;
    }

    private static String fetchGuestToken(HttpClient http, String baseUrl) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/Token/guest"))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IOException("Regimand Token/guest HTTP " + res.statusCode());
        }
        return res.body().replace("\"", "").trim();
    }

    private static int fetchLatestYear(HttpClient http, String baseUrl, String token)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/TenureMandatary/Years"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            return 2024;
        }
        int max = 2024;
        for (JsonNode y : JSON.readTree(res.body())) {
            if (y.isInt() && y.asInt() > max) {
                max = y.asInt();
            }
        }
        return max;
    }
}

