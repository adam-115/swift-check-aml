package com.swiftcheck.aml.plugins.depep.landgovernments;

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
 * <b>de_pep_land_governments</b> – Ministres-présidents, ministres et secrétaires d'État des 16 Länder allemands.
 * <p>
 * Catégorie <b>DE-a</b> (§ 1 (12) a) GwG ; JO C/2023/724).
 * Explore les 44 URLs des gouvernements régionaux (Baden-Württemberg, Bayern, Berlin, Brandenburg, Bremen,
 * Hamburg, Hessen, Mecklenburg-Vorpommern, Niedersachsen, Nordrhein-Westfalen, Rheinland-Pfalz, Saarland,
 * Sachsen, Sachsen-Anhalt, Schleswig-Holstein, Thüringen).
 * Gère les alertes SOURCE_BROKEN pour les URLs non joignables ou malformées (notamment celles avec espaces) sans planter,
 * et STRUCTURE_CHANGED en cas d'évolution du DOM.
 */
@Extension
public class DePepLandGovernmentsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepLandGovernmentsParser.class);

    static final String ID_PREFIX = "de-pep-land-governments";
    static final String TOPIC = "role.pep";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Gouvernements des Länder)";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile("^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    private static final Pattern SEIT_DATE = Pattern.compile("(?i)Seit(?:\\s+dem)?\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}\\.\\d{2}\\.\\d{4}|[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{4})");

    private static final Map<String, String> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
            Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
            Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
            Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
            Map.entry("dezember", "12")
    );

    record Member(String slug, String landName, String fullName, String title, String cleanName, String role,
                  String pageUrl, String startDate, String cvSummary) {

        Member withDetails(String newStartDate, String newCv) {
            return new Member(slug, landName, fullName, title, cleanName, role, pageUrl,
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

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        for (Member m : crawl.members().values()) {
            write(m, dataset);
        }

        dataset.writeNdjsonFile(ctx.outputFile());

        StringBuilder msg = new StringBuilder();
        msg.append(crawl.members().size()).append(" membres des exécutifs des 16 Länder extraits.");
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
            records.add(new SourceRecord(ids.buildEntityId(m.slug()), "Person", m.cleanName(), m.pageUrl()));
        }
        return records;
    }

    CrawlResult crawl(ParserContext ctx, Map<String, String> parameters) {
        Map<String, Member> membersBySlug = new LinkedHashMap<>();
        List<String> brokenSources = new ArrayList<>();
        List<String> structureWarnings = new ArrayList<>();

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        // 1. Fichier d'entrée local (tests unitaires ou archive)
        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            try {
                String html = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
                List<Member> list = extractFromHtml("local", "Land", html);
                for (Member m : list) {
                    membersBySlug.put(m.slug(), m);
                }
                return new CrawlResult(membersBySlug, brokenSources, structureWarnings);
            } catch (Exception e) {
                brokenSources.add("inputFile: " + e.getMessage());
                return new CrawlResult(membersBySlug, brokenSources, structureWarnings);
            }
        }

        // 2. Traitement des URLs cibles
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

            String landName = detectLand(cleanUrl);
            List<Member> found = extractFromHtml(cleanUrl, landName, html);
            if (found.isEmpty()) {
                // Si l'URL a répondu 200 mais qu'aucun profil n'est extrait (ex: daten.berlin.de/datensaetze ou page sans profil)
                structureWarnings.add("Structure inattendue ou 0 profil sur " + cleanUrl);
            } else {
                for (Member m : found) {
                    Member existing = membersBySlug.get(m.slug());
                    if (existing != null) {
                        membersBySlug.put(m.slug(), existing.withDetails(m.startDate(), m.cvSummary()));
                    } else {
                        membersBySlug.put(m.slug(), m);
                    }
                }
            }
        }

        return new CrawlResult(membersBySlug, brokenSources, structureWarnings);
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
        return list;
    }

    private String fetchSafe(HttpClient client, String rawUrl) throws IOException, InterruptedException {
        // Détection précoce des URLs malformées (ex: contenant des espaces non encodés comme 'schleswig holstein.de')
        if (rawUrl.contains(" ")) {
            log.warn("URL malformée avec espaces (SOURCE_BROKEN) : {}", rawUrl);
            return null;
        }

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

    static List<Member> extractFromHtml(String url, String landName, String html) {
        List<Member> list = new ArrayList<>();

        // 1. Détection via les teasers / cards du cabinet (Baden-Württemberg, Bayern, NRW, etc.)
        Matcher cardM = Pattern.compile("(?si)<(?:div|li|article)[^>]*class=\"[^\"]*(?:teaser|member|person|card|profile|regierungsmitglied)[^\"]*\"[^>]*>(.*?)(?=<div[^>]*class=\"[^\"]*(?:teaser|member|person|card|profile)|<li[^>]*class=\"[^\"]*(?:teaser|member)|$)").matcher(html);
        while (cardM.find()) {
            String block = cardM.group(1);
            Matcher headM = Pattern.compile("(?si)<h[2-4][^>]*>(.*?)</h[2-4]>").matcher(block);
            if (!headM.find()) continue;

            String rawName = Html.text(headM.group(1));
            if (rawName.isBlank() || rawName.length() < 3 || rawName.length() > 60 || rawName.contains("Kabinett") || rawName.contains("Regierung")) {
                continue;
            }

            String role = "Mitglied der Landesregierung";
            Matcher roleM = Pattern.compile("(?si)<(?:p|div|span)[^>]*class=\"[^\"]*(?:role|function|subline|position|amt)[^\"]*\"[^>]*>(.*?)</(?:p|div|span)>").matcher(block);
            if (roleM.find()) {
                role = Html.text(roleM.group(1));
            }

            String title = null;
            String cleanName = rawName;
            Matcher tm = ACADEMIC_TITLE.matcher(rawName);
            if (tm.find()) {
                title = tm.group().trim();
                cleanName = rawName.substring(tm.end()).trim();
            }

            String slug = cleanName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9äöüß]+", "-");
            list.add(new Member(slug, landName, rawName, title, cleanName, role, url, null, null));
        }

        // 2. Si aucun profil trouvé par bloc (ex: page dédiée à une seule personne avec h1)
        if (list.isEmpty()) {
            Matcher h1M = Pattern.compile("(?si)<h1[^>]*>(.*?)</h1>").matcher(html);
            if (h1M.find()) {
                String rawName = Html.text(h1M.group(1));
                if (isValidPersonName(rawName)) {
                    String title = null;
                    String cleanName = rawName;
                    Matcher tm = ACADEMIC_TITLE.matcher(rawName);
                    if (tm.find()) {
                        title = tm.group().trim();
                        cleanName = rawName.substring(tm.end()).trim();
                    }

                    String role = determineRoleFromPage(url, html);
                    String startDate = null;
                    Matcher seitM = SEIT_DATE.matcher(html);
                    if (seitM.find()) {
                        startDate = parseIsoDate(seitM.group(1));
                    }

                    String cv = extractCvText(html);
                    String slug = cleanName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9äöüß]+", "-");
                    list.add(new Member(slug, landName, rawName, title, cleanName, role, url, startDate, cv));
                }
            }
        }

        return list;
    }

    static boolean isValidPersonName(String s) {
        if (s == null || s.isBlank() || s.length() < 3 || s.length() > 60) return false;
        String l = s.toLowerCase(Locale.ROOT);
        return !l.contains("kabinett") && !l.contains("ministerium") && !l.contains("datens")
                && !l.contains("staatskanzlei") && !l.contains("regierung") && !l.contains("seite")
                && !l.contains("senat") && !l.contains("kontakt");
    }

    static String determineRoleFromPage(String url, String html) {
        String combined = (url + " " + html).toLowerCase(Locale.ROOT);
        if (combined.contains("ministerpräsident") || combined.contains("regierender bürgermeister") || combined.contains("präsident des senats")) {
            return "Ministerpräsident(in)";
        }
        if (combined.contains("staatssekretär")) {
            return "Staatssekretär(in)";
        }
        return "Landesminister(in)";
    }

    static String extractCvText(String html) {
        Matcher m = Pattern.compile("(?si)(?:Lebenslauf|Werdegang|Zur Person|Vita).*?<p>(.*?)</p>").matcher(html);
        if (m.find()) {
            String text = Html.text(m.group(1));
            return text.length() > 400 ? text.substring(0, 400) + "..." : text;
        }
        return null;
    }

    static String detectLand(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("baden-wuerttemberg")) return "Baden-Württemberg";
        if (u.contains("bayern")) return "Bayern";
        if (u.contains("berlin")) return "Berlin";
        if (u.contains("brandenburg")) return "Brandenburg";
        if (u.contains("bremen")) return "Bremen";
        if (u.contains("hamburg")) return "Hamburg";
        if (u.contains("hessen")) return "Hessen";
        if (u.contains("government-mv") || u.contains("mecklenburg")) return "Mecklenburg-Vorpommern";
        if (u.contains("niedersachsen") || u.contains("lower-saxony")) return "Niedersachsen";
        if (u.contains("land.nrw") || u.contains("nrw")) return "Nordrhein-Westfalen";
        if (u.contains("rlp") || u.contains("rheinland-pfalz")) return "Rheinland-Pfalz";
        if (u.contains("saarland")) return "Saarland";
        if (u.contains("staatsregierung.sachsen")) return "Sachsen";
        if (u.contains("sachsen-anhalt")) return "Sachsen-Anhalt";
        if (u.contains("schleswig-holstein") || u.contains("schleswig")) return "Schleswig-Holstein";
        if (u.contains("thueringen") || u.contains("thüringen")) return "Thüringen";
        return "Land";
    }

    static String parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.GERMAN);

        Matcher m = Pattern.compile("(\\d{1,2})\\.\\s*([a-zäöü]+)\\s*(\\d{4})").matcher(s);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            String monthName = m.group(2);
            String year = m.group(3);
            String month = GERMAN_MONTHS.getOrDefault(monthName, "01");
            return String.format("%s-%s-%02d", year, month, day);
        }

        if (s.matches("^\\d{1,2}\\.\\d{2}\\.\\d{4}$")) {
            String[] p = s.split("\\.");
            return String.format("%s-%02d-%02d", p[2], Integer.parseInt(p[1]), Integer.parseInt(p[0]));
        }

        if (s.matches("^\\d{4}$")) {
            return s + "-01-01";
        }

        return null;
    }

    private static void write(Member m, FtmDatasetBuilder dataset) {
        boolean isMp = m.role().contains("Ministerpräsident") || m.role().contains("Regierender") || m.role().contains("Präsident des Senats");
        String posLabel = isMp ? "Ministre-président d'un Land (Ministerpräsident)"
                : "Ministre ou secrétaire d'État d'un gouvernement de Land (Landesminister, Staatssekretär)";

        FtmEntity person = dataset.newEntity("Person", m.slug());
        person.addValue("name", m.cleanName());
        person.addValue("title", m.title());
        String[] parts = m.cleanName().split("\\s+", 2);
        person.addValue("firstName", parts[0]);
        if (parts.length > 1) {
            person.addValue("lastName", parts[1]);
        }
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", m.role() + " – " + m.landName());
        person.addValue("sourceUrl", m.pageUrl());
        if (m.cvSummary() != null) {
            person.addValue("summary", m.cvSummary());
            person.addValue("notes", "Lebenslauf : " + m.cvSummary());
        }
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", m.landName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"), isMp ? "mp" : "minister");
        position.addValue("name", posLabel);
        position.addValue("country", "de");
        position.addValue("organization", "Gouvernement du Land " + m.landName());
        position.addValue("topics", "gov.regional");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.slug(), "executive");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", m.role());
        occupancy.addValue("sourceUrl", m.pageUrl());
        if (m.startDate() != null) {
            occupancy.addValue("startDate", m.startDate());
        }
        dataset.addEntity(occupancy);
    }
}

