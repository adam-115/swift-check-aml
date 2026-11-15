package com.swiftcheck.aml.plugins.bepep.politicalparties;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
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
import com.swiftcheck.aml.sdk.WikidataImages;

/**
 * <b>be_pep_political_parties</b> – Dirigeants et membres des organes dirigeants des partis politiques belges
 * (catégorie <b>BE-c</b>, loi du 18 septembre 2017, art. 4, 28°, et Annexe IV, point 3 : <i>membre de la direction,
 * du conseil politique, du comité de direction, de la gestion journalière ou du secrétariat d'un parti</i>).
 *
 * <p>Sources combinées (type {@code PLUGIN_MANAGED}) :
 * <ul>
 *   <li>le point SPARQL de <b>Wikidata</b> ({@link WikidataPartiesDownloader}) pour les présidents (P488), secrétaires
 *       généraux (P3975) et directeurs/dirigeants (P1037, P169) des partis politiques belges (Q31) ;</li>
 *   <li>le registre officiel des mandats de la <b>Cour des comptes</b> ({@code https://public.regimand.be}), interrogé
 *       pour chaque parti politique configuré dans le paramètre {@code parties} de {@link ParserContext#parameters()},
 *       afin d'extraire les vice-présidents, secrétaires généraux, trésoriers, directeurs politiques, administrateurs
 *       et membres des bureaux politiques, conseils de parti et comités directeurs nationaux.</li>
 * </ul>
 */
@Extension
public class BePepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "be-pep-political-parties";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String DEFAULT_COUNTRY = "Q31";
    static final String DEFAULT_REGIMAND_URL = "https://public.regimand.be";
    static final String REGIMAND_SOURCE_URL = "https://public.regimand.be";
    /** Un dirigeant sorti reste PEP pendant 12 mois (loi du 18/09/2017, art. 66, § 3). */
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    private static final String REGIMAND_USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - organes dirigeants des partis politiques)";

    /** Rôle de la requête Wikidata → libellé de la fonction. */
    private static final Map<String, String> ROLES = Map.of(
            "chair", "Président ou présidente",
            "secretaryGeneral", "Secrétaire général ou générale",
            "director", "Dirigeant ou dirigeante");

    /** Fonctions dirigeantes nationales de parti politique retenues (Annexe IV, point 3). */
    private static final Pattern PARTY_GOVERNANCE_ROLE = Pattern.compile(
            "pr[eé]sident|voorzitter|ondervoorzitter|secr[eé]taire\\s+g[eé]n[eé]ral|algemeen\\s+secretaris"
                    + "|tr[eé]sorier|penningmeester|administrateur|administratrice|bestuurder|bestuurslid"
                    + "|bureau|partijbestuur|partijbureau|partijraad|raad\\s+van\\s+bestuur"
                    + "|conseil\\s+d['’]administration|conseil\\s+politique|conseil\\s+de\\s+f[eé]d[eé]ration"
                    + "|directeur\\s+politique|politiek\\s+directeur|d[eé]l[eé]gu[eé]\\s+g[eé]n[eé]ral"
                    + "|gestion\\s+journali[eè]re|dagelijks\\s+bestuur|membre\\s+du\\s+conseil",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Mentions locales, régionales, catégorielles ou salariées explicitement écartées. */
    private static final Pattern EXCLUDED_PARTY_ROLE = Pattern.compile(
            "communal|gemeente|lokaal|local|afdeling|section|arrondissement|provinc|r[eé]gional|regio"
                    + "|wallon|bruxell|flamand|vlaams|antwerpen|deurne|gent|leuven|brugge|limburg"
                    + "|oost-vlaanderen|west-vlaanderen|vlaams-brabant|brussel|borgerhout|oostende|herstal"
                    + "|a[iî]n[eé]s|senioren|jongeren|jeunes|femmes|vrouwen"
                    + "|collaborat|attach[eé]|employ[eé]|bediende|co[oö]rdinator|militant|beroepskamer",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final String PREFERRED_RANK = "http://wikiba.se/ontology#PreferredRank";
    private static final String MALE = "Q6581097";
    private static final String FEMALE = "Q6581072";

    private static final ObjectMapper JSON = new ObjectMapper();

    record PartyConfig(String slug, String query, String name, Pattern institutionPattern) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<PartyConfig> partyConfigs = loadPartyConfigs(p.get("parties"));

        new WikidataPartiesDownloader().download(p.getOrDefault("endpoint", DEFAULT_ENDPOINT).trim(),
                p.getOrDefault("country", DEFAULT_COUNTRY).trim(), ctx.rawDir());
        downloadRegimand(p.getOrDefault("regimandUrl", DEFAULT_REGIMAND_URL).trim(),
                p.get("year"), ctx.rawDir(), partyConfigs);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now().minusMonths(monthsAfterEnd(p)), dataset, partyConfigs);
        String photos = WikidataImages.addPhotos(dataset, WikidataImages.QID, WikidataImages.personIdSuffixes(dataset),
                WikidataImages.httpFetcher(p.getOrDefault("endpoint", DEFAULT_ENDPOINT).trim(), WikidataImages.DEFAULT_USER_AGENT),
                ctx.rawDir().resolve(WikidataImages.PHOTOS_FILE));
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage((report.message() + " " + photos).trim());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<PartyConfig> partyConfigs = loadPartyConfigs(p.get("parties"));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now().minusMonths(monthsAfterEnd(p)), dataset, partyConfigs);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    record Statement(String id, String partyQid, String partyLabel, String role, boolean preferred, String start,
            String end, String personQid, String personLabel, Set<String> birthDates, String gender) {
    }

    record Selection(List<Statement> current, List<Statement> former, int superseded, int endedLongAgo) {
    }

    record Report(int parties, int persons, int current, int former, int regimandMandates, int superseded,
            int endedLongAgo, int unnamed) {

        String message() {
            String m = String.format(
                    "%d partis belges couverts (Wikidata + Cour des comptes Regimand) : %d dirigeants et membres d'organes dirigeants, "
                            + "%d mandats actifs (%d via Regimand), %d terminés depuis moins de la durée retenue. "
                            + "Écartés : %d mandats remplacés par un plus récent, %d terminés depuis plus longtemps.",
                    parties, persons, current, regimandMandates, former, superseded, endedLongAgo);
            return unnamed == 0 ? m : m + String.format(" %d dirigeants sans nom dans Wikidata écartés.", unnamed);
        }
    }

    static List<PartyConfig> loadPartyConfigs(String partiesParam) throws IOException {
        if (partiesParam == null || partiesParam.isBlank()) {
            throw new IllegalStateException(
                    "Paramètre « parties » absent : la liste des partis politiques se configure dans les paramètres du parser (colonne parser.parameters).");
        }
        List<PartyConfig> list = new ArrayList<>();
        JsonNode root = JSON.readTree(partiesParam);
        JsonNode partiesNode = root.has("parties") ? root.path("parties") : (root.has("value") ? root.path("value") : root);
        if (partiesNode.isArray()) {
            for (JsonNode node : partiesNode) {
                String slug = node.path("slug").asText("").trim();
                String query = node.path("query").asText("").trim();
                String name = node.path("name").asText(query).trim();
                String patternStr = node.path("pattern").asText("").trim();
                if (!slug.isEmpty() && !query.isEmpty() && !patternStr.isEmpty()) {
                    list.add(new PartyConfig(
                            slug,
                            query,
                            name,
                            Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
                }
            }
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun parti politique défini dans le paramètre « parties ».");
        }
        return list;
    }

    void downloadRegimand(String regimandBaseUrl, String yearParam, Path rawDir, List<PartyConfig> partyConfigs)
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

        for (PartyConfig party : partyConfigs) {
            String body = JSON.writeValueAsString(Map.of("year", year, "institutionName", party.query()));
            HttpRequest req = HttpRequest.newBuilder(URI.create(regimandBaseUrl + "/api/Statement/PublicSearch"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .header("User-Agent", REGIMAND_USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                throw new IOException("Regimand PublicSearch HTTP " + res.statusCode() + " pour " + party.query());
            }
            Files.write(rawDir.resolve(regimandFileName(party.slug())), res.body());
        }
    }

    Report convert(Path rawDir, LocalDate since, FtmDatasetBuilder dataset, List<PartyConfig> partyConfigs) throws Exception {
        JsonNode response = JSON.readTree(Files.readString(rawDir.resolve(WikidataPartiesDownloader.LEADERS_FILE), StandardCharsets.UTF_8));
        List<Statement> statements = new ArrayList<>();
        int unnamed = 0;
        for (Statement s : statements(response)) {
            if (s.personLabel().isBlank() || s.personLabel().equals(s.personQid())) {
                unnamed++;
            } else {
                statements.add(s);
            }
        }
        Selection selection = select(statements, since);
        if (selection.current().isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant de parti en fonction dans la réponse de Wikidata : "
                    + "réponse vide ou format changé.");
        }

        Set<String> parties = new LinkedHashSet<>();
        Set<String> persons = new LinkedHashSet<>();
        Map<String, FtmEntity> personsByFoldedName = new LinkedHashMap<>();

        for (Statement s : selection.current()) {
            FtmEntity person = writeWikidata(dataset, s, true);
            parties.add(s.partyQid());
            persons.add(person.getId());
            personsByFoldedName.putIfAbsent(foldName(s.personLabel()), person);
        }
        for (Statement s : selection.former()) {
            FtmEntity person = writeWikidata(dataset, s, false);
            parties.add(s.partyQid());
            persons.add(person.getId());
            personsByFoldedName.putIfAbsent(foldName(s.personLabel()), person);
        }

        int regimandMandates = loadRegimandPartyExecutives(
                rawDir, since, dataset, partyConfigs, parties, persons, personsByFoldedName);

        Report report = new Report(parties.size(), persons.size(), selection.current().size() + regimandMandates,
                selection.former().size(), regimandMandates, selection.superseded(), selection.endedLongAgo(), unnamed);
        log.info(report.message());
        return report;
    }

    private int loadRegimandPartyExecutives(
            Path rawDir,
            LocalDate since,
            FtmDatasetBuilder dataset,
            List<PartyConfig> partyConfigs,
            Set<String> parties,
            Set<String> persons,
            Map<String, FtmEntity> personsByFoldedName) throws IOException {
        Set<String> writtenMandates = new LinkedHashSet<>();
        String sinceText = since.toString();
        int addedMandates = 0;

        for (PartyConfig party : partyConfigs) {
            Path file = rawDir.resolve(regimandFileName(party.slug()));
            if (!Files.exists(file)) {
                continue;
            }
            JsonNode statements = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!statements.isArray()) {
                continue;
            }

            boolean partyHasMandates = false;
            for (JsonNode st : statements) {
                JsonNode profile = st.path("profile");
                String profileId = profile.path("id").asText("").trim();
                String firstName = profile.path("firstName").asText("").trim();
                String lastName = profile.path("lastName").asText("").trim();
                String fullName = joinNonBlank(" ", firstName, lastName);
                if (fullName.isBlank()) {
                    continue;
                }

                for (JsonNode tenure : st.path("tenures")) {
                    String instName = translation(tenure.path("institution").path("translations"));
                    if (!matchesPartyInstitution(instName, party)) {
                        continue;
                    }
                    String role = translation(tenure.path("functionType").path("translations"));
                    if (!isPartyGovernanceRole(role)) {
                        continue;
                    }
                    String startDate = isoDate(tenure.path("startDate").asText(null));
                    String endDate = isoDate(tenure.path("endDate").asText(null));

                    String foldedName = foldName(fullName);
                    String roleSlug = normaliseSlug(role);
                    String canonicalPartySlug = "pvda".equals(party.slug()) ? "ptb" : party.slug();
                    String mandateKey = foldedName + "|" + canonicalPartySlug + "|" + roleSlug;
                    if (!writtenMandates.add(mandateKey)) {
                        continue;
                    }

                    partyHasMandates = true;
                    String title = role + " – " + party.name();

                    FtmEntity person = personsByFoldedName.get(foldedName);
                    if (person == null) {
                        String personKey = profileId.isBlank() ? foldedName : profileId;
                        person = dataset.newEntity("Person", "regimand", personKey);
                        person.addValue("name", fullName);
                        person.addValue("firstName", firstName);
                        person.addValue("lastName", lastName);
                        person.addValue("country", "be");
                        person.addValue("political", party.name());
                        person.addValue("position", title);
                        person.addValue("topics", TOPIC);
                        person.addValue("sourceUrl", REGIMAND_SOURCE_URL);
                        dataset.addEntity(person);
                        personsByFoldedName.put(foldedName, person);
                    } else {
                        person.addValue("political", party.name());
                        person.addValue("position", title);
                        person.addValue("sourceUrl", REGIMAND_SOURCE_URL);
                        dataset.addEntity(person);
                    }
                    persons.add(person.getId());

                    FtmEntity position = dataset.newEntity("Position", "regimand-pos", canonicalPartySlug, roleSlug);
                    position.addValue("name", title);
                    position.addValue("organization", party.name());
                    position.addValue("country", "be");
                    position.addValue("topics", "gov.national");
                    position.addValue("sourceUrl", REGIMAND_SOURCE_URL);
                    dataset.addEntity(position);

                    FtmEntity occupancy = dataset.newEntity("Occupancy", "regimand-occ", foldedName,
                            canonicalPartySlug, roleSlug, startDate == null ? "" : startDate);
                    occupancy.addValue("holder", person.getId());
                    occupancy.addValue("post", position.getId());
                    occupancy.addValue("startDate", startDate);
                    occupancy.addValue("endDate", endDate);
                    occupancy.addValue("status", endDate == null ? "current" : "ended");
                    occupancy.addValue("sourceUrl", REGIMAND_SOURCE_URL);
                    dataset.addEntity(occupancy);

                    addedMandates++;
                }
            }

            if (partyHasMandates) {
                parties.add("regimand-" + ("pvda".equals(party.slug()) ? "ptb" : party.slug()));
            }
        }

        return addedMandates;
    }

    static boolean matchesPartyInstitution(String instName, PartyConfig party) {
        if (instName == null || instName.isBlank()) {
            return false;
        }
        return party.institutionPattern().matcher(instName.trim()).matches();
    }

    static boolean isPartyGovernanceRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String trimmed = role.trim();
        if (EXCLUDED_PARTY_ROLE.matcher(trimmed).find()) {
            return false;
        }
        return PARTY_GOVERNANCE_ROLE.matcher(trimmed).find();
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

    static String regimandFileName(String slug) {
        return "regimand-" + slug + ".json";
    }

    private static String foldName(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static String normaliseSlug(String text) {
        return foldName(text);
    }

    static List<Statement> statements(JsonNode response) {
        Map<String, Statement> byId = new LinkedHashMap<>();
        for (JsonNode b : response.path("results").path("bindings")) {
            String id = text(b, "statement");
            Statement s = byId.get(id);
            if (s == null) {
                s = new Statement(id, qid(text(b, "party")), text(b, "partyLabel"), text(b, "role"),
                        PREFERRED_RANK.equals(text(b, "rank")), date(text(b, "start")), date(text(b, "end")),
                        qid(text(b, "person")), text(b, "personLabel"), new LinkedHashSet<>(),
                        qid(text(b, "gender")));
                byId.put(id, s);
            }
            String birth = birthDate(text(b, "birth"), text(b, "birthPrecision"));
            if (birth != null) {
                s.birthDates().add(birth);
            }
        }
        return new ArrayList<>(byId.values());
    }

    static Selection select(List<Statement> statements, LocalDate since) {
        Map<String, List<Statement>> groups = new LinkedHashMap<>();
        for (Statement s : statements) {
            groups.computeIfAbsent(s.partyQid() + "|" + s.role(), k -> new ArrayList<>()).add(s);
        }
        List<Statement> current = new ArrayList<>();
        List<Statement> former = new ArrayList<>();
        int superseded = 0;
        int endedLongAgo = 0;
        String sinceText = since.toString();
        for (List<Statement> group : groups.values()) {
            boolean hasPreferred = group.stream().anyMatch(Statement::preferred);
            for (Statement s : group) {
                if (s.end() != null) {
                    if (s.end().compareTo(sinceText) >= 0) {
                        former.add(s);
                    } else {
                        endedLongAgo++;
                    }
                } else if (hasPreferred ? !s.preferred() : startedBefore(s, group)) {
                    superseded++;
                } else {
                    current.add(s);
                }
            }
        }
        return new Selection(current, former, superseded, endedLongAgo);
    }

    private static boolean startedBefore(Statement s, List<Statement> group) {
        return s.start() != null && group.stream()
                .anyMatch(o -> o != s && o.start() != null && o.start().compareTo(s.start()) > 0);
    }

    private static FtmEntity writeWikidata(FtmDatasetBuilder dataset, Statement s, boolean isCurrent) {
        String function = ROLES.getOrDefault(s.role(), "Dirigeant ou dirigeante") + " – " + s.partyLabel();

        FtmEntity person = dataset.newEntity("Person", s.personQid());
        person.addValue("name", s.personLabel());
        person.addValue("wikidataId", s.personQid());
        s.birthDates().forEach(d -> person.addValue("birthDate", d));
        if (MALE.equals(s.gender())) {
            person.addValue("gender", "male");
        } else if (FEMALE.equals(s.gender())) {
            person.addValue("gender", "female");
        }
        person.addValue("country", "be");
        person.addValue("political", s.partyLabel());
        person.addValue("position", isCurrent ? function : "Ancien : " + function);
        person.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.personQid());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", s.partyQid(), s.role());
        position.addValue("name", function);
        position.addValue("country", "be");
        position.addValue("organization", s.partyLabel());
        position.addValue("wikidataId", s.partyQid());
        position.addValue("topics", "gov.national");
        position.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.partyQid());
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", s.personQid(), s.partyQid(), s.role(),
                s.start() == null ? "" : s.start());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", s.start());
        occupancy.addValue("endDate", s.end());
        occupancy.addValue("status", isCurrent ? "current" : "ended");
        occupancy.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.partyQid());
        dataset.addEntity(occupancy);
        return person;
    }

    static String birthDate(String value, String precision) {
        if (value == null || value.length() < 10 || value.startsWith("-")) {
            return null;
        }
        int p;
        try {
            p = Integer.parseInt(precision);
        } catch (NumberFormatException e) {
            p = 11;
        }
        return p >= 11 ? value.substring(0, 10) : p == 10 ? value.substring(0, 7) : p == 9 ? value.substring(0, 4) : null;
    }

    private static String isoDate(String raw) {
        if (raw == null || raw.length() < 10 || raw.startsWith("0001")) {
            return null;
        }
        return raw.substring(0, 10);
    }

    private static String date(String value) {
        return value.length() < 10 ? null : value.substring(0, 10);
    }

    private static String text(JsonNode binding, String column) {
        return binding.path(column).path("value").asText("");
    }

    private static String qid(String uri) {
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    static int monthsAfterEnd(Map<String, String> parameters) {
        try {
            return Integer.parseInt(parameters.getOrDefault("monthsAfterEnd", "").trim());
        } catch (NumberFormatException e) {
            return DEFAULT_MONTHS_AFTER_END;
        }
    }

    private static String fetchGuestToken(HttpClient http, String baseUrl) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/Token/guest"))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", REGIMAND_USER_AGENT)
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
                .header("User-Agent", REGIMAND_USER_AGENT)
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
