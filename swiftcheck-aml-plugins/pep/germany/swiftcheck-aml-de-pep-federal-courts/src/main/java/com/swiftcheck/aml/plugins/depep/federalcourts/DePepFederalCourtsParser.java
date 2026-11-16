package com.swiftcheck.aml.plugins.depep.federalcourts;

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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_federal_courts</b> – Juges des cours fédérales suprêmes (catégorie <b>DE-d</b>, § 1 (12) d) GwG).
 * <p>
 * Explore les pages des cours fédérales suprêmes (Bundesverfassungsgericht, BGH, BVerwG, BAG, BSG, BFH) :
 * lit les listes officielles et pour le BVerfG découvre et visite les sous-liens de détail des 16 juges
 * pour enrichir leur CV (Lebenslauf) et leur date de prise de fonction.
 * Consigne les alertes SOURCE_BROKEN et STRUCTURE_CHANGED sans interrompre l'exécution.
 */
@Extension
public class DePepFederalCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepFederalCourtsParser.class);

    static final String ID_PREFIX = "de-pep-federal-courts";
    static final String TOPIC = "role.pep";
    static final String CONFIG_RESOURCE = "/federal-courts.json";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Cours fédérales)";
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    private static final Pattern BVERFG_JUDGE_LINK = Pattern.compile(
            "(?si)<a\\s+[^>]*href=\"([^\"]*/RichterinnenRichter/[^\"]*/([^/]+)_node\\.html)\"[^>]*class=\"[^\"]*c-teaser__headline[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</a>");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourtConfig(String key, String name, String acronym, String url) {
    }

    /**
     * Liste Wikipédia des juges d'une cour, avec le plan de répartition officiel qui sert à la confirmer :
     * {@code planPdfUrl} (adresse fixe) ou {@code planDiscoveryUrl} (page à partir de laquelle on trouve le PDF).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JudgeListConfig(String key, String name, String acronym, String page, String planPdfUrl,
                                  String planDiscoveryUrl, Long planDelayMs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<CourtConfig> courts, List<JudgeListConfig> judgeLists) {
        public Config(List<CourtConfig> courts) {
            this(courts, List.of());
        }
    }

    /** Résultat de la lecture d'une cour. */
    record Outcome(CourtConfig court, List<FederalPages.Member> members, String problem) {
    }

    /**
     * Résultat de la lecture d'une liste Wikipédia : les juges, le texte du plan officiel (null s'il n'a pas pu être
     * lu) et la raison de l'échec éventuel (liste ou plan).
     */
    record JudgeOutcome(JudgeListConfig list, List<WikipediaJudges.Judge> judges, String planText, String problem) {
    }

    static final long DEFAULT_WIKIPEDIA_DELAY_MS = 5000;

    /** Accès HTTP remplaçable dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        if (ctx.rawDir() != null) {
            Files.createDirectories(ctx.rawDir());
        }
        long delay = parseLong(p.get("delayMs"), 1000);
        int months = (int) parseLong(p.get("monthsAfterEnd"), DEFAULT_MONTHS_AFTER_END);

        Config config = resolveConfig(p);
        Fetcher fetcher = http(delay);

        List<Outcome> outcomes = readAll(config, fetcher, ctx.rawDir());
        List<JudgeOutcome> lists = List.of();
        if (!"false".equalsIgnoreCase(p.get("judgeLists"))) {
            // les listes viennent toujours de la configuration embarquée, même si « urls » restreint les pages officielles
            lists = readJudgeLists(loadConfig().judgeLists(), http(parseLong(p.get("wikipediaDelayMs"), DEFAULT_WIKIPEDIA_DELAY_MS)),
                    list -> readPlan(list, list.planDelayMs() != null ? list.planDelayMs() : delay), ctx.rawDir());
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        LocalDate today = LocalDate.now();
        String message = convert(outcomes, today, months, dataset);
        if (!lists.isEmpty()) {
            message = message + " " + convertJudges(lists, outcomes, today, months, dataset);
        }
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Lecture du texte du plan officiel d'une cour, remplaçable dans les tests. */
    interface PlanReader {
        String read(JudgeListConfig list) throws IOException;
    }

    /**
     * Lit chaque liste Wikipédia, puis le plan officiel de la cour quand il est configuré. Une liste illisible est
     * signalée sans interrompre les autres ; un plan illisible laisse la liste publiée, non confirmée.
     */
    static List<JudgeOutcome> readJudgeLists(List<JudgeListConfig> lists, Fetcher wikipedia, PlanReader plans, Path rawDir)
            throws IOException {
        List<JudgeOutcome> out = new ArrayList<>();
        if (lists == null) {
            return out;
        }
        for (JudgeListConfig list : lists) {
            List<WikipediaJudges.Judge> judges;
            try {
                String response = wikipedia.get(WikipediaJudges.apiUrl(list.page()));
                if (rawDir != null) {
                    Files.writeString(rawDir.resolve("wikipedia-" + list.key() + ".json"), response, StandardCharsets.UTF_8);
                }
                judges = WikipediaJudges.parse(WikipediaJudges.wikitext(response));
                if (judges.isEmpty()) {
                    out.add(new JudgeOutcome(list, List.of(), null, "ALERT STRUCTURE_CHANGED : liste Wikipédia sans ligne {{PersonZelle}}"));
                    continue;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("SOURCE_BROKEN: liste Wikipédia {} non lue : {}", list.page(), e.getMessage());
                out.add(new JudgeOutcome(list, List.of(), null, "ALERT SOURCE_BROKEN : liste Wikipédia : " + e.getMessage()));
                continue;
            }
            String planText = null;
            String problem = null;
            if (list.planPdfUrl() != null || list.planDiscoveryUrl() != null) {
                try {
                    planText = plans.read(list);
                    if (rawDir != null) {
                        Files.writeString(rawDir.resolve("plan-" + list.key() + ".txt"), planText, StandardCharsets.UTF_8);
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("Plan officiel {} non lu : {}", list.acronym(), e.getMessage());
                    problem = "plan officiel non lu : " + e.getMessage();
                }
            }
            out.add(new JudgeOutcome(list, judges, planText, problem));
        }
        return out;
    }

    /** Plan officiel en ligne : PDF à adresse fixe, ou découverte du PDF de l'année la plus récente (BGH). */
    static String readPlan(JudgeListConfig list, long delayMs) throws IOException {
        Fetcher pages = http(delayMs);
        String pdfUrl = list.planPdfUrl();
        if (pdfUrl == null) {
            String overview = pages.get(list.planDiscoveryUrl());
            String yearPage = OfficialPlans.latestBghYearPage(overview, list.planDiscoveryUrl());
            pdfUrl = OfficialPlans.pdfLink(pages.get(yearPage), yearPage);
        }
        return OfficialPlans.text(httpBytes(delayMs).get(pdfUrl));
    }

    /**
     * Ajoute les juges des listes Wikipédia en fonction (ou sortis depuis moins de {@code months} mois). Une personne
     * déjà lue sur la page officielle de la cour (président, vice-président) n'est pas reprise. Les juges absents du
     * plan officiel restent publiés (mieux vaut un juge sorti qu'un juge manquant), avec une note.
     */
    static String convertJudges(List<JudgeOutcome> lists, List<Outcome> official, LocalDate today, int months,
                                FtmDatasetBuilder dataset) {
        List<String> parts = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int total = 0;
        for (JudgeOutcome o : lists) {
            JudgeListConfig list = o.list();
            if (o.judges().isEmpty()) {
                failures.add(list.acronym() + " (" + o.problem() + ")");
                continue;
            }
            java.util.Set<String> alreadyRead = new java.util.HashSet<>();
            for (Outcome off : official) {
                if (off.court().key().equals(list.key())) {
                    off.members().forEach(m -> alreadyRead.add(WikipediaJudges.normalizedName(m.cleanName())));
                }
            }
            String sourceUrl = "https://de.wikipedia.org/wiki/" + list.page();
            FtmEntity position = dataset.newEntity("Position", list.key() + "-judge");
            position.addValue("name", "Juge ou dirigeant de la cour : " + list.name());
            position.addValue("country", "de");
            position.addValue("organization", list.name());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.judicial");
            dataset.addEntity(position);
            int kept = 0;
            int confirmed = 0;
            for (WikipediaJudges.Judge j : o.judges()) {
                if (!j.keep(today, months) || alreadyRead.contains(WikipediaJudges.normalizedName(j.fullName()))) {
                    continue;
                }
                boolean inPlan = o.planText() != null && WikipediaJudges.inOfficialPlan(j.lastName(), o.planText());
                String role = j.role(list.name());
                FtmEntity person = dataset.newEntity("Person", list.key(), j.fullName());
                person.addValue("name", j.fullName());
                person.addValue("firstName", j.firstName());
                person.addValue("lastName", j.lastName());
                person.addValue("birthDate", j.birthYear() == null ? null : String.valueOf(j.birthYear()));
                person.addValue("country", "de");
                person.addValue("position", role);
                person.addValue("sourceUrl", sourceUrl);
                person.addValue("notes", o.planText() == null ? "Source : Wikipédia (liste des juges de la cour)."
                        : inPlan ? "Source : Wikipédia ; nom présent dans le plan de répartition officiel de la cour."
                        : "Source : Wikipédia ; nom absent du plan de répartition officiel de la cour (départ à vérifier).");
                dataset.addEntity(person);

                boolean ended = j.end() != null && !j.end().isAfter(today);
                FtmEntity occ = dataset.newEntity("Occupancy", list.key(), j.fullName(), "wikipedia");
                occ.addValue("holder", person.getId());
                occ.addValue("post", position.getId());
                occ.addValue("startDate", j.start().toString());
                occ.addValue("endDate", j.end() == null ? null : j.end().toString());
                occ.addValue("status", ended ? "ended" : "current");
                occ.addValue("description", role);
                occ.addValue("sourceUrl", sourceUrl);
                dataset.addEntity(occ);
                kept++;
                if (inPlan) {
                    confirmed++;
                }
            }
            total += kept;
            parts.add(list.acronym() + " " + kept + (o.planText() != null ? " (" + confirmed + " confirmés par le plan officiel)" : "")
                    + (o.problem() != null ? " (" + o.problem() + ")" : ""));
        }
        String message = total + " juges lus sur les listes Wikipédia : " + String.join(", ", parts) + ".";
        return failures.isEmpty() ? message : message + " Listes non lues : " + String.join(" ; ", failures) + ".";
    }

    static Config resolveConfig(Map<String, String> parameters) {
        String urlsParam = parameters.get("urls");
        if (urlsParam != null && !urlsParam.isBlank()) {
            List<CourtConfig> courts = new ArrayList<>();
            if (urlsParam.trim().startsWith("[")) {
                Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(urlsParam);
                while (m.find()) {
                    String u = m.group(1).trim();
                    if (u.contains("bundesverfassungsgericht")) {
                        courts.add(new CourtConfig("bverfg", "Bundesverfassungsgericht", "BVerfG", u));
                    }
                }
            } else {
                for (String u : urlsParam.split("[\r\n,]+")) {
                    String clean = u.trim();
                    if (clean.contains("bundesverfassungsgericht")) {
                        courts.add(new CourtConfig("bverfg", "Bundesverfassungsgericht", "BVerfG", clean));
                    }
                }
            }
            if (!courts.isEmpty()) {
                return new Config(courts);
            }
        }
        try {
            return loadConfig();
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger la configuration des cours fédérales", e);
        }
    }

    static Config loadConfig() throws IOException {
        try (var in = DePepFederalCourtsParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return new ObjectMapper().readValue(in, Config.class);
        }
    }

    static List<Outcome> readAll(Config config, Fetcher fetcher, Path rawDir) throws IOException {
        List<Outcome> out = new ArrayList<>();
        for (CourtConfig c : config.courts()) {
            var extractor = FederalPages.EXTRACTORS.get(c.key());
            if (extractor == null) {
                out.add(new Outcome(c, List.of(), "aucun extracteur pour cette cour"));
                continue;
            }
            try {
                String html = fetcher.get(c.url());
                if (rawDir != null) {
                    Files.writeString(rawDir.resolve(c.key() + ".html"), html, StandardCharsets.UTF_8);
                }
                List<FederalPages.Member> members = extractor.apply(PageText.lines(html));
                if (members.isEmpty()) {
                    out.add(new Outcome(c, List.of(), "ALERT STRUCTURE_CHANGED : page lue mais aucune personne reconnue"));
                } else {
                    // Enrichissement récursif pour BVerfG : découverte des sous-liens de détail des juges
                    if ("bverfg".equals(c.key())) {
                        members = enrichBverfgJudges(html, members, fetcher);
                    }
                    out.add(new Outcome(c, members, null));
                }
            } catch (IOException | RuntimeException e) {
                log.warn("SOURCE_BROKEN: Cour {} ({}) non lue : {}", c.acronym(), c.url(), e.getMessage());
                out.add(new Outcome(c, List.of(), "ALERT SOURCE_BROKEN : " + e.getMessage()));
            }
        }
        if (out.stream().allMatch(o -> o.members().isEmpty())) {
            throw new IllegalStateException("Aucune cour fédérale lisible : "
                    + String.join(" ; ", out.stream().map(o -> o.court().key() + " (" + o.problem() + ")").toList()));
        }
        return out;
    }

    static List<FederalPages.Member> enrichBverfgJudges(String overviewHtml, List<FederalPages.Member> originalMembers, Fetcher fetcher) {
        Map<String, String> detailUrls = new ArrayList<Map.Entry<String, String>>() {{
            Matcher m = BVERFG_JUDGE_LINK.matcher(overviewHtml);
            while (m.find()) {
                String fullLink = m.group(1);
                String namePart = m.group(3).replaceAll("<[^>]+>", "").trim();
                PageText.Candidate c = PageText.person(namePart);
                if (c != null) {
                    add(Map.entry(c.cleanName().toLowerCase(Locale.ROOT), fullLink));
                }
            }
        }}.stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

        List<FederalPages.Member> enriched = new ArrayList<>();
        for (FederalPages.Member m : originalMembers) {
            String detailUrl = detailUrls.get(m.cleanName().toLowerCase(Locale.ROOT));
            if (detailUrl != null) {
                try {
                    String judgeHtml = fetcher.get(detailUrl);
                    String cv = extractTableCv(judgeHtml);
                    LocalDate start = extractStartDate(judgeHtml);
                    enriched.add(new FederalPages.Member(m.fullName(), m.title(), m.cleanName(), m.role(),
                            start != null ? start : m.start(), m.end(), cv, detailUrl));
                } catch (Exception e) {
                    enriched.add(m);
                }
            } else {
                enriched.add(m);
            }
        }
        return enriched;
    }

    static String extractTableCv(String html) {
        Matcher m = Pattern.compile("(?si)<caption>Lebenslauf</caption>\\s*(.*?)\\s*</table>").matcher(html);
        if (m.find()) {
            String clean = m.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            if (clean.length() > 500) {
                clean = clean.substring(0, 500) + "...";
            }
            return clean;
        }
        return null;
    }

    static LocalDate extractStartDate(String html) {
        Matcher m = Pattern.compile("(?i)(?:Ernennung zum|Ernennung zur)[^.<>]*?(?:am|im)\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|[A-Za-zäöüÄÖÜ]+\\s*\\d{4})").matcher(html);
        if (m.find()) {
            return parseGermanDate(m.group(1));
        }
        return null;
    }

    static LocalDate parseGermanDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.GERMAN);
        Map<String, String> months = Map.ofEntries(
                Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
                Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
                Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
                Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
                Map.entry("dezember", "12")
        );
        Matcher m = Pattern.compile("(?:(\\d{1,2})\\.\\s*)?([a-zäöü]+)\\s*(\\d{4})").matcher(s);
        if (m.find()) {
            int day = m.group(1) != null ? Integer.parseInt(m.group(1)) : 1;
            String month = months.getOrDefault(m.group(2), "01");
            String year = m.group(3);
            return LocalDate.parse(String.format("%s-%s-%02d", year, month, day));
        }
        return null;
    }

    static String convert(List<Outcome> outcomes, LocalDate today, int months, FtmDatasetBuilder dataset) {
        LocalDate cutoff = today.minusMonths(months);
        int persons = 0;
        int dropped = 0;
        List<String> failures = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (o.problem() != null) {
                failures.add(o.court().acronym() + " (" + o.problem() + ")");
                continue;
            }
            CourtConfig c = o.court();
            FtmEntity position = dataset.newEntity("Position", c.key() + "-judge");
            position.addValue("name", "Juge ou dirigeant de la cour : " + c.name());
            position.addValue("country", "de");
            position.addValue("organization", c.name());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.judicial");
            dataset.addEntity(position);
            for (FederalPages.Member m : o.members()) {
                if (m.end() != null && m.end().isBefore(cutoff)) {
                    dropped++;
                    continue;
                }
                FtmEntity person = dataset.newEntity("Person", c.key(), m.cleanName());
                person.addValue("name", m.cleanName());
                person.addValue("title", m.title());
                String[] parts = m.cleanName().split("\\s+", 2);
                person.addValue("firstName", parts.length > 1 ? parts[0] : null);
                person.addValue("lastName", parts.length > 1 ? parts[1] : parts[0]);
                person.addValue("country", "de");
                person.addValue("position", m.role() + " – " + c.name());
                person.addValue("sourceUrl", m.detailUrl() != null ? m.detailUrl() : c.url());
                if (m.cvSummary() != null) {
                    person.addValue("summary", m.cvSummary());
                    person.addValue("notes", "Lebenslauf : " + m.cvSummary());
                }
                dataset.addEntity(person);

                boolean ended = m.end() != null && !m.end().isAfter(today);
                FtmEntity occ = dataset.newEntity("Occupancy", c.key(), m.cleanName(), m.role());
                occ.addValue("holder", person.getId());
                occ.addValue("post", position.getId());
                occ.addValue("startDate", m.start() == null ? null : m.start().toString());
                occ.addValue("endDate", m.end() == null ? null : m.end().toString());
                occ.addValue("status", ended ? "ended" : "current");
                occ.addValue("description", m.role());
                occ.addValue("sourceUrl", m.detailUrl() != null ? m.detailUrl() : c.url());
                dataset.addEntity(occ);
                persons++;
            }
        }
        String message = persons + " personnes lues sur " + (outcomes.size() - failures.size()) + " cours fédérales"
                + (dropped > 0 ? " (" + dropped + " anciens présidents sortis depuis plus de " + months + " mois écartés)" : "");
        return failures.isEmpty() ? message + "." : message + ". Erreurs: " + String.join(" ; ", failures) + ".";
    }

    private static long parseLong(String v, long def) {
        try {
            return Long.parseLong(v == null ? "" : v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static Fetcher http(long delayMs) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        return url -> {
            IOException last = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    Thread.sleep(delayMs);
                    HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                            .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (resp.statusCode() == 200) {
                        return resp.body();
                    }
                    last = new IOException("HTTP " + resp.statusCode());
                    if (resp.statusCode() < 500 && resp.statusCode() != 429) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrompu", e);
                } catch (IOException e) {
                    last = e;
                }
            }
            throw last;
        };
    }

    /** Téléchargement binaire (PDF des plans de répartition), avec la même politesse que {@link #http(long)}. */
    static OfficialPlans.BytesFetcher httpBytes(long delayMs) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        return url -> {
            try {
                Thread.sleep(delayMs);
                HttpResponse<byte[]> resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                        .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200) {
                    throw new IOException("HTTP " + resp.statusCode() + " pour " + url);
                }
                return resp.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrompu", e);
            }
        };
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        Config config = resolveConfig(p);
        long delay = parseLong(p.get("delayMs"), 1000);
        List<Outcome> outcomes = readAll(config, http(delay), null);
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (Outcome o : outcomes) {
            for (FederalPages.Member m : o.members()) {
                records.add(new SourceRecord(ids.buildEntityId(o.court().key(), m.cleanName()), "Person", m.cleanName(), m.role()));
            }
        }
        return records;
    }
}
