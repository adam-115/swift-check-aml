package com.swiftcheck.aml.plugins.bepep.associatedentities;

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
 * <b>be_pep_associated_entities</b> – Organismes liés aux PEP belges (parlementaires et membres des gouvernements),
 * d'après les listes de mandats, fonctions et professions publiées par la Cour des comptes sur
 * {@code https://public.regimand.be} (lois du 2 mai 1995 et du 26 juin 2004).
 *
 * <p>Ce plugin couvre la catégorie <b>BE-RCA</b>, fonction <b>Proche associé d'une PEP</b> (loi du 18 septembre 2017,
 * art. 4, 30°), côté personnes morales : les sociétés, associations, intercommunales et organismes dans lesquels une
 * PEP belge exerce un mandat externe ({@code isLiable = false}) déclaré auprès de la Cour des comptes.
 *
 * <p>Entités FollowTheMoney : le déclarant ({@code Person}, {@code role.pep}), chaque organisme externe
 * ({@code Organization}, {@code role.rca}), et le lien {@code Directorship}.
 */
@Extension
public class BePepAssociatedEntitiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepAssociatedEntitiesParser.class);

    static final String ID_PREFIX = "be-pep-associated-entities";
    static final String TOPIC_PEP = "role.pep";
    static final String TOPIC_RCA = "role.rca";
    static final String DEFAULT_REGIMAND_URL = "https://public.regimand.be";
    static final String SOURCE_URL = "https://public.regimand.be";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - entites associees Regimand)";

    /** Mentions génériques d'activité personnelle sans personne morale identifiée. */
    private static final Pattern NON_ORGANIZATION = Pattern.compile(
            "^(?:néant|geen|nihil|sans objet|personne physique|natuurlijk persoon|activité indépendante|zelfstandige activiteit)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final ObjectMapper JSON = new ObjectMapper();

    record InstitutionQuery(String slug, String query, String label) {
    }

    record Report(int statementsRead, int pepsWithLinks, int organizations, int directorships) {
        String message() {
            return String.format(
                    "Regimand (Cour des comptes) : %d déclarations lues, %d organismes associés (%d mandats externes) déclarés par %d PEP belges.",
                    statementsRead, organizations, directorships, pepsWithLinks);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(p.getOrDefault("regimandUrl", DEFAULT_REGIMAND_URL).trim(), p.get("year"), ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        Report report = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque organisme associé gardé, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Organization".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Organization", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    void download(String regimandBaseUrl, String yearParam, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String token = fetchGuestToken(http, regimandBaseUrl);
        int year = (yearParam != null && !yearParam.isBlank())
                ? Integer.parseInt(yearParam.trim())
                : fetchLatestYear(http, regimandBaseUrl, token);

        for (InstitutionQuery inst : loadConfig()) {
            String body = JSON.writeValueAsString(Map.of("year", year, "institutionName", inst.query()));
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
                throw new IOException("Regimand PublicSearch HTTP " + res.statusCode() + " pour " + inst.query());
            }
            Files.write(rawDir.resolve(statementFileName(inst.slug())), res.body());
        }
    }

    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        List<InstitutionQuery> configs = loadConfig();
        Set<String> seenStatements = new LinkedHashSet<>();
        Set<String> pepsWithLinks = new LinkedHashSet<>();
        Set<String> organizations = new LinkedHashSet<>();
        Set<String> directorships = new LinkedHashSet<>();

        List<Path> jsonFiles = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(rawDir, "*.json")) {
            for (Path p : stream) {
                jsonFiles.add(p);
            }
        }

        for (Path file : jsonFiles) {
            JsonNode statements = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!statements.isArray()) {
                continue;
            }

            for (JsonNode st : statements) {
                String stId = st.path("id").asText("");
                if (!stId.isBlank() && !seenStatements.add(stId)) {
                    continue;
                }
                JsonNode profile = st.path("profile");
                String profileId = profile.path("id").asText("");
                String firstName = profile.path("firstName").asText("").trim();
                String lastName = profile.path("lastName").asText("").trim();
                String fullName = joinNonBlank(" ", firstName, lastName);
                if (fullName.isBlank()) {
                    continue;
                }

                // 1. Repérer les mandats publics assujettis (isLiable = true) du déclarant
                List<String> publicMandates = new ArrayList<>();
                for (JsonNode tenure : st.path("tenures")) {
                    if (tenure.path("isLiable").asBoolean(false)) {
                        String f = translation(tenure.path("functionType").path("translations"));
                        String i = translation(tenure.path("institution").path("translations"));
                        if (!i.isBlank()) {
                            publicMandates.add(f.isBlank() ? i : f + " – " + i);
                        }
                    }
                }
                if (publicMandates.isEmpty()) {
                    publicMandates.add("Mandataire public (Cour des comptes)");
                }

                String personKey = profileId.isBlank() ? fullName.toLowerCase(Locale.ROOT) : profileId;
                FtmEntity person = dataset.newEntity("Person", "pep", personKey);
                person.addValue("name", fullName);
                person.addValue("firstName", firstName);
                person.addValue("lastName", lastName);
                person.addValue("country", "be");
                person.addValue("topics", TOPIC_PEP);
                publicMandates.forEach(m -> person.addValue("position", m));
                person.addValue("sourceUrl", SOURCE_URL);

                int links = 0;
                // 2. Extraire tous les organismes associés (isLiable = false)
                for (JsonNode tenure : st.path("tenures")) {
                    if (tenure.path("isLiable").asBoolean(false)) {
                        continue;
                    }
                    String orgName = cleanOrgName(translation(tenure.path("institution").path("translations")));
                    if (!isValidOrganization(orgName)) {
                        continue;
                    }
                    String role = translation(tenure.path("functionType").path("translations"));
                    if (role.isBlank()) {
                        role = "Mandat ou fonction déclarée";
                    }
                    String startDate = isoDate(tenure.path("startDate").asText(null));
                    String endDate = isoDate(tenure.path("endDate").asText(null));
                    boolean remunerated = tenure.path("isRemunerated").asBoolean(false);

                    String orgKey = orgName.toLowerCase(Locale.ROOT);
                    FtmEntity org = dataset.newEntity("Organization", "org", orgKey);
                    org.addValue("name", orgName);
                    org.addValue("country", "be");
                    org.addValue("topics", TOPIC_RCA);
                    org.addValue("summary", "Déclaré par " + fullName + " (" + publicMandates.get(0)
                            + ", déclaration Cour des comptes)");
                    org.addValue("sourceUrl", SOURCE_URL);
                    dataset.addEntity(org);
                    organizations.add(org.getId());

                    String dirKey = personKey + "|" + orgKey + "|" + role.toLowerCase(Locale.ROOT);
                    if (directorships.add(dirKey)) {
                        FtmEntity link = dataset.newEntity("Directorship", personKey, orgKey,
                                role.toLowerCase(Locale.ROOT));
                        link.addValue("director", person.getId());
                        link.addValue("organization", org.getId());
                        link.addValue("role", role);
                        link.addValue("startDate", startDate);
                        link.addValue("endDate", endDate);
                        link.addValue("summary", remunerated ? "Mandat rémunéré" : "Mandat à titre gratuit");
                        link.addValue("sourceUrl", SOURCE_URL);
                        dataset.addEntity(link);
                    }
                    links++;
                }

                if (links > 0) {
                    dataset.addEntity(person);
                    pepsWithLinks.add(personKey);
                }
            }
        }

        if (organizations.isEmpty()) {
            throw new IllegalStateException("Aucun organisme associé extrait depuis " + rawDir);
        }

        Report report = new Report(seenStatements.size(), pepsWithLinks.size(), organizations.size(),
                directorships.size());
        log.info(report.message());
        return report;
    }

    static String cleanOrgName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("\\s+", " ").trim();
    }

    static boolean isValidOrganization(String orgName) {
        if (orgName == null || orgName.length() < 2) {
            return false;
        }
        if (orgName.replaceAll("[^\\p{L}]", "").length() < 2) {
            return false;
        }
        return !NON_ORGANIZATION.matcher(orgName).matches();
    }

    static String translation(JsonNode translations) {
        if (translations == null || !translations.isArray()) {
            return "";
        }
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
                return name;
            }
        }
        return "";
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

    static List<InstitutionQuery> loadConfig() throws IOException {
        List<InstitutionQuery> list = new ArrayList<>();
        try (InputStream in = BePepAssociatedEntitiesParser.class.getResourceAsStream("/pep-institutions.json")) {
            if (in == null) {
                throw new IllegalStateException("pep-institutions.json absent du plugin");
            }
            for (JsonNode node : JSON.readTree(in).path("institutions")) {
                list.add(new InstitutionQuery(
                        node.path("slug").asText(),
                        node.path("query").asText(),
                        node.path("label").asText()));
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

