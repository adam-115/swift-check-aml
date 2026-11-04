package com.swiftcheck.aml.plugins.bepep.localexecutives;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
 * <b>be_pep_local_executives</b> – Bourgmestres et gouverneurs de province des collectivités belges d'au moins
 * 50 000 habitants (catégorie <b>BE-AMLR</b> ; règlement (UE) 2024/1624, applicable le 10 juillet 2027).
 *
 * <p>Sources combinées (type {@code PLUGIN_MANAGED}) :
 * <ul>
 *   <li><b>Wikidata SPARQL</b> ({@code wikidata-local.json}) : communes belges ({@code Q493522}) et provinces
 *       ({@code Q83116}) d'au moins {@code minPopulation} habitants ({@code P1082}) et leur chef de l'exécutif
 *       ({@code P6} : bourgmestre ou gouverneur de province) en Wallonie, à Bruxelles et en Flandre ;</li>
 *   <li><b>LBLOD SPARQL</b> ({@code lblod-local.json}, Agentschap Binnenlands Bestuur Vlaanderen) : mandats officiels
 *       de {@code Burgemeester}, {@code Aangewezen burgemeester} et {@code Gouverneur} en Flandre, filtrés sur les
 *       collectivités d'au moins {@code minPopulation} habitants.</li>
 * </ul>
 */
@Extension
public class BePepLocalExecutivesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepLocalExecutivesParser.class);

    static final String ID_PREFIX = "be-pep-local-executives";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_WIKIDATA_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String DEFAULT_LBLOD_ENDPOINT = "https://centrale-vindplaats.lblod.info/sparql";
    static final long DEFAULT_MIN_POPULATION = 50_000L;
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    static final String WIKIDATA_FILE = "wikidata-local.json";
    static final String LBLOD_FILE = "lblod-local.json";
    static final String REGIMAND_PREFIX = "regimand-";
    static final String REGIMAND_FAILURES = "regimand-echecs.txt";
    static final long DEFAULT_REGIMAND_DELAY_MS = 500L;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - executifs locaux)";
    private static final String PREFERRED_RANK = "http://wikiba.se/ontology#PreferredRank";
    private static final String MALE = "Q6581097";
    private static final String FEMALE = "Q6581072";

    private final ObjectMapper json = new ObjectMapper();

    record Area(String qid, String labelFr, String labelNl, String kind, long population) {
        String functionTitle() {
            return ("province".equals(kind) ? "Gouverneur" : "Bourgmestre") + " – " + labelFr;
        }

        String topic() {
            return "province".equals(kind) ? "gov.state" : "gov.muni";
        }
    }

    record WikiStatement(String id, Area area, boolean preferred, String start, String end,
            String personQid, String personLabel, Set<String> birthDates, String gender) {
    }

    record Report(int communes, int provinces, int mayors, int governors, int lblodAdded, long minPopulation,
            int regimandMandates, List<String> regimandFailures) {

        Report(int communes, int provinces, int mayors, int governors, int lblodAdded, long minPopulation) {
            this(communes, provinces, mayors, governors, lblodAdded, minPopulation, 0, List.of());
        }

        String message() {
            String m = String.format(
                    "Collectivités belges de %d habitants ou plus (%d communes, %d provinces) : %d bourgmestres et %d gouverneurs (dont %d complétés via LBLOD).",
                    minPopulation, communes, provinces, mayors, governors, lblodAdded);
            if (regimandMandates > 0) {
                m += String.format(" %d échevins, présidents de CPAS et députés provinciaux (Cour des comptes, Regimand).", regimandMandates);
            }
            if (!regimandFailures.isEmpty()) {
                m += " Regimand non lu pour : " + String.join(", ", regimandFailures) + ".";
            }
            return m;
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        long minPop = minPopulation(p);
        download(p.getOrDefault("wikidataEndpoint", DEFAULT_WIKIDATA_ENDPOINT).trim(),
                p.getOrDefault("lblodEndpoint", DEFAULT_LBLOD_ENDPOINT).trim(),
                minPop, ctx.rawDir());
        String regimandUrl = p.getOrDefault("regimandUrl", RegimandLocal.DEFAULT_URL).trim();
        if (!regimandUrl.isBlank()) {
            downloadRegimand(RegimandLocal.httpClient(regimandUrl), minPop,
                    Long.parseLong(p.getOrDefault("regimandDelayMs", String.valueOf(DEFAULT_REGIMAND_DELAY_MS)).trim()),
                    ctx.rawDir());
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), minPop, LocalDate.now().minusMonths(monthsAfterEnd(p)), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), minPopulation(p), LocalDate.now().minusMonths(monthsAfterEnd(p)), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    void download(String wikidataEndpoint, String lblodEndpoint, long minPopulation, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String wikiJson = postSparql(http, wikidataEndpoint, wikidataQuery(minPopulation));
        Files.writeString(rawDir.resolve(WIKIDATA_FILE), wikiJson, StandardCharsets.UTF_8);

        if (lblodEndpoint != null && !lblodEndpoint.isBlank()) {
            try {
                String lblodJson = postSparql(http, lblodEndpoint, lblodQuery());
                Files.writeString(rawDir.resolve(LBLOD_FILE), lblodJson, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("LBLOD SPARQL indisponible ({}) : {}", lblodEndpoint, e.getMessage());
            }
        }
    }

    /**
     * Interroge Regimand pour chaque collectivité de la réponse Wikidata, par son nom français puis néerlandais.
     * Une collectivité en échec est notée dans {@code regimand-echecs.txt}, l'exécution continue ; si toutes échouent, elle s'arrête.
     */
    void downloadRegimand(RegimandLocal.Client client, long minPopulation, long delayMs, Path rawDir)
            throws IOException, InterruptedException {
        Path wiki = rawDir.resolve(WIKIDATA_FILE);
        Map<String, Area> areas = areas(json.readTree(Files.readString(wiki, StandardCharsets.UTF_8)), minPopulation);
        List<String> failures = new ArrayList<>();
        for (Area area : areas.values()) {
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            try {
                for (String label : new LinkedHashSet<>(List.of(area.labelFr(), area.labelNl()))) {
                    for (JsonNode statement : json.readTree(client.search(label))) {
                        byId.putIfAbsent(statement.path("id").asText(), statement);
                    }
                    Thread.sleep(delayMs);
                }
                Files.writeString(rawDir.resolve(REGIMAND_PREFIX + area.qid() + ".json"),
                        json.writeValueAsString(byId.values()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                failures.add(area.labelFr() + " (" + e.getMessage() + ")");
            }
        }
        if (!areas.isEmpty() && failures.size() == areas.size()) {
            throw new IOException("Regimand illisible pour toutes les collectivités : " + failures.get(0));
        }
        Files.writeString(rawDir.resolve(REGIMAND_FAILURES), String.join("\n", failures), StandardCharsets.UTF_8);
    }

    /** Collectivités de la réponse Wikidata d'au moins {@code minPopulation} habitants. */
    Map<String, Area> areas(JsonNode wikiRoot, long minPopulation) {
        Map<String, Area> areas = new LinkedHashMap<>();
        for (JsonNode b : wikiRoot.path("results").path("bindings")) {
            long pop = text(b, "population").isEmpty() ? 0 : Long.parseLong(text(b, "population"));
            if (pop < minPopulation) {
                continue;
            }
            String qid = qid(text(b, "area"));
            String fr = text(b, "areaLabel");
            String nl = text(b, "areaLabelNl");
            areas.computeIfAbsent(qid, k -> new Area(qid, fr, nl.isBlank() ? fr : nl, text(b, "kind"), pop));
        }
        return areas;
    }

    Report convert(Path rawDir, long minPopulation, LocalDate since, FtmDatasetBuilder dataset) throws Exception {
        Path wikiPath = rawDir.resolve(WIKIDATA_FILE);
        if (!Files.exists(wikiPath)) {
            throw new IllegalStateException(WIKIDATA_FILE + " absent de " + rawDir);
        }
        JsonNode wikiRoot = json.readTree(Files.readString(wikiPath, StandardCharsets.UTF_8));
        Map<String, Area> areasByQid = new LinkedHashMap<>();
        Map<String, Area> areasByNormalizedName = new LinkedHashMap<>();
        Map<String, WikiStatement> statementsById = new LinkedHashMap<>();

        for (JsonNode b : wikiRoot.path("results").path("bindings")) {
            long pop = Long.parseLong(text(b, "population").isEmpty() ? "0" : text(b, "population"));
            if (pop < minPopulation) {
                continue;
            }
            String areaQid = qid(text(b, "area"));
            String labelFr = text(b, "areaLabel");
            String labelNl = text(b, "areaLabelNl");
            String kind = text(b, "kind");
            Area area = areasByQid.computeIfAbsent(areaQid,
                    k -> new Area(areaQid, labelFr, labelNl.isBlank() ? labelFr : labelNl, kind, pop));
            areasByNormalizedName.put(fold(area.labelFr()), area);
            if (!area.labelNl().isBlank()) {
                areasByNormalizedName.put(fold(area.labelNl()), area);
            }

            String stmtId = text(b, "statement");
            String personUri = text(b, "person");
            String personLabel = text(b, "personLabel");
            if (!stmtId.isBlank() && !personUri.isBlank() && !personLabel.isBlank()) {
                String personQid = qid(personUri);
                if (!personLabel.equals(personQid)) {
                    WikiStatement s = statementsById.computeIfAbsent(stmtId,
                            k -> new WikiStatement(stmtId, area, PREFERRED_RANK.equals(text(b, "rank")),
                                    date(text(b, "start")), date(text(b, "end")), personQid, personLabel,
                                    new LinkedHashSet<>(), qid(text(b, "gender"))));
                    String birth = birthDate(text(b, "birth"), text(b, "birthPrecision"));
                    if (birth != null) {
                        s.birthDates().add(birth);
                    }
                }
            }
        }

        // Sélection par collectivité des mandats actuels et récemment terminés
        Map<String, List<WikiStatement>> byArea = new LinkedHashMap<>();
        for (WikiStatement s : statementsById.values()) {
            byArea.computeIfAbsent(s.area().qid(), k -> new ArrayList<>()).add(s);
        }

        Set<String> recordedAreaPersonKeys = new LinkedHashSet<>();
        int mayors = 0;
        int governors = 0;
        String sinceText = since.toString();

        for (List<WikiStatement> group : byArea.values()) {
            boolean hasPreferred = group.stream().anyMatch(WikiStatement::preferred);
            for (WikiStatement s : group) {
                boolean isCurrent;
                if (s.end() != null) {
                    if (s.end().compareTo(sinceText) >= 0) {
                        isCurrent = false;
                    } else {
                        continue;
                    }
                } else if (hasPreferred ? !s.preferred() : startedBefore(s, group)) {
                    continue;
                } else {
                    isCurrent = true;
                }
                writeWiki(dataset, s, isCurrent);
                recordedAreaPersonKeys.add(s.area().qid() + "|" + fold(s.personLabel()));
                if ("province".equals(s.area().kind())) {
                    governors++;
                } else {
                    mayors++;
                }
            }
        }

        int lblodAdded = 0;
        Path lblodPath = rawDir.resolve(LBLOD_FILE);
        if (Files.exists(lblodPath)) {
            JsonNode lblodRoot = json.readTree(Files.readString(lblodPath, StandardCharsets.UTF_8));
            for (JsonNode b : lblodRoot.path("results").path("bindings")) {
                String unitName = text(b, "unitName");
                String roleLabel = text(b, "roleLabel");
                String firstName = text(b, "firstName").trim();
                String familyName = text(b, "familyName").trim();
                String fullName = joinNonBlank(" ", firstName, familyName);
                String personUri = text(b, "person");
                String start = date(text(b, "start"));
                if (unitName.isBlank() || fullName.isBlank()) {
                    continue;
                }
                Area area = matchLblodArea(unitName, roleLabel, areasByNormalizedName);
                if (area == null) {
                    continue;
                }
                String key = area.qid() + "|" + fold(fullName);
                if (recordedAreaPersonKeys.add(key)) {
                    writeLblod(dataset, area, firstName, familyName, fullName, start, personUri);
                    lblodAdded++;
                    if ("province".equals(area.kind())) {
                        governors++;
                    } else {
                        mayors++;
                    }
                }
            }
        }

        int regimandMandates = 0;
        for (Area area : areasByQid.values()) {
            Path file = rawDir.resolve(REGIMAND_PREFIX + area.qid() + ".json");
            if (!Files.exists(file)) {
                continue;
            }
            for (RegimandLocal.Mandate m : RegimandLocal.select(json.readTree(Files.readString(file, StandardCharsets.UTF_8)),
                    "province".equals(area.kind()), List.of(area.labelFr(), area.labelNl()), since)) {
                writeRegimand(dataset, area, m, since);
                regimandMandates++;
            }
        }
        List<String> regimandFailures = new ArrayList<>();
        Path failuresFile = rawDir.resolve(REGIMAND_FAILURES);
        if (Files.exists(failuresFile)) {
            for (String line : Files.readAllLines(failuresFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    regimandFailures.add(line.trim());
                }
            }
        }
        if (mayors == 0) {
            throw new IllegalStateException("Aucun bourgmestre d'une commune de " + minPopulation
                    + " habitants ou plus extrait : réponse vide ou format changé.");
        }

        int communes = (int) areasByQid.values().stream().filter(a -> "commune".equals(a.kind())).count();
        int provinces = (int) areasByQid.values().stream().filter(a -> "province".equals(a.kind())).count();
        Report report = new Report(communes, provinces, mayors, governors, lblodAdded, minPopulation,
                regimandMandates, regimandFailures);
        log.info(report.message());
        return report;
    }

    private static Area matchLblodArea(String unitName, String roleLabel, Map<String, Area> byName) {
        String cleaned = unitName.replaceFirst("(?i)^(gemeente|stad|provincie|district)\\s+", "").trim();
        Area area = byName.get(fold(cleaned));
        if (area == null) {
            return null;
        }
        boolean isGovRole = roleLabel != null && roleLabel.toLowerCase(Locale.ROOT).contains("gouverneur");
        if (isGovRole && !"province".equals(area.kind())) {
            // Ex. « Antwerpen » (Gouverneur de la province d'Anvers vs Bourgmestre de la ville d'Anvers)
            for (Area candidate : byName.values()) {
                if ("province".equals(candidate.kind())
                        && (fold(candidate.labelNl()).contains(fold(cleaned))
                                || fold(candidate.labelFr()).contains(fold(cleaned)))) {
                    return candidate;
                }
            }
        }
        return area;
    }

    private static boolean startedBefore(WikiStatement s, List<WikiStatement> group) {
        return s.start() != null && group.stream()
                .anyMatch(o -> o != s && o.start() != null && o.start().compareTo(s.start()) > 0);
    }

    private static void writeWiki(FtmDatasetBuilder dataset, WikiStatement s, boolean isCurrent) {
        Area area = s.area();
        String title = area.functionTitle();

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
        person.addValue("position", isCurrent ? title : "Ancien : " + title);
        person.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.personQid());
        dataset.addEntity(person);

        FtmEntity position = writePosition(dataset, area);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ", s.personQid(), area.qid(),
                s.start() == null ? "" : s.start());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", s.start());
        occupancy.addValue("endDate", s.end());
        occupancy.addValue("status", isCurrent ? "current" : "ended");
        occupancy.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + area.qid());
        dataset.addEntity(occupancy);
    }

    private static void writeLblod(FtmDatasetBuilder dataset, Area area, String firstName, String familyName,
            String fullName, String start, String personUri) {
        String title = area.functionTitle();
        FtmEntity person = dataset.newEntity("Person", "lblod-person", area.qid(), fold(fullName));
        person.addValue("name", fullName);
        person.addValue("firstName", firstName);
        person.addValue("lastName", familyName);
        person.addValue("country", "be");
        person.addValue("position", title);
        person.addValue("sourceUrl", personUri.isBlank() ? "https://centrale-vindplaats.lblod.info" : personUri);
        dataset.addEntity(person);

        FtmEntity position = writePosition(dataset, area);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "lblod-occ", area.qid(), fold(fullName),
                start == null ? "" : start);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", start);
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", personUri.isBlank() ? "https://centrale-vindplaats.lblod.info" : personUri);
        dataset.addEntity(occupancy);
    }

    /** Échevin, président de CPAS ou député provincial déclaré à la Cour des comptes. */
    private static void writeRegimand(FtmDatasetBuilder dataset, Area area, RegimandLocal.Mandate m, LocalDate since) {
        boolean current = m.end() == null;
        String title = m.role() + " – " + area.labelFr();
        FtmEntity person = dataset.newEntity("Person", "regimand", m.profileId());
        person.addValue("name", m.fullName());
        person.addValue("firstName", m.firstName());
        person.addValue("lastName", m.lastName());
        person.addValue("country", "be");
        person.addValue("position", current ? title : "Ancien : " + title);
        person.addValue("sourceUrl", RegimandLocal.SOURCE_URL);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "regimand", area.qid(), RegimandLocal.fold(m.role()));
        position.addValue("name", title);
        position.addValue("country", "be");
        position.addValue("subnationalArea", area.labelFr());
        position.addValue("description", "Collectivité belge de " + area.population() + " habitants");
        position.addValue("topics", area.topic());
        position.addValue("topics", "gov.executive");
        position.addValue("sourceUrl", RegimandLocal.SOURCE_URL);
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "regimand", m.profileId(), area.qid(),
                RegimandLocal.fold(m.role()), m.start() == null ? "" : m.start());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", m.start());
        occupancy.addValue("endDate", m.end());
        occupancy.addValue("status", current ? "current" : "ended");
        occupancy.addValue("sourceUrl", RegimandLocal.SOURCE_URL);
        dataset.addEntity(occupancy);
    }

    private static FtmEntity writePosition(FtmDatasetBuilder dataset, Area area) {
        FtmEntity position = dataset.newEntity("Position", "pos", area.qid(), area.kind());
        position.addValue("name", area.functionTitle());
        position.addValue("country", "be");
        position.addValue("subnationalArea", area.labelFr());
        position.addValue("wikidataId", area.qid());
        position.addValue("description", "Collectivité belge de " + area.population() + " habitants");
        position.addValue("topics", area.topic());
        position.addValue("topics", "gov.executive");
        position.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + area.qid());
        dataset.addEntity(position);
        return position;
    }

    static String wikidataQuery(long minPopulation) {
        return """
                SELECT ?area ?areaLabel ?areaLabelNl ?kind ?population ?statement ?rank ?start ?end ?person ?personLabel ?birth ?birthPrecision ?gender WHERE {
                  {
                    SELECT DISTINCT ?area ?kind (MAX(?pop) AS ?population) WHERE {
                      VALUES (?type ?kind) { (wd:Q493522 "commune") (wd:Q83116 "province") }
                      ?area wdt:P31/wdt:P279* ?type ; wdt:P17 wd:Q31 ; wdt:P1082 ?pop .
                      FILTER NOT EXISTS { ?area wdt:P576 ?dissolved }
                      FILTER(?pop >= %d)
                    } GROUP BY ?area ?kind
                  }
                  OPTIONAL { ?area rdfs:label ?areaLabelNl . FILTER(LANG(?areaLabelNl) = "nl") }
                  OPTIONAL {
                    ?area p:P6 ?statement .
                    ?statement ps:P6 ?person ; wikibase:rank ?rank .
                    FILTER(?rank != wikibase:DeprecatedRank)
                    ?person wdt:P31 wd:Q5 .
                    FILTER NOT EXISTS { ?person wdt:P570 ?death }
                    OPTIONAL { ?statement pq:P580 ?start }
                    OPTIONAL { ?statement pq:P582 ?end }
                    OPTIONAL { ?person p:P569/psv:P569 ?birthValue .
                               ?birthValue wikibase:timeValue ?birth ; wikibase:timePrecision ?birthPrecision . }
                    OPTIONAL { ?person wdt:P21 ?gender }
                  }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,nl,de,mul,en".
                    ?area rdfs:label ?areaLabel . ?person rdfs:label ?personLabel . }
                }
                """.formatted(minPopulation);
    }

    static String lblodQuery() {
        return """
                PREFIX mandaat: <http://data.vlaanderen.be/ns/mandaat#>
                PREFIX org: <http://www.w3.org/ns/org#>
                PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                PREFIX persoon: <http://data.vlaanderen.be/ns/persoon#>
                PREFIX besluit: <http://data.vlaanderen.be/ns/besluit#>

                SELECT DISTINCT ?person ?firstName ?familyName ?roleLabel ?unitName ?start ?end WHERE {
                  VALUES ?roleCode {
                    <http://data.vlaanderen.be/id/concept/BestuursfunctieCode/5ab0e9b8a3b2ca7c5e000013>
                    <http://data.vlaanderen.be/id/concept/BestuursfunctieCode/7b038cc40bba10bec833ecfe6f15bc7a>
                    <http://data.vlaanderen.be/id/concept/BestuursfunctieCode/d7c00cd1-baf1-4346-83c0-6796c0bedd85>
                  }
                  ?roleCode skos:prefLabel ?roleLabel .
                  ?mandate org:role ?roleCode ;
                           ^org:holds ?mandataris ;
                           ^org:hasPost ?orgInTime .
                  ?orgInTime mandaat:isTijdspecialisatieVan ?org .
                  ?org besluit:bestuurt ?unit .
                  ?unit skos:prefLabel ?unitName .
                  ?mandataris mandaat:isBestuurlijkeAliasVan ?person ;
                              mandaat:start ?start .
                  OPTIONAL { ?mandataris mandaat:einde ?end }
                  FILTER(!BOUND(?end) || ?end > NOW())
                  ?person persoon:gebruikteVoornaam ?firstName ;
                          foaf:familyName ?familyName .
                }
                """;
    }

    private static String postSparql(HttpClient http, String endpoint, String query)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(90))
                .header("Accept", "application/sparql-results+json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString("query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("SPARQL HTTP " + response.statusCode() + " (" + endpoint + ")");
        }
        return response.body();
    }

    static String fold(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static String birthDate(String value, String precision) {
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

    private static String date(String value) {
        return value == null || value.length() < 10 ? null : value.substring(0, 10);
    }

    private static String text(JsonNode binding, String column) {
        return binding.path(column).path("value").asText("");
    }

    private static String qid(String uri) {
        return uri == null || uri.isBlank() ? "" : uri.substring(uri.lastIndexOf('/') + 1);
    }

    static long minPopulation(Map<String, String> p) {
        String value = p.get("minPopulation");
        if (value == null || value.isBlank()) {
            return DEFAULT_MIN_POPULATION;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « minPopulation » invalide : « " + value + " »");
        }
    }

    static int monthsAfterEnd(Map<String, String> p) {
        try {
            return Integer.parseInt(p.getOrDefault("monthsAfterEnd", "").trim());
        } catch (NumberFormatException e) {
            return DEFAULT_MONTHS_AFTER_END;
        }
    }
}

