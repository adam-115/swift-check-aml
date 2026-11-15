package com.swiftcheck.aml.plugins.depep.bafin;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <b>de_pep_bafin</b> – Direction de la BaFin (Bundesanstalt für Finanzdienstleistungsaufsicht) : le président et les
 * directeurs exécutifs (catégorie <b>DE-f</b>, autorités de supervision financière).
 * <p>
 * Explore la page « Direktorium » de bafin.de, découvre les liens vers les fiches individuelles (Lebenslauf tabellarisch / Volltext),
 * extrait la date de début (startDate) et le CV complet, et signale les alertes SOURCE_BROKEN et STRUCTURE_CHANGED.
 */
@Extension
public class DePepBafinParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepBafinParser.class);

    static final String ID_PREFIX = "de-pep-bafin";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_URL = "https://www.bafin.de/DE/die-bafin/ueber-die-bafin/organisation/direktorium/direktorium_node.html";
    static final String SITE = "https://www.bafin.de";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - BaFin)";
    static final String ORGANIZATION = "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)";

    private static final Pattern TEASER_BLOCK = Pattern.compile(
            "(?si)<div class=\"c-teaser[^\"]*\">\\s*<div class=\"c-teaser__wrapper\">(.*?)</div>\\s*</div>");

    private static final Pattern LINK_PATTERN = Pattern.compile("(?i)<a[^>]+href=[\"']([^\"']+)[\"'][^>]*class=[\"']c-teaser__link-main[\"']");
    private static final Pattern HEADLINE_PATTERN = Pattern.compile("(?si)<h3 class=\"c-teaser__headline[^\"]*\">\\s*(.*?)\\s*</h3>");
    private static final Pattern TEXT_PATTERN = Pattern.compile("(?si)<div class=\"c-teaser__text\">\\s*<p>(.*?)</p>");

    private static final Pattern ACADEMIC_TITLE = Pattern.compile("^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?)\\s+)+");

    private static final Pattern SEIT_DATE = Pattern.compile("(?i)Seit(?:\\s+dem)?\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}\\.\\d{2}\\.\\d{4}|[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{4})");

    private static final Map<String, String> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
            Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
            Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
            Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
            Map.entry("dezember", "12")
    );

    record Member(String fullName, String title, String cleanName, String role, String area, String detailUrl,
                  String startDate, String cvSummary) {

        Member withDetails(String newStartDate, String newCv) {
            return new Member(fullName, title, cleanName, role, area, detailUrl,
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
            throw new IllegalStateException("Aucun membre du Direktorium de la BaFin trouvé : la mise en page a probablement changé.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(new ArrayList<>(crawl.members().values()), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        StringBuilder msg = new StringBuilder();
        msg.append(crawl.members().size()).append(" membres du Direktorium de la BaFin extraits.");
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
            records.add(new SourceRecord(ids.buildEntityId(m.cleanName()), "Person", m.cleanName(), m.detailUrl()));
        }
        return records;
    }

    CrawlResult crawl(ParserContext ctx, Map<String, String> parameters) {
        Map<String, Member> membersByName = new LinkedHashMap<>();
        List<String> brokenSources = new ArrayList<>();
        List<String> structureWarnings = new ArrayList<>();

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        // 1. Fichier d'entrée local (tests unitaires ou archivage)
        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            try {
                String html = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
                List<Member> list = members(html);
                for (Member m : list) {
                    membersByName.put(m.cleanName().toLowerCase(Locale.ROOT), m);
                }
                return new CrawlResult(membersByName, brokenSources, structureWarnings);
            } catch (Exception e) {
                brokenSources.add("inputFile: " + e.getMessage());
                return new CrawlResult(membersByName, brokenSources, structureWarnings);
            }
        }

        // 2. URLs cibles depuis parameters (les 7 URLs BaFin ou DEFAULT_URL)
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

            // A. Page d'accueil / liste (direktorium_node.html)
            if (cleanUrl.contains("direktorium_node.html")) {
                try {
                    List<Member> list = members(html);
                    for (Member m : list) {
                        membersByName.putIfAbsent(m.cleanName().toLowerCase(Locale.ROOT), m);
                    }
                } catch (Exception e) {
                    structureWarnings.add("Structure modifiée sur " + cleanUrl + " : " + e.getMessage());
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
            if (m.cvSummary() == null && m.detailUrl() != null && m.detailUrl().startsWith("http")) {
                try {
                    String detailHtml = fetchSafe(client, m.detailUrl());
                    if (detailHtml != null) {
                        Member detail = parseDetailPage(m.detailUrl(), detailHtml);
                        if (detail != null) {
                            membersByName.put(entry.getKey(), m.withDetails(detail.startDate(), detail.cvSummary()));
                        }
                    }
                } catch (Exception e) {
                    log.debug("Impossible d'enrichir le profil {} depuis {}: {}", m.cleanName(), m.detailUrl(), e.getMessage());
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
        if (resp.statusCode() >= 400) {
            log.warn("HTTP {} pour {} (SOURCE_BROKEN)", resp.statusCode(), rawUrl);
            return null;
        }
        return resp.body();
    }

    static List<Member> members(String html) {
        Map<String, Member> byName = new LinkedHashMap<>();

        Matcher teaserMatcher = TEASER_BLOCK.matcher(html);
        while (teaserMatcher.find()) {
            String block = teaserMatcher.group(1);

            Matcher linkM = LINK_PATTERN.matcher(block);
            String detailUrl = null;
            if (linkM.find()) {
                String raw = linkM.group(1);
                detailUrl = raw.startsWith("http") ? raw : SITE + raw;
            }

            Matcher headM = HEADLINE_PATTERN.matcher(block);
            Matcher textM = TEXT_PATTERN.matcher(block);

            if (!headM.find() || !textM.find()) {
                continue;
            }

            String headline = clean(headM.group(1));
            String text = clean(textM.group(1));
            String role;
            String name;

            if ("Präsident".equals(headline) || "Präsidentin".equals(headline)) {
                role = headline + " der BaFin";
                name = text;
                headline = null;
            } else if (text.contains(",") && text.matches(".*,\\s*Exekutivdirektor(in)?$")) {
                name = text.substring(0, text.lastIndexOf(',')).trim();
                role = text.substring(text.lastIndexOf(',') + 1).trim() + " der BaFin";
            } else {
                continue;
            }

            if (name.isBlank() || name.length() > 60 || name.matches(".*\\d.*")) {
                continue;
            }

            String title = null;
            String cleanName = name;
            Matcher tm = ACADEMIC_TITLE.matcher(name);
            if (tm.find()) {
                title = tm.group().trim();
                cleanName = name.substring(tm.end()).trim();
            }

            byName.putIfAbsent(cleanName.toLowerCase(Locale.ROOT),
                    new Member(name, title, cleanName, role, headline, detailUrl, null, null));
        }

        // Fallback avec l'ancien matcher simple si block wrapping pas trouvé
        if (byName.isEmpty()) {
            Matcher m = Pattern.compile("(?s)<h3 class=\"c-teaser__headline[^\"]*\">\\s*(.*?)\\s*</h3>.*?<div class=\"c-teaser__text\">\\s*<p>(.*?)</p>").matcher(html);
            while (m.find()) {
                String headline = clean(m.group(1));
                String text = clean(m.group(2));
                String role;
                String name;
                if ("Präsident".equals(headline) || "Präsidentin".equals(headline)) {
                    role = headline + " der BaFin";
                    name = text;
                    headline = null;
                } else if (text.contains(",") && text.matches(".*,\\s*Exekutivdirektor(in)?$")) {
                    name = text.substring(0, text.lastIndexOf(',')).trim();
                    role = text.substring(text.lastIndexOf(',') + 1).trim() + " der BaFin";
                } else {
                    continue;
                }
                if (name.isBlank() || name.length() > 60 || name.matches(".*\\d.*")) {
                    continue;
                }
                String title = null;
                String cleanName = name;
                Matcher tm = ACADEMIC_TITLE.matcher(name);
                if (tm.find()) {
                    title = tm.group().trim();
                    cleanName = name.substring(tm.end()).trim();
                }
                byName.putIfAbsent(cleanName.toLowerCase(Locale.ROOT),
                        new Member(name, title, cleanName, role, headline, null, null, null));
            }
        }

        List<Member> list = new ArrayList<>(byName.values());
        boolean president = list.stream().anyMatch(x -> x.role().startsWith("Präsident"));
        if (!president || list.size() < 3) {
            throw new IllegalStateException("Page du Direktorium de la BaFin illisible (" + list.size()
                    + " membre(s), président " + (president ? "trouvé" : "absent") + ") : la mise en page a probablement changé.");
        }
        return list;
    }

    static Member parseDetailPage(String url, String html) {
        String cleanHtml = html.replaceAll("(?si)<script[^>]*>.*?</script>", "")
                               .replaceAll("(?si)<style[^>]*>.*?</style>", "");

        // Nom du directeur
        String rawName = null;
        Matcher h1Matcher = Pattern.compile("(?si)<h1[^>]*>\\s*([^<]+?)\\s*</h1>").matcher(cleanHtml);
        while (h1Matcher.find()) {
            String t = clean(h1Matcher.group(1));
            if (!t.equalsIgnoreCase("StartseiteUeberschrift") && !t.contains("nicht gefunden") && !t.isBlank()) {
                rawName = t;
                break;
            }
        }
        if (rawName == null) {
            Matcher titleMatcher = Pattern.compile("(?i)<meta\\s+property=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']").matcher(cleanHtml);
            if (titleMatcher.find()) {
                rawName = clean(titleMatcher.group(1));
            }
        }
        if (rawName == null) {
            rawName = "Mitglied des Direktoriums";
        }

        String title = null;
        String cleanName = rawName;
        Matcher tm = ACADEMIC_TITLE.matcher(rawName);
        if (tm.find()) {
            title = tm.group().trim();
            cleanName = rawName.substring(tm.end()).trim();
        }

        // Date de prise de fonction (Seit ...)
        String startDate = null;
        Matcher seitM = SEIT_DATE.matcher(cleanHtml);
        if (seitM.find()) {
            startDate = parseIsoDate(seitM.group(1));
        }

        // Résumé CV / Biographie
        String cvSummary = null;
        Matcher bodyM = Pattern.compile("(?si)<h2>Lebenslauf(?:\\s*\\([^)]+\\))?</h2>\\s*(.*?)(?=<h2|</div>\\s*</div>|$)").matcher(cleanHtml);
        if (bodyM.find()) {
            cvSummary = clean(bodyM.group(1));
            if (cvSummary.length() > 500) {
                cvSummary = cvSummary.substring(0, 500) + "...";
            }
        }

        String role = url.contains("praesident") ? "Präsident der BaFin" : "Exekutivdirektor der BaFin";
        return new Member(rawName, title, cleanName, role, null, url, startDate, cvSummary);
    }

    static String parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.GERMAN);

        // Format D. MMMM YYYY (ex: "1. August 2021")
        Matcher m = Pattern.compile("(\\d{1,2})\\.\\s*([a-zäöü]+)\\s*(\\d{4})").matcher(s);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            String monthName = m.group(2);
            String year = m.group(3);
            String month = GERMAN_MONTHS.getOrDefault(monthName, "01");
            return String.format("%s-%s-%02d", year, month, day);
        }

        // Format Mois Année (ex: "August 2021")
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

    private static String clean(String s) {
        return s.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    static void convert(List<Member> members, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", "direktorium");
        position.addValue("name", "Mitglied des Direktoriums der BaFin");
        position.addValue("country", "de");
        position.addValue("organization", ORGANIZATION);
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.financial");
        dataset.addEntity(position);

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
            person.addValue("sourceUrl", m.detailUrl() != null ? m.detailUrl() : DEFAULT_URL);
            if (m.cvSummary() != null) {
                person.addValue("summary", m.cvSummary());
                person.addValue("notes", "Lebenslauf : " + m.cvSummary());
            }
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", m.cleanName(), "direktorium");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", m.area() == null ? m.role() : m.role() + " – " + m.area());
            occ.addValue("sourceUrl", m.detailUrl() != null ? m.detailUrl() : DEFAULT_URL);
            if (m.startDate() != null) {
                occ.addValue("startDate", m.startDate());
            }
            dataset.addEntity(occ);
        }
    }
}
