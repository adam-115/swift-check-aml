package com.swiftcheck.aml.plugins.depep.bundesbank;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_bundesbank</b> – Directoire, dirigeants et Hauptverwaltungen de la Banque fédérale d'Allemagne (Deutsche Bundesbank).
 * <p>
 * Catégorie <b>DE-f</b> (§ 1 (12) f) GwG ; liste JO C/2023/724).
 * Couvre le Président, la Vice-Présidente, les membres du directoire (Vorstand), les directeurs de domaines (Bereiche / COO)
 * et les présidents des 9 Hauptverwaltungen régionales.
 * Explore la page racine /vorstand, découvre les sous-liens, enrichit avec le CV et la date de prise de fonction,
 * et signale les alertes SOURCE_BROKEN et STRUCTURE_CHANGED.
 */
@Extension
public class DePepBundesbankParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepBundesbankParser.class);

    static final String ID_PREFIX = "de-pep-bundesbank";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_URL = "https://www.bundesbank.de/de/bundesbank/organisation/vorstand";
    static final String SITE = "https://www.bundesbank.de";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Bundesbank)";

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?si)<(?:div|li|article)[^>]*class=\"[^\"]*(?:teaser|card|member|portrait|person)[^\"]*\"[^>]*>(.*?)(?=<div[^>]*class=\"[^\"]*(?:teaser|card|member|portrait|person)|<li[^>]*class=\"[^\"]*(?:teaser|card|member|portrait|person)|</(?:body|ul|main)>|$)");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "(?si)<h[2-4][^>]*>\\s*(?:<a[^>]*href=\"([^\"]*)\"[^>]*>)?\\s*([^<]+?)\\s*(?:</a>)?\\s*</h[2-4]>");

    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(?si)<(?:p|div|span)[^>]*class=\"[^\"]*(?:role|text|subline|subtitle|function|position|description)[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</(?:p|div|span)>");

    private static final Pattern IMG_PATTERN = Pattern.compile(
            "(?si)<img[^>]*\\ssrc=\"([^\"]+)\"[^>]*>");

    private static final Pattern VORSTAND_H2_BLOCK = Pattern.compile(
            "(?si)<h[2-4][^>]*>\\s*<a\\s+[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>\\s*</h[2-4]>\\s*(?:<p[^>]*>([\\s\\S]*?)</p>)?");

    private static final Pattern VORSTAND_LINK = Pattern.compile(
            "(?si)<a\\s+[^>]*href=\"(/de/bundesbank/organisation/(?:vorstand|coo-und-bereiche|hauptverwaltungen)/[^\"]+)\"[^>]*>([\\s\\S]*?)</a>");

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    private static final Pattern SEIT_DATE = Pattern.compile("(?i)Seit(?:\\s+dem)?\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}\\.\\d{2}\\.\\d{4}|[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{4})");

    private static final Map<String, String> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
            Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
            Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
            Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
            Map.entry("dezember", "12")
    );

    record Member(String fullName, String title, String cleanName, String role, String photoUrl, String profileUrl,
                  String startDate, String cvSummary) {

        Member withDetails(String newStartDate, String newCv) {
            return new Member(fullName, title, cleanName, role, photoUrl, profileUrl,
                    newStartDate != null ? newStartDate : startDate,
                    newCv != null ? newCv : cvSummary);
        }
    }

    record CrawlResult(Map<String, Member> members, List<String> brokenSources, List<String> structureWarnings) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, p);

        if (crawl.members().isEmpty()) {
            throw new IllegalStateException("Aucun membre trouvé pour la Deutsche Bundesbank.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(new ArrayList<>(crawl.members().values()), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        StringBuilder msg = new StringBuilder();
        msg.append(crawl.members().size()).append(" membres du directoire et dirigeants de la Deutsche Bundesbank extraits.");
        if (!crawl.brokenSources().isEmpty()) {
            msg.append(" [ALERT SOURCE_BROKEN: ").append(crawl.brokenSources().size()).append(" source(s) en échec: ")
               .append(String.join(", ", crawl.brokenSources())).append("]");
        }
        if (!crawl.structureWarnings().isEmpty()) {
            msg.append(" [ALERT STRUCTURE_CHANGED: ").append(String.join(", ", crawl.structureWarnings())).append("]");
        }

        return dataset.toConversionResult().withMessage(msg.toString());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, p);
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (Member m : crawl.members().values()) {
            records.add(new SourceRecord(
                    ids.buildEntityId(m.cleanName()),
                    "Person",
                    m.fullName(),
                    m.role() != null ? m.role() : "Mitglied des Vorstands"
            ));
        }
        return records;
    }

    CrawlResult crawl(ParserContext ctx, Map<String, String> parameters) {
        this.rawDir = ctx.rawDir();
        Map<String, Member> membersByName = new LinkedHashMap<>();
        List<String> brokenSources = new ArrayList<>();
        List<String> structureWarnings = new ArrayList<>();

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        // 1. Fichier d'entrée local (tests unitaires ou snapshot)
        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            try {
                String html = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
                List<Member> list = extractMembers(html);
                if (list.isEmpty()) {
                    structureWarnings.add("0 membre extrait du fichier d'entrée");
                }
                for (Member m : list) {
                    membersByName.put(m.cleanName().toLowerCase(Locale.ROOT), m);
                }
                return new CrawlResult(membersByName, brokenSources, structureWarnings);
            } catch (Exception e) {
                brokenSources.add("inputFile: " + e.getMessage());
                return new CrawlResult(membersByName, brokenSources, structureWarnings);
            }
        }

        // 2. URLs cibles depuis parameters (les 35 URLs de la Story 1 ou DEFAULT_URL)
        List<String> targetUrls = resolveTargetUrls(parameters);

        for (String rawUrl : targetUrls) {
            String cleanUrl = rawUrl.trim();
            if (cleanUrl.isEmpty()) continue;

            String html;
            try {
                html = fetchSafe(client, cleanUrl);
                if (html == null) {
                    brokenSources.add(cleanUrl);
                    continue;
                }
            } catch (Exception e) {
                log.warn("SOURCE_BROKEN sur {}: {}", cleanUrl, e.getMessage());
                brokenSources.add(cleanUrl);
                continue;
            }

            // A. Page de synthèse / liste (vorstand, coo-und-bereiche, hauptverwaltungen)
            if (cleanUrl.endsWith("/vorstand") || cleanUrl.endsWith("/coo-und-bereiche") || cleanUrl.endsWith("/hauptverwaltungen")) {
                List<Member> list = extractMembers(html);
                if (list.isEmpty()) {
                    structureWarnings.add("Structure modifiée sur " + cleanUrl);
                } else {
                    for (Member m : list) {
                        membersByName.putIfAbsent(m.cleanName().toLowerCase(Locale.ROOT), m);
                    }
                }
            } else {
                // B. Fiche individuelle directe
                Member detail = parseDetailPage(cleanUrl, html);
                if (detail != null) {
                    String key = detail.cleanName().toLowerCase(Locale.ROOT);
                    Member existing = membersByName.get(key);
                    if (existing != null) {
                        membersByName.put(key, existing.withDetails(detail.startDate(), detail.cvSummary()));
                    } else {
                        membersByName.put(key, detail);
                    }
                } else {
                    structureWarnings.add("Structure modifiée sur la fiche " + cleanUrl);
                }
            }
        }

        // 3. Découverte / enrichissement récursif des sous-liens
        for (Map.Entry<String, Member> entry : new ArrayList<>(membersByName.entrySet())) {
            Member m = entry.getValue();
            if (m.cvSummary() == null && m.profileUrl() != null) {
                String fullUrl = m.profileUrl().startsWith("http") ? m.profileUrl() : SITE + m.profileUrl();
                try {
                    String detailHtml = fetchSafe(client, fullUrl);
                    if (detailHtml != null) {
                        Member detail = parseDetailPage(fullUrl, detailHtml);
                        if (detail != null) {
                            membersByName.put(entry.getKey(), m.withDetails(detail.startDate(), detail.cvSummary()));
                        }
                    }
                } catch (Exception e) {
                    log.debug("Impossible d'enrichir le profil {} depuis {}: {}", m.cleanName(), fullUrl, e.getMessage());
                }
            }
        }

        return new CrawlResult(membersByName, brokenSources, structureWarnings);
    }

    static List<String> resolveTargetUrls(Map<String, String> parameters) {
        List<String> list = new ArrayList<>();
        String urlsParam = parameters.get("urls");
        if (urlsParam != null && !urlsParam.isBlank()) {
            if (urlsParam.trim().startsWith("[")) {
                Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(urlsParam);
                while (m.find()) {
                    list.add(m.group(1));
                }
            } else {
                for (String part : urlsParam.split("[\r\n,]+")) {
                    if (!part.isBlank()) list.add(part.trim());
                }
            }
        }
        if (list.isEmpty()) {
            list.add(parameters.getOrDefault("url", DEFAULT_URL).trim());
        }
        return list;
    }

    /** Dossier des réponses brutes de l'exécution en cours : le core les signe et les archive (preuve des données). */
    private java.nio.file.Path rawDir;

    private String fetchSafe(HttpClient client, String rawUrl) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            log.warn("URL malformée (SOURCE_BROKEN) : {}", rawUrl);
            return null;
        }

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .GET().build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = resp.body();
        if (resp.statusCode() >= 400 || com.swiftcheck.aml.sdk.StealthBrowserFetcher.isChallenge(resp)) {
            // Depuis un serveur de centre de données (VPS), les sites fédéraux renvoient vers un défi anti-robot
            // (/.enodia/challenge) : le service navigateur (crawler-stealth) le passe et rapporte la page.
            log.warn("HTTP {} pour {} ({}) : essai par le service navigateur", resp.statusCode(), rawUrl, resp.uri());
            try {
                body = new String(com.swiftcheck.aml.sdk.StealthBrowserFetcher.downloadBytes(rawUrl), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("SOURCE_BROKEN {} : {}", rawUrl, e.getMessage());
                return null;
            }
        }
        // Chaque page reçue est déposée dans rawDir ; sans fichier dans rawDir, l'exécution est bloquée.
        if (rawDir != null) {
            Files.createDirectories(rawDir);
            Files.writeString(rawDir.resolve(rawFileName(uri)), body, StandardCharsets.UTF_8);
        }
        return body;
    }

    /** Nom du fichier brut d'une page : chemin et requête de l'URL (150 caractères au plus). */
    static String rawFileName(URI uri) {
        String path = (uri.getPath() == null ? "" : uri.getPath()) + (uri.getQuery() == null ? "" : "-" + uri.getQuery());
        String name = path.replaceAll("\\.html$", "").replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        if (name.length() > 150) {
            name = name.substring(0, 140) + "-" + Integer.toHexString(path.hashCode());
        }
        return (name.isEmpty() ? "page" : name) + ".html";
    }

    static List<Member> extractMembers(String html) {
        List<Member> members = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();

        // 1. Détection via les blocs h2/h3 de présentation Bundesbank CMS
        Matcher h2Matcher = VORSTAND_H2_BLOCK.matcher(html);
        while (h2Matcher.find()) {
            String profileUrl = h2Matcher.group(1);
            String rawName = h2Matcher.group(2).replaceAll("<[^>]+>", "").trim();
            String roleHtml = h2Matcher.group(3) != null ? h2Matcher.group(3).replaceAll("<[^>]+>", " ").trim() : "";

            if (rawName.isBlank() || rawName.equalsIgnoreCase("Vorstand") || rawName.contains("Geschäftsverteilung")) {
                continue;
            }

            String title = null;
            String cleanName = rawName;
            Matcher tm = ACADEMIC_TITLE.matcher(rawName);
            if (tm.find()) {
                title = tm.group(0).trim();
                cleanName = rawName.substring(tm.end()).trim();
            }

            if (!seenNames.add(cleanName.toLowerCase(Locale.ROOT))) {
                continue;
            }

            String role = determineRole(rawName, roleHtml);
            members.add(new Member(rawName, title, cleanName, role, null, profileUrl, null, null));
        }

        // 2. Détection via CARD_PATTERN classique si pas trouvé en h2
        if (members.isEmpty()) {
            Matcher cardMatcher = CARD_PATTERN.matcher(html);
            while (cardMatcher.find()) {
                String card = cardMatcher.group(1);
                Matcher headMatcher = HEADING_PATTERN.matcher(card);
                if (!headMatcher.find()) {
                    continue;
                }
                String profileUrl = headMatcher.group(1);
                String rawName = headMatcher.group(2).replaceAll("<[^>]+>", "").trim();
                if (rawName.isBlank() || rawName.equalsIgnoreCase("Vorstand") || rawName.contains("Bundesbank") && !rawName.contains("Dr.") && !rawName.contains(" ")) {
                    continue;
                }

                String title = null;
                String cleanName = rawName;
                Matcher titleMatcher = ACADEMIC_TITLE.matcher(rawName);
                if (titleMatcher.find()) {
                    title = titleMatcher.group(0).trim();
                    cleanName = rawName.substring(titleMatcher.end()).trim();
                }

                if (!seenNames.add(cleanName.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                String role = null;
                Matcher roleMatcher = ROLE_PATTERN.matcher(card);
                if (roleMatcher.find()) {
                    role = roleMatcher.group(1).trim();
                }
                if (role == null) {
                    role = determineRole(rawName, card);
                }

                String photoUrl = null;
                Matcher imgMatcher = IMG_PATTERN.matcher(card);
                if (imgMatcher.find()) {
                    photoUrl = imgMatcher.group(1);
                    if (photoUrl.startsWith("/")) {
                        photoUrl = SITE + photoUrl;
                    }
                }

                members.add(new Member(rawName, title, cleanName, role, photoUrl, profileUrl, null, null));
            }
        }

        // 3. Fallback direct via les liens du Vorstand
        if (members.isEmpty()) {
            Matcher linkMatcher = VORSTAND_LINK.matcher(html);
            while (linkMatcher.find()) {
                String profileUrl = linkMatcher.group(1);
                String rawName = linkMatcher.group(2).replaceAll("<[^>]+>", "").trim();
                if (rawName.isBlank() || rawName.equalsIgnoreCase("Vorstand") || rawName.contains("Geschäftsverteilung")
                        || rawName.length() < 3 || rawName.contains("Downloads")) {
                    continue;
                }
                String title = null;
                String cleanName = rawName;
                Matcher tm = ACADEMIC_TITLE.matcher(rawName);
                if (tm.find()) {
                    title = tm.group(0).trim();
                    cleanName = rawName.substring(tm.end()).trim();
                }
                if (seenNames.add(cleanName.toLowerCase(Locale.ROOT))) {
                    String role = determineRole(rawName, "");
                    members.add(new Member(rawName, title, cleanName, role, null, profileUrl, null, null));
                }
            }
        }

        return members;
    }

    static Member parseDetailPage(String url, String html) {
        String cleanHtml = html.replaceAll("(?si)<script[^>]*>.*?</script>", "")
                               .replaceAll("(?si)<style[^>]*>.*?</style>", "");

        // Titre / Nom
        String rawName = null;
        Matcher h1Matcher = Pattern.compile("(?si)<h1[^>]*>\\s*([^<]+?)\\s*</h1>").matcher(cleanHtml);
        while (h1Matcher.find()) {
            String text = h1Matcher.group(1).trim();
            if (!text.equalsIgnoreCase("Service Navigation") && !text.equalsIgnoreCase("Logo") && !text.contains("nicht gefunden") && !text.isBlank()) {
                rawName = text;
                break;
            }
        }
        if (rawName == null) {
            Matcher docTitle = Pattern.compile("(?i)setDocumentTitle[',\\s]+/[^/]+/[^/]+/[^/]+/(?:Vorstand/)?([^'\\[\\]]+)").matcher(cleanHtml);
            if (docTitle.find()) {
                rawName = docTitle.group(1).trim();
            }
        }
        if (rawName == null) {
            rawName = extractSlug(url).replace('-', ' ');
        }

        String title = null;
        String cleanName = rawName;
        Matcher tm = ACADEMIC_TITLE.matcher(rawName);
        if (tm.find()) {
            title = tm.group(0).trim();
            cleanName = rawName.substring(tm.end()).trim();
        }

        // Date de début (Seit ...)
        String startDate = null;
        Matcher seitM = SEIT_DATE.matcher(cleanHtml);
        if (seitM.find()) {
            startDate = parseIsoDate(seitM.group(1));
        }

        // CV / Résumé biographique
        String cvSummary = null;
        Matcher descM = Pattern.compile("(?i)<meta\\s+name=[\"']description[\"']\\s+content=[\"']([^\"']+)[\"']").matcher(cleanHtml);
        if (descM.find()) {
            cvSummary = descM.group(1).trim();
        }

        String role = determineRole(rawName, cleanHtml);
        return new Member(rawName, title, cleanName, role, null, url, startDate, cvSummary);
    }

    static String extractSlug(String url) {
        Matcher m = Pattern.compile("/([^/?#]+)(?:\\.[a-z]+)?(?:\\?.*)?$").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    static String parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.GERMAN);

        // Format Mois Année (ex: "Januar 2022")
        Matcher mMonthYear = Pattern.compile("([a-zäöü]+)\\s*(\\d{4})").matcher(s);
        if (mMonthYear.find()) {
            String month = GERMAN_MONTHS.getOrDefault(mMonthYear.group(1), "01");
            return mMonthYear.group(2) + "-" + month + "-01";
        }

        // Format DD.MM.YYYY
        if (s.matches("^\\d{1,2}\\.\\d{2}\\.\\d{4}$")) {
            String[] p = s.split("\\.");
            return String.format("%s-%02d-%02d", p[2], Integer.parseInt(p[1]), Integer.parseInt(p[0]));
        }

        // Format YYYY
        if (s.matches("^\\d{4}$")) {
            return s + "-01-01";
        }

        return null;
    }

    private static String determineRole(String name, String context) {
        String combined = (name + " " + context).toLowerCase(Locale.ROOT);
        if (combined.contains("präsidentin") || combined.contains("praesidentin")) {
            return combined.contains("vize") ? "Vizepräsidentin der Deutschen Bundesbank" : "Präsidentin der Deutschen Bundesbank";
        }
        if (combined.contains("präsident") || combined.contains("praesident")) {
            return combined.contains("vize") ? "Vizepräsident der Deutschen Bundesbank" : "Präsident der Deutschen Bundesbank";
        }
        if (name.contains("Nagel")) {
            return "Präsident der Deutschen Bundesbank";
        }
        if (name.contains("Mauderer")) {
            return "Vizepräsidentin der Deutschen Bundesbank";
        }
        if (combined.contains("hauptverwaltung")) {
            return "Präsident der Hauptverwaltung der Deutschen Bundesbank";
        }
        if (combined.contains("bereich") || combined.contains("coo")) {
            return "Leiter eines Zentralbereichs / COO der Deutschen Bundesbank";
        }
        return "Mitglied des Vorstands der Deutschen Bundesbank";
    }

    private void convert(List<Member> members, FtmDatasetBuilder dataset) {
        FtmEntity generalPosition = dataset.newEntity("Position", "board-member");
        generalPosition.addValue("name", "Mitglied des Vorstands der Deutschen Bundesbank");
        generalPosition.addValue("country", "de");
        generalPosition.addValue("organization", "Deutsche Bundesbank");
        generalPosition.addValue("topics", "gov.national");
        generalPosition.addValue("topics", "gov.financial");
        dataset.addEntity(generalPosition);

        for (Member m : members) {
            FtmEntity person = dataset.newEntity("Person", m.cleanName());
            person.addValue("name", m.cleanName());
            person.addValue("title", m.title());
            String[] parts = m.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            person.addValue("position", m.role());
            if (m.photoUrl() != null) {
                person.addValue("imageUrl", m.photoUrl());
            }
            if (m.profileUrl() != null) {
                String fullProfile = m.profileUrl().startsWith("http") ? m.profileUrl() : SITE + m.profileUrl();
                person.addValue("sourceUrl", fullProfile);
            }
            if (m.cvSummary() != null) {
                person.addValue("summary", m.cvSummary());
                person.addValue("notes", "Lebenslauf : " + m.cvSummary());
            }
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", m.cleanName(), "vorstand");
            occ.addValue("holder", person.getId());
            occ.addValue("post", generalPosition.getId());
            occ.addValue("status", "current");
            occ.addValue("description", m.role());
            if (m.startDate() != null) {
                occ.addValue("startDate", m.startDate());
            }
            dataset.addEntity(occ);
        }
    }
}
