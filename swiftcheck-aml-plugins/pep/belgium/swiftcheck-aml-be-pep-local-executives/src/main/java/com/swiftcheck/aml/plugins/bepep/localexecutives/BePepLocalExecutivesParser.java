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

    /**
     * Les 40 collectivités belges de 50 000 habitants ou plus selon les chiffres officiels de population Statbel
     * (Arrêté royal annuel) : 10 provinces et 30 communes.
     */
    static final List<Area> OFFICIAL_STATBEL_AREAS = List.of(
            // 10 Provinces
            new Area("Q1116", "Anvers", "Antwerpen", "province", 1_921_189),
            new Area("Q1118", "Brabant flamand", "Vlaams-Brabant", "province", 1_204_541),
            new Area("Q1122", "Brabant wallon", "Waals-Brabant", "province", 415_381),
            new Area("Q1113", "Flandre-Occidentale", "West-Vlaanderen", "province", 1_231_585),
            new Area("Q1114", "Flandre-Orientale", "Oost-Vlaanderen", "province", 1_602_532),
            new Area("Q1129", "Hainaut", "Henegouwen", "province", 1_365_328),
            new Area("Q1127", "Liège", "Luik", "province", 1_122_925),
            new Area("Q1095", "Limbourg", "Limburg", "province", 904_919),
            new Area("Q1126", "Luxembourg", "Luxemburg", "province", 296_008),
            new Area("Q1125", "Namur", "Namen", "province", 505_348),
            // 30 Communes >= 50 000 hab.
            new Area("Q13121", "Alost", "Aalst", "commune", 88_854),
            new Area("Q12886", "Anderlecht", "Anderlecht", "commune", 120_887),
            new Area("Q12892", "Anvers", "Antwerpen", "commune", 529_247),
            new Area("Q12994", "Bruges", "Brugge", "commune", 118_509),
            new Area("Q239", "Bruxelles", "Brussel", "commune", 195_546),
            new Area("Q81046", "Charleroi", "Charleroi", "commune", 204_322),
            new Area("Q12995", "Courtrai", "Kortrijk", "commune", 77_741),
            new Area("Q72946", "Forest", "Vorst", "commune", 56_581),
            new Area("Q1296", "Gand", "Gent", "commune", 272_657),
            new Area("Q189692", "Genk", "Genk", "commune", 66_110),
            new Area("Q58780", "Hasselt", "Hasselt", "commune", 90_249),
            new Area("Q208713", "Ixelles", "Elsene", "commune", 87_632),
            new Area("Q241918", "Jette", "Jette", "commune", 52_728),
            new Area("Q211572", "La Louvière", "La Louvière", "commune", 80_986),
            new Area("Q3992", "Liège", "Luik", "commune", 195_278),
            new Area("Q118958", "Louvain", "Leuven", "commune", 101_032),
            new Area("Q162022", "Malines", "Mechelen", "commune", 86_921),
            new Area("Q180775", "Molenbeek-Saint-Jean", "Sint-Jans-Molenbeek", "commune", 97_979),
            new Area("Q83407", "Mons", "Bergen", "commune", 95_299),
            new Area("Q220145", "Mouscron", "Moeskroen", "commune", 58_234),
            new Area("Q134121", "Namur", "Namen", "commune", 115_330),
            new Area("Q12996", "Ostende", "Oostende", "commune", 71_557),
            new Area("Q211037", "Roulers", "Roeselare", "commune", 64_495),
            new Area("Q13127", "Saint-Nicolas", "Sint-Niklaas", "commune", 80_167),
            new Area("Q12887", "Schaerbeek", "Schaarbeek", "commune", 132_799),
            new Area("Q194037", "Seraing", "Seraing", "commune", 64_270),
            new Area("Q173219", "Tournai", "Doornik", "commune", 69_554),
            new Area("Q203312", "Uccle", "Ukkel", "commune", 83_980),
            new Area("Q202954", "Verviers", "Verviers", "commune", 55_198),
            new Area("Q211764", "Woluwe-Saint-Lambert", "Sint-Lambrechts-Woluwe", "commune", 57_712)
    );

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
                    "Collectivités belges de %d habitants ou plus (%d communes, %d provinces) : %d bourgmestres et %d gouverneurs (sources officielles Cour des comptes et LBLOD).",
                    minPopulation, communes, provinces, mayors, governors);
            if (regimandMandates > 0) {
                m += String.format(" %d échevins, présidents de CPAS et députés provinciaux (Cour des comptes / LBLOD).", regimandMandates);
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
        Map<String, Area> targetAreas = resolveAreas(p, ctx.rawDir(), minPop);

        // LBLOD (officiel gouvernement flamand)
        String lblodEndpoint = p.getOrDefault("lblodEndpoint", DEFAULT_LBLOD_ENDPOINT).trim();
        if (!lblodEndpoint.isBlank()) {
            downloadLblod(lblodEndpoint, ctx.rawDir());
        }

        // Wikidata (optionnel uniquement si explicitement configuré en paramètre)
        String wikidataEndpoint = p.getOrDefault("wikidataEndpoint", "").trim();
        if (!wikidataEndpoint.isBlank()) {
            downloadWikidata(wikidataEndpoint, minPop, ctx.rawDir());
        }

        // Regimand (Cour des comptes de Belgique)
        String regimandUrl = p.getOrDefault("regimandUrl", RegimandLocal.DEFAULT_URL).trim();
        if (!regimandUrl.isBlank()) {
            long delay = Long.parseLong(p.getOrDefault("regimandDelayMs", String.valueOf(DEFAULT_REGIMAND_DELAY_MS)).trim());
            downloadRegimand(RegimandLocal.httpClient(regimandUrl), targetAreas, delay, ctx.rawDir());
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
        if (wikidataEndpoint != null && !wikidataEndpoint.isBlank()) {
            downloadWikidata(wikidataEndpoint, minPopulation, rawDir);
        }
        if (lblodEndpoint != null && !lblodEndpoint.isBlank()) {
            downloadLblod(lblodEndpoint, rawDir);
        }
    }

    void downloadWikidata(String wikidataEndpoint, long minPopulation, Path rawDir)
            throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String wikiJson = postSparql(http, wikidataEndpoint, wikidataQuery(minPopulation));
        Files.writeString(rawDir.resolve(WIKIDATA_FILE), wikiJson, StandardCharsets.UTF_8);
    }

    void downloadLblod(String lblodEndpoint, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            String lblodJson = postSparql(http, lblodEndpoint, lblodQuery());
            Files.writeString(rawDir.resolve(LBLOD_FILE), lblodJson, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("LBLOD SPARQL indisponible ({}) : {}", lblodEndpoint, e.getMessage());
        }
    }

    void downloadRegimand(RegimandLocal.Client client, long minPopulation, long delayMs, Path rawDir)
            throws IOException, InterruptedException {
        downloadRegimand(client, resolveAreas(Map.of(), rawDir, minPopulation), delayMs, rawDir);
    }

    void downloadRegimand(RegimandLocal.Client client, Map<String, Area> areas, long delayMs, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        List<String> failures = new ArrayList<>();
        for (Area area : areas.values()) {
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            try {
                for (String label : searchTerms(area)) {
                    for (JsonNode statement : json.readTree(client.search(label))) {
                        byId.putIfAbsent(statement.path("id").asText(), statement);
                    }
                    if (delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
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

    static Set<String> searchTerms(Area area) {
        Set<String> terms = new LinkedHashSet<>();
        terms.add(area.labelFr());
        if (!area.labelNl().isBlank()) {
            terms.add(area.labelNl());
        }
        return terms;
    }

    Map<String, Area> resolveAreas(Map<String, String> params, Path rawDir, long minPopulation) {
        Path wikiPath = rawDir != null ? rawDir.resolve(WIKIDATA_FILE) : null;
        if (wikiPath != null && Files.exists(wikiPath)) {
            try {
                return areas(json.readTree(Files.readString(wikiPath, StandardCharsets.UTF_8)), minPopulation);
            } catch (Exception e) {
                log.warn("Lecture {} impossible : {}", WIKIDATA_FILE, e.getMessage());
            }
        }
        Map<String, Area> result = new LinkedHashMap<>();
        for (Area a : OFFICIAL_STATBEL_AREAS) {
            if (a.population() >= minPopulation) {
                result.put(a.qid(), a);
            }
        }
        return result;
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
        Map<String, Area> areasByQid = new LinkedHashMap<>();
        Map<String, Area> areasByNormalizedName = new LinkedHashMap<>();
        Map<String, WikiStatement> statementsById = new LinkedHashMap<>();

        Path wikiPath = rawDir.resolve(WIKIDATA_FILE);
        if (Files.exists(wikiPath)) {
            JsonNode wikiRoot = json.readTree(Files.readString(wikiPath, StandardCharsets.UTF_8));
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
        } else {
            // Source officielle Statbel : 100% officiel sans dépendre de Wikidata
            for (Area area : OFFICIAL_STATBEL_AREAS) {
                if (area.population() >= minPopulation) {
                    areasByQid.put(area.qid(), area);
                    areasByNormalizedName.put(fold(area.labelFr()), area);
                    if (!area.labelNl().isBlank()) {
                        areasByNormalizedName.put(fold(area.labelNl()), area);
                    }
                }
            }
        }

        // Sélection par collectivité des mandats actuels et récemment terminés (si Wikidata disponible)
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
                recordedAreaPersonKeys.add(s.area().qid() + "|head|" + fold(s.personLabel()));
                if ("province".equals(s.area().kind())) {
                    governors++;
                } else {
                    mayors++;
                }
            }
        }

        // LBLOD (Gouvernement flamand)
        int lblodMayorsGovernorsAdded = 0;
        int lblodOtherMandates = 0;
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
                boolean isMayorOrGov = isMayorOrGovernor(roleLabel);
                String cat = isMayorOrGov ? "head" : roleCategory(roleLabel);
                String key = area.qid() + "|" + cat + "|" + fold(fullName);
                if (recordedAreaPersonKeys.add(key)) {
                    if (isMayorOrGov) {
                        writeLblod(dataset, area, firstName, familyName, fullName, start, personUri);
                        lblodMayorsGovernorsAdded++;
                        if ("province".equals(area.kind())) {
                            governors++;
                        } else {
                            mayors++;
                        }
                    } else {
                        writeLblodOther(dataset, area, firstName, familyName, fullName, roleLabel, start, personUri);
                        lblodOtherMandates++;
                    }
                }
            }
        }

        // Regimand (Cour des comptes de Belgique)
        int regimandOtherMandates = 0;
        for (Area area : areasByQid.values()) {
            Path file = rawDir.resolve(REGIMAND_PREFIX + area.qid() + ".json");
            if (!Files.exists(file)) {
                continue;
            }
            for (RegimandLocal.Mandate m : RegimandLocal.select(json.readTree(Files.readString(file, StandardCharsets.UTF_8)),
                    "province".equals(area.kind()), List.of(area.labelFr(), area.labelNl()), since)) {
                String cat = roleCategory(m.role());
                boolean isMayorOrGov = "mayor".equals(cat) || "governor".equals(cat);
                String key = area.qid() + "|" + (isMayorOrGov ? "head" : cat) + "|" + fold(m.fullName());
                if (recordedAreaPersonKeys.add(key)) {
                    writeRegimand(dataset, area, m, since);
                    if (isMayorOrGov) {
                        if ("province".equals(area.kind())) {
                            governors++;
                        } else {
                            mayors++;
                        }
                    } else {
                        regimandOtherMandates++;
                    }
                }
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
        int totalOtherMandates = lblodOtherMandates + regimandOtherMandates;
        Report report = new Report(communes, provinces, mayors, governors, lblodMayorsGovernorsAdded, minPopulation,
                totalOtherMandates, regimandFailures);
        log.info(report.message());
        return report;
    }

    private static Area matchLblodArea(String unitName, String roleLabel, Map<String, Area> byName) {
        String cleaned = unitName.replaceFirst("(?i)^(gemeente|stad|provincie|district)\\s+", "").trim();
        Area area = byName.get(fold(cleaned));
        if (area == null) {
            return null;
        }
        boolean isProvRole = roleLabel != null && (roleLabel.toLowerCase(Locale.ROOT).contains("gouverneur")
                || roleLabel.toLowerCase(Locale.ROOT).contains("gedeputeerde"));
        if (isProvRole && !"province".equals(area.kind())) {
            // Ex. « Antwerpen » (Gouverneur / Gedeputeerde de la province d'Anvers vs Bourgmestre / Schepen de la ville d'Anvers)
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

    private static void writeLblodOther(FtmDatasetBuilder dataset, Area area, String firstName, String familyName,
            String fullName, String roleLabel, String start, String personUri) {
        String title = formatLblodRole(roleLabel, area);
        FtmEntity person = dataset.newEntity("Person", "lblod-person", area.qid(), fold(fullName));
        person.addValue("name", fullName);
        person.addValue("firstName", firstName);
        person.addValue("lastName", familyName);
        person.addValue("country", "be");
        person.addValue("position", title);
        person.addValue("sourceUrl", personUri.isBlank() ? "https://centrale-vindplaats.lblod.info" : personUri);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "lblod-pos", area.qid(), fold(roleLabel));
        position.addValue("name", title);
        position.addValue("country", "be");
        position.addValue("subnationalArea", area.labelFr());
        position.addValue("description", "Collectivité belge de " + area.population() + " habitants");
        position.addValue("topics", area.topic());
        position.addValue("topics", "gov.executive");
        position.addValue("sourceUrl", "https://centrale-vindplaats.lblod.info");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "lblod-occ", area.qid(), fold(fullName), fold(roleLabel),
                start == null ? "" : start);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", start);
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", personUri.isBlank() ? "https://centrale-vindplaats.lblod.info" : personUri);
        dataset.addEntity(occupancy);
    }

    static boolean isMayorOrGovernor(String roleLabel) {
        if (roleLabel == null) {
            return false;
        }
        String r = roleLabel.toLowerCase(Locale.ROOT);
        return r.contains("burgemeester") || r.contains("gouverneur");
    }

    static String formatLblodRole(String roleLabel, Area area) {
        String r = roleLabel.toLowerCase(Locale.ROOT);
        String label;
        if (r.contains("gedeputeerde")) {
            label = "Député provincial (Gedeputeerde)";
        } else if (r.contains("bijzonder comit") || r.contains("ocmw")) {
            label = "Président de CPAS (Voorzitter BCSD)";
        } else if (r.contains("schepen")) {
            label = "Échevin (Schepen)";
        } else {
            label = roleLabel;
        }
        return label + " – " + area.labelFr();
    }

    /** Mandat local (bourgmestre, gouverneur, échevin, président de CPAS ou député provincial) déclaré à la Cour des comptes. */
    private static void writeRegimand(FtmDatasetBuilder dataset, Area area, RegimandLocal.Mandate m, LocalDate since) {
        boolean current = m.end() == null;
        String cat = roleCategory(m.role());
        boolean isMayorOrGov = "mayor".equals(cat) || "governor".equals(cat);
        String title = isMayorOrGov ? area.functionTitle() : m.role() + " – " + area.labelFr();

        FtmEntity person = dataset.newEntity("Person", "regimand", m.profileId());
        person.addValue("name", m.fullName());
        person.addValue("firstName", m.firstName());
        person.addValue("lastName", m.lastName());
        person.addValue("country", "be");
        person.addValue("position", current ? title : "Ancien : " + title);
        person.addValue("sourceUrl", RegimandLocal.SOURCE_URL);
        dataset.addEntity(person);

        FtmEntity position;
        if (isMayorOrGov) {
            position = writePosition(dataset, area);
        } else {
            position = dataset.newEntity("Position", "regimand", area.qid(), RegimandLocal.fold(m.role()));
            position.addValue("name", title);
            position.addValue("country", "be");
            position.addValue("subnationalArea", area.labelFr());
            position.addValue("description", "Collectivité belge de " + area.population() + " habitants");
            position.addValue("topics", area.topic());
            position.addValue("topics", "gov.executive");
            position.addValue("sourceUrl", RegimandLocal.SOURCE_URL);
            dataset.addEntity(position);
        }

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

    static String roleCategory(String role) {
        String r = RegimandLocal.fold(role);
        if (r.contains("bourgmestre") || r.contains("burgemeester")) {
            return "mayor";
        }
        if (r.contains("gouverneur")) {
            return "governor";
        }
        if (r.contains("depute") || r.contains("gedeputeerde")) {
            return "deputy";
        }
        if (r.contains("cpas") || r.contains("bijzonder comit") || r.contains("ocmw")) {
            return "cpas";
        }
        return "alderman";
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
                    <http://data.vlaanderen.be/id/concept/BestuursfunctieCode/5ab0e9b8a3b2ca7c5e000013> # Burgemeester
                    <http://data.vlaanderen.be/id/concept/BestuursfunctieCode/7b038cc40bba10bec833ecfe6f15bc7a> # Aangewezen burgemeester
                    <http://data.vlaanderen.be/id/concept/BestuursfunctieCode/d7c00cd1-baf1-4346-83c0-6796c0bedd85> # Gouverneur
                    <http://data.vlaanderen.be/id/concept/BestuursfunctieCode/5ab0e9b8a3b2ca7c5e000014> # Schepen
                    <http://data.vlaanderen.be/id/concept/BestuursfunctieCode/59a90e03-4f22-4bb9-8c91-132618db4b38> # Toegevoegde schepen
                    <http://data.vlaanderen.be/id/concept/BestuursfunctieCode/5ab0e9b8a3b2ca7c5e000020> # Gedeputeerde
                    <http://data.vlaanderen.be/id/concept/BestuursfunctieCode/5ab0e9b8a3b2ca7c5e00001a> # Voorzitter van het BCSD
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

