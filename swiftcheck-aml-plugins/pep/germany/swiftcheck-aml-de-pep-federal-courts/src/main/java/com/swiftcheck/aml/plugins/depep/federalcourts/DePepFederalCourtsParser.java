package com.swiftcheck.aml.plugins.depep.federalcourts;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * Sources, toutes officielles :
 * <ul>
 * <li>pages de la présidence de chaque cour, et liste des 16 juges du Bundesverfassungsgericht (avec leur CV) ;</li>
 * <li>plans de répartition (Geschäftsverteilungspläne) du BGH, du BVerwG, du BFH, du BAG et du BSG : la liste des
 * juges en fonction, avec nom de famille, titre et fonction ;</li>
 * <li>pour les prénoms, que les plans ne donnent pas : communiqués d'élection du ministère fédéral de la Justice et du
 * ministère fédéral du Travail, rapports annuels des cours, noms déjà relevés dans des communiqués officiels (paramètre
 * {@code judgeNames}) et, en dernier recours, recherche dans les communiqués de la cour.</li>
 * </ul>
 * Un juge du plan sans prénom trouvé est publié avec son nom de famille seul, et le message d'exécution le signale.
 * <p>
 * Toute la configuration est dans les paramètres du parser (aucun fichier embarqué) :
 * <ul>
 * <li>{@code courts} (obligatoire) : tableau JSON des cours, chacune avec {@code key}, {@code name}, {@code acronym},
 * {@code url} (page de la présidence), {@code plan} ({@code url} de départ et {@code links}, motifs des liens à suivre
 * jusqu'au PDF), {@code delayMs} (pause entre deux appels au site, selon son robots.txt) et {@code search}
 * (facultatif : {@code url} avec {@code {query}}, {@code query} avec {@code {lastName}}, {@code link} motif des liens
 * de communiqués, {@code results} nombre de communiqués lus par recherche) ;</li>
 * <li>{@code nameSources} : tableau JSON des adresses de communiqués et rapports annuels (HTML ou PDF) ;</li>
 * <li>{@code judgeNames} : tableau JSON de noms relevés dans des communiqués officiels ({@code court}, {@code name},
 * {@code source}) ;</li>
 * <li>{@code maxSearches} (10 par défaut), {@code delayMs} (1000), {@code monthsAfterEnd} (12),
 * {@code planJudges} ({@code false} pour ne publier que les présidences).</li>
 * </ul>
 * Consigne les alertes SOURCE_BROKEN et STRUCTURE_CHANGED sans interrompre l'exécution.
 */
@Extension
public class DePepFederalCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepFederalCourtsParser.class);

    static final String ID_PREFIX = "de-pep-federal-courts";
    static final String TOPIC = "role.pep";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Cours fédérales)";
    static final int DEFAULT_MONTHS_AFTER_END = 12;
    static final int DEFAULT_MAX_SEARCHES = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern BVERFG_JUDGE_LINK = Pattern.compile(
            "(?si)<a\\s+[^>]*href=\"([^\"]*/RichterinnenRichter/[^\"]*/([^/]+)_node\\.html)\"[^>]*class=\"[^\"]*c-teaser__headline[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</a>");

    /** Plan de répartition : page de départ et motifs des liens à suivre jusqu'au PDF. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanConfig(String url, List<String> links) {
    }

    /** Recherche des communiqués de nomination sur le site de la cour. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchConfig(String url, String query, String link, Integer results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourtConfig(String key, String name, String acronym, String url, PlanConfig plan, Long delayMs,
                              SearchConfig search, String wikipedia) {

        public CourtConfig(String key, String name, String acronym, String url) {
            this(key, name, acronym, url, null, null, null, null);
        }

        NamedJudges.Court court() {
            return new NamedJudges.Court(key, name, acronym);
        }
    }

    /** Nom relevé dans un communiqué officiel (paramètre {@code judgeNames}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KnownName(String court, String name, String source) {
    }

    public record Config(List<CourtConfig> courts, List<String> nameSources, List<KnownName> judgeNames) {
    }

    /** Résultat de la lecture d'une page de présidence. */
    record Outcome(CourtConfig court, List<FederalPages.Member> members, String problem) {
    }

    /** Résultat de la lecture du plan d'une cour : les juges (avec ou sans prénom), l'adresse du plan, le problème. */
    record PlanOutcome(CourtConfig court, List<JudgeRoster.Judge> judges, String planUrl, String problem) {
    }

    /** Accès HTTP texte remplaçable dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException;
    }

    /** Accès HTTP aux sources : texte, binaire, avec la pause propre à chaque site. */
    interface Web {
        String text(String url, long delayMs) throws IOException;

        byte[] bytes(String url, long delayMs) throws IOException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        if (ctx.rawDir() != null) {
            Files.createDirectories(ctx.rawDir());
        }
        long delay = parseLong(p.get("delayMs"), 1000);
        int months = (int) parseLong(p.get("monthsAfterEnd"), DEFAULT_MONTHS_AFTER_END);
        Config config = config(p);
        Web web = http();

        List<Outcome> outcomes = readAll(config, url -> web.text(url, delay), ctx.rawDir());
        List<PlanOutcome> plans = List.of();
        String namesMessage = "";
        if (!"false".equalsIgnoreCase(p.get("planJudges"))) {
            List<NamedJudges.NamedJudge> named = new ArrayList<>(knownNames(config));
            List<String> nameProblems = new ArrayList<>();
            named.addAll(readNameSources(config, web, delay, ctx.rawDir(), nameProblems));
            long wikipediaDelay = "false".equalsIgnoreCase(p.get("wikipediaFallback")) ? -1 : parseLong(p.get("wikipediaDelayMs"), 5000);
            plans = readPlans(config, web, ctx.rawDir(), named, (int) parseLong(p.get("maxSearches"), DEFAULT_MAX_SEARCHES), wikipediaDelay);
            namesMessage = nameProblems.isEmpty() ? "" : " Sources de prénoms illisibles : " + String.join(" ; ", nameProblems) + ".";
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        LocalDate today = LocalDate.now();
        String message = convert(outcomes, today, months, dataset);
        if (!plans.isEmpty()) {
            message = message + " " + convertPlans(plans, outcomes, dataset) + namesMessage;
        }
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    // ------------------------------------------------------------------ configuration

    /** Configuration lue dans les paramètres du parser. */
    static Config config(Map<String, String> parameters) {
        String courts = parameters.get("courts");
        if (courts == null || courts.isBlank()) {
            throw new IllegalArgumentException("Paramètre « courts » manquant dans la configuration du parser de_pep_federal_courts.");
        }
        try {
            return new Config(JSON.readValue(courts, new TypeReference<List<CourtConfig>>() { }),
                    list(parameters.get("nameSources"), new TypeReference<List<String>>() { }),
                    list(parameters.get("judgeNames"), new TypeReference<List<KnownName>>() { }));
        } catch (IOException e) {
            throw new IllegalArgumentException("Paramètres JSON illisibles (courts, nameSources ou judgeNames) : " + e.getMessage(), e);
        }
    }

    private static <T> List<T> list(String json, TypeReference<List<T>> type) throws IOException {
        return json == null || json.isBlank() ? List.of() : JSON.readValue(json, type);
    }

    // ------------------------------------------------------------------ prénoms

    /** Noms du paramètre {@code judgeNames}. */
    static List<NamedJudges.NamedJudge> knownNames(Config config) {
        List<NamedJudges.NamedJudge> out = new ArrayList<>();
        for (KnownName k : config.judgeNames()) {
            if (k.court() != null && k.name() != null && k.name().trim().contains(" ")) {
                out.add(new NamedJudges.NamedJudge(k.court(), List.of(k.name().trim().split("\\s+")), null, k.source()));
            }
        }
        return out;
    }

    /** Communiqués et rapports annuels du paramètre {@code nameSources}. Une source illisible est signalée, sans arrêt. */
    static List<NamedJudges.NamedJudge> readNameSources(Config config, Web web, long delayMs, Path rawDir, List<String> problems)
            throws IOException {
        List<NamedJudges.Court> courts = config.courts().stream().filter(c -> c.plan() != null).map(CourtConfig::court).toList();
        List<NamedJudges.NamedJudge> out = new ArrayList<>();
        int index = 0;
        for (String url : config.nameSources()) {
            index++;
            try {
                byte[] content = web.bytes(url, delayFor(config, url, delayMs));
                boolean pdf = OfficialPlans.isPdf(content);
                if (rawDir != null) {
                    Files.write(rawDir.resolve(String.format("names-%03d.%s", index, pdf ? "pdf" : "html")), content);
                }
                out.addAll(pdf ? NamedJudges.fromText(OfficialPlans.text(content), url, courts)
                        : NamedJudges.fromHtml(new String(content, StandardCharsets.UTF_8), url, courts));
            } catch (IOException | RuntimeException e) {
                log.warn("SOURCE_BROKEN: source de prénoms {} non lue : {}", url, e.getMessage());
                problems.add(url + " (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    /** Pause du site de la cour dont l'adresse est sur le même hôte, sinon la pause générale. */
    private static long delayFor(Config config, String url, long fallback) {
        String host = URI.create(url).getHost();
        for (CourtConfig c : config.courts()) {
            if (c.delayMs() != null && c.plan() != null && c.plan().url() != null && host != null
                    && host.equals(URI.create(c.plan().url()).getHost())) {
                return c.delayMs();
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------ plans

    /**
     * Lit le plan de chaque cour, complète les prénoms, puis cherche les prénoms manquants dans les communiqués de la cour
     * (au plus {@code maxSearches} recherches pour l'ensemble des cours).
     */
    static List<PlanOutcome> readPlans(Config config, Web web, Path rawDir, List<NamedJudges.NamedJudge> named, int maxSearches)
            throws IOException {
        return readPlans(config, web, rawDir, named, maxSearches, -1);
    }

    /** @param wikipediaDelayMs pause avant l'appel à Wikipédia ; négative, le complément Wikipédia n'est pas lu */
    static List<PlanOutcome> readPlans(Config config, Web web, Path rawDir, List<NamedJudges.NamedJudge> named, int maxSearches,
                                       long wikipediaDelayMs) throws IOException {
        List<PlanOutcome> out = new ArrayList<>();
        int searches = 0;
        for (CourtConfig c : config.courts()) {
            if (c.plan() == null) {
                continue;
            }
            long delay = c.delayMs() == null ? 1000 : c.delayMs();
            String planUrl = null;
            List<PlanJudges.PlanJudge> plan;
            try {
                planUrl = OfficialPlans.discover(c.plan().url(), c.plan().links(), url -> web.text(url, delay));
                byte[] pdf = web.bytes(planUrl, delay);
                if (rawDir != null) {
                    Files.write(rawDir.resolve("plan-" + c.key() + ".pdf"), pdf);
                }
                plan = PlanJudges.parse(OfficialPlans.text(pdf), Pattern.quote(c.name()) + "|" + Pattern.quote(c.acronym()));
            } catch (IOException | RuntimeException e) {
                log.warn("SOURCE_BROKEN: plan de répartition {} non lu : {}", c.acronym(), e.getMessage());
                out.add(new PlanOutcome(c, List.of(), planUrl, "ALERT SOURCE_BROKEN : plan de répartition : " + e.getMessage()));
                continue;
            }
            if (plan.isEmpty()) {
                out.add(new PlanOutcome(c, List.of(), planUrl, "ALERT STRUCTURE_CHANGED : plan lu mais aucun juge reconnu"));
                continue;
            }
            List<NamedJudges.NamedJudge> courtNames = new ArrayList<>(named);
            List<JudgeRoster.Judge> roster = JudgeRoster.match(c.key(), plan, courtNames);
            if (c.search() != null) {
                for (JudgeRoster.Judge j : roster) {
                    if (j.firstName() != null || searches >= maxSearches) {
                        continue;
                    }
                    searches++;
                    courtNames.addAll(search(c, j.plan().lastName(), web, delay, rawDir, searches));
                }
                roster = JudgeRoster.match(c.key(), plan, courtNames);
            }
            if (c.wikipedia() != null && wikipediaDelayMs >= 0 && roster.stream().anyMatch(j -> j.firstName() == null)) {
                roster = completeFromWikipedia(c, roster, web, wikipediaDelayMs, rawDir);
            }
            out.add(new PlanOutcome(c, roster, planUrl, null));
        }
        return out;
    }

    /**
     * Complément : prénom lu dans la liste Wikipédia des juges de la cour, pour les seuls juges du plan restés sans prénom
     * après les sources officielles. La liste Wikipédia n'ajoute aucun juge. Une liste illisible laisse les juges tels quels.
     */
    static List<JudgeRoster.Judge> completeFromWikipedia(CourtConfig c, List<JudgeRoster.Judge> roster, Web web, long delayMs, Path rawDir) {
        try {
            String response = web.text(WikipediaJudges.apiUrl(c.wikipedia()), delayMs);
            if (rawDir != null) {
                Files.writeString(rawDir.resolve("wikipedia-" + c.key() + ".json"), response, StandardCharsets.UTF_8);
            }
            List<NamedJudges.NamedJudge> wiki = WikipediaJudges.asNames(WikipediaJudges.parse(WikipediaJudges.wikitext(response)),
                    c.key(), c.wikipedia(), LocalDate.now());
            List<PlanJudges.PlanJudge> missing = roster.stream().filter(j -> j.firstName() == null).map(JudgeRoster.Judge::plan).toList();
            Map<PlanJudges.PlanJudge, JudgeRoster.Judge> completed = new java.util.HashMap<>();
            for (JudgeRoster.Judge j : JudgeRoster.match(c.key(), missing, wiki)) {
                if (j.firstName() != null) {
                    completed.put(j.plan(), j);
                }
            }
            return roster.stream().map(j -> completed.getOrDefault(j.plan(), j)).toList();
        } catch (IOException | RuntimeException e) {
            log.warn("Liste Wikipédia {} non lue : {}", c.wikipedia(), e.getMessage());
            return roster;
        }
    }

    /** Communiqués de la cour trouvés par sa recherche pour un nom de famille. Une recherche en échec ne donne rien. */
    static List<NamedJudges.NamedJudge> search(CourtConfig c, String lastName, Web web, long delay, Path rawDir, int index) {
        SearchConfig s = c.search();
        try {
            String query = s.query().replace("{lastName}", lastName);
            String url = s.url().replace("{query}", URLEncoder.encode(query, StandardCharsets.UTF_8));
            String results = web.text(url, delay);
            Pattern link = Pattern.compile(s.link());
            Set<String> links = new java.util.LinkedHashSet<>();
            Matcher m = Pattern.compile("href=\"([^\"]+)\"").matcher(results);
            while (m.find() && links.size() < (s.results() == null ? 2 : s.results())) {
                String href = m.group(1).replace("&amp;", "&");
                if (link.matcher(href).find()) {
                    links.add(URI.create(url).resolve(href.replaceAll("\\?.*$", "")).toString());
                }
            }
            List<NamedJudges.NamedJudge> out = new ArrayList<>();
            int k = 0;
            for (String pm : links) {
                String html = web.text(pm, delay);
                if (rawDir != null) {
                    Files.writeString(rawDir.resolve(String.format("search-%s-%03d-%d.html", c.key(), index, ++k)), html, StandardCharsets.UTF_8);
                }
                out.addAll(NamedJudges.fromHtml(html, pm, List.of(c.court())));
            }
            return out;
        } catch (IOException | RuntimeException e) {
            log.warn("Recherche {} « {} » en échec : {}", c.acronym(), lastName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Publie les juges des plans. Une personne déjà lue sur la page de la présidence n'est pas reprise. Un juge sans
     * prénom est publié avec son nom de famille et son titre.
     */
    static String convertPlans(List<PlanOutcome> plans, List<Outcome> official, FtmDatasetBuilder dataset) {
        List<String> parts = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> withoutFirstName = new ArrayList<>();
        int total = 0;
        for (PlanOutcome o : plans) {
            CourtConfig c = o.court();
            if (o.problem() != null) {
                failures.add(c.acronym() + " (" + o.problem() + ")");
                continue;
            }
            Set<String> alreadyRead = new HashSet<>();
            for (Outcome off : official) {
                if (off.court().key().equals(c.key())) {
                    off.members().forEach(m -> alreadyRead.add(lastToken(m.cleanName())));
                }
            }
            FtmEntity position = dataset.newEntity("Position", c.key() + "-judge");
            position.addValue("name", "Juge ou dirigeant de la cour : " + c.name());
            position.addValue("country", "de");
            position.addValue("organization", c.name());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.judicial");
            dataset.addEntity(position);
            int kept = 0;
            int complete = 0;
            int fromWikipedia = 0;
            for (JudgeRoster.Judge j : o.judges()) {
                PlanJudges.PlanJudge pj = j.plan();
                if (alreadyRead.contains(lastToken(pj.lastName()))) {
                    continue;
                }
                String role = (pj.presiding() ? (pj.female() ? "Vorsitzende Richterin" : "Vorsitzender Richter")
                        : (pj.female() ? "Richterin" : "Richter")) + " am " + c.name();
                FtmEntity person = dataset.newEntity("Person", c.key(), j.fullName());
                person.addValue("name", j.fullName());
                person.addValue("title", pj.title());
                person.addValue("firstName", j.firstName());
                person.addValue("lastName", pj.lastName());
                person.addValue("gender", pj.female() ? "female" : "male");
                person.addValue("country", "de");
                person.addValue("position", role);
                person.addValue("sourceUrl", o.planUrl());
                boolean wiki = WikipediaJudges.isWikipedia(j.nameSource());
                person.addValue("notes", j.firstName() != null && wiki
                        ? "Fonction et nom de famille : plan de répartition officiel de la cour. Prénom : Wikipédia (" + j.nameSource()
                        + "), non confirmé par une source officielle."
                        : j.firstName() != null
                        ? "Fonction et nom de famille : plan de répartition officiel de la cour. Prénom : " + j.nameSource() + "."
                        : "Fonction et nom de famille : plan de répartition officiel de la cour. Prénom non trouvé dans les "
                        + "publications officielles (" + j.missing() + ").");
                dataset.addEntity(person);

                FtmEntity occ = dataset.newEntity("Occupancy", c.key(), j.fullName(), "plan");
                occ.addValue("holder", person.getId());
                occ.addValue("post", position.getId());
                occ.addValue("status", "current");
                occ.addValue("description", role);
                occ.addValue("sourceUrl", o.planUrl());
                dataset.addEntity(occ);
                kept++;
                if (j.firstName() != null) {
                    complete++;
                    if (wiki) {
                        fromWikipedia++;
                    }
                } else {
                    withoutFirstName.add(c.acronym() + " " + pj.displayName());
                }
            }
            total += kept;
            parts.add(c.acronym() + " " + kept + " (" + complete + " avec prénom" + (fromWikipedia > 0 ? ", dont " + fromWikipedia
                    + " par Wikipédia" : "") + ")");
        }
        String message = total + " juges lus dans les plans de répartition officiels : " + String.join(", ", parts) + ".";
        if (!withoutFirstName.isEmpty()) {
            message += " Sans prénom (publiés avec le nom de famille seul) : " + String.join(", ", withoutFirstName) + ".";
        }
        return failures.isEmpty() ? message : message + " Plans non lus : " + String.join(" ; ", failures) + ".";
    }

    private static String lastToken(String name) {
        String n = name.trim();
        return n.substring(n.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ pages de présidence

    static List<Outcome> readAll(Config config, Fetcher fetcher, Path rawDir) throws IOException {
        List<Outcome> out = new ArrayList<>();
        for (CourtConfig c : config.courts()) {
            if (c.url() == null || c.url().isBlank()) {
                continue;
            }
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
        Map<String, String> detailUrls = new LinkedHashMap<>();
        Matcher m = BVERFG_JUDGE_LINK.matcher(overviewHtml);
        while (m.find()) {
            String namePart = m.group(3).replaceAll("<[^>]+>", "").trim();
            PageText.Candidate c = PageText.person(namePart);
            if (c != null) {
                detailUrls.putIfAbsent(c.cleanName().toLowerCase(Locale.ROOT), m.group(1));
            }
        }
        List<FederalPages.Member> enriched = new ArrayList<>();
        for (FederalPages.Member member : originalMembers) {
            String detailUrl = detailUrls.get(member.cleanName().toLowerCase(Locale.ROOT));
            if (detailUrl != null) {
                try {
                    String judgeHtml = fetcher.get(detailUrl);
                    String cv = extractTableCv(judgeHtml);
                    LocalDate start = extractStartDate(judgeHtml);
                    enriched.add(new FederalPages.Member(member.fullName(), member.title(), member.cleanName(), member.role(),
                            start != null ? start : member.start(), member.end(), cv, detailUrl));
                } catch (Exception e) {
                    enriched.add(member);
                }
            } else {
                enriched.add(member);
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
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.GERMAN);
        Map<String, String> months = Map.ofEntries(
                Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
                Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
                Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
                Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
                Map.entry("dezember", "12"));
        Matcher m = Pattern.compile("(?:(\\d{1,2})\\.\\s*)?([a-zäöü]+)\\s*(\\d{4})").matcher(s);
        if (m.find()) {
            int day = m.group(1) != null ? Integer.parseInt(m.group(1)) : 1;
            String month = months.getOrDefault(m.group(2), "01");
            return LocalDate.parse(String.format("%s-%s-%02d", m.group(3), month, day));
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
        String message = persons + " personnes lues sur " + (outcomes.size() - failures.size()) + " pages de présidence"
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

    // ------------------------------------------------------------------ HTTP

    static Web http() {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        return new Web() {
            @Override
            public String text(String url, long delayMs) throws IOException {
                return new String(bytes(url, delayMs), StandardCharsets.UTF_8);
            }

            @Override
            public byte[] bytes(String url, long delayMs) throws IOException {
                IOException last = null;
                for (int attempt = 1; attempt <= 2; attempt++) {
                    try {
                        Thread.sleep(delayMs * attempt);
                        HttpResponse<byte[]> resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
                                .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                        if (resp.statusCode() == 200) {
                            return resp.body();
                        }
                        last = new IOException("HTTP " + resp.statusCode() + " pour " + url);
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
            }
        };
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        Config config = config(p);
        long delay = parseLong(p.get("delayMs"), 1000);
        Web web = http();
        List<Outcome> outcomes = readAll(config, url -> web.text(url, delay), null);
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (Outcome o : outcomes) {
            for (FederalPages.Member m : o.members()) {
                records.add(new SourceRecord(ids.buildEntityId(o.court().key(), m.cleanName()), "Person", m.cleanName(), m.role()));
            }
        }
        if (!"false".equalsIgnoreCase(p.get("planJudges"))) {
            List<NamedJudges.NamedJudge> named = new ArrayList<>(knownNames(config));
            named.addAll(readNameSources(config, web, delay, null, new ArrayList<>()));
            for (PlanOutcome o : readPlans(config, web, null, named, 0)) {
                for (JudgeRoster.Judge j : o.judges()) {
                    records.add(new SourceRecord(ids.buildEntityId(o.court().key(), j.fullName()), "Person", j.fullName(),
                            "Richter am " + o.court().name()));
                }
            }
        }
        return records;
    }
}
