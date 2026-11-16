package com.swiftcheck.aml.plugins.depep.defencesecretaries;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
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
import java.util.zip.GZIPInputStream;

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
 * <b>de_pep_defence_secretaries</b> - Secrétaires d'État du ministère fédéral de la Défense (BMVg).
 *
 * <p>Cadre réglementaire :
 * <ul>
 *   <li>Directive (UE) 2015/849 art. 3 pt 9 a) : Secrétaires d'État</li>
 *   <li>GwG § 1 Abs. 12 S. 2 Nr. 1 : Classe A.1</li>
 * </ul>
 *
 * <p>Explore le hub officiel <a href="https://www.bmvg.de/de/ministerium/staatssekretaere">/staatssekretaere</a>
 * et extrait dynamiquement les profils enfants :
 * <ul>
 *   <li>Secrétaires d'État fonctionnaires : <i>Staatssekretär im Bundesministerium der Verteidigung</i></li>
 *   <li>Secrétaires d'État parlementaires : <i>Parlamentarischer Staatssekretär beim Bundesminister der Verteidigung</i></li>
 * </ul>
 *
 * <p>Fonctions en allemand non traduites. Fail-safe en cas d'indisponibilité ponctuelle d'un profil.
 */
@Extension
public class DePepDefenceSecretariesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepDefenceSecretariesParser.class);

    public static final String ID_PREFIX = "de-pep-defence-secretaries";
    public static final String TOPIC = "role.pep";
    public static final String DEFAULT_HUB_URL = "https://www.bmvg.de/de/ministerium/staatssekretaere";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Regex
    private static final Pattern CHILD_LINK = Pattern.compile("(?si)<a[^>]+href=[\"']([^\"']*(?:/staatssekretaere/[a-z0-9\\-]+))[\"']");
    private static final Pattern OG_TITLE = Pattern.compile("(?si)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']");
    private static final Pattern OG_IMAGE = Pattern.compile("(?si)<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']");
    private static final Pattern OG_DESC = Pattern.compile("(?si)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']");
    private static final Pattern TABLE_BLOCK = Pattern.compile("(?si)<table[^>]*>(.*?)</table>");
    private static final Pattern ROW_BLOCK = Pattern.compile("(?si)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL_BLOCK = Pattern.compile("(?si)<t[dh][^>]*>(.*?)</t[dh]>");

    public record SecretaryProfile(
            String slug,
            String name,
            String title,
            String firstName,
            String lastName,
            String positionName,
            String startDate,
            String status,
            String sourceUrl,
            String photoUrl,
            String notes
    ) {}

    public record CrawlResult(
            List<SecretaryProfile> profiles,
            List<String> brokenSources,
            List<String> warnings
    ) {}

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> params = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, params);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);

        for (SecretaryProfile sp : crawl.profiles()) {
            writeProfile(sp, dataset);
        }

        dataset.writeNdjsonFile(ctx.outputFile());

        StringBuilder msg = new StringBuilder();
        msg.append(crawl.profiles().size()).append(" secrétaires d'État du BMVg extraits.");
        if (!crawl.brokenSources().isEmpty()) {
            msg.append(" [ALERT SOURCE_BROKEN: ").append(String.join(", ", crawl.brokenSources())).append("]");
        }
        if (!crawl.warnings().isEmpty()) {
            msg.append(" [ALERT STRUCTURE_CHANGED: ").append(String.join(", ", crawl.warnings())).append("]");
        }

        return dataset.toConversionResult().withMessage(msg.toString());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> params = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, params);

        List<SourceRecord> records = new ArrayList<>();
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);

        for (SecretaryProfile sp : crawl.profiles()) {
            records.add(new SourceRecord(
                    ids.buildEntityId("person", sp.slug()),
                    "Person",
                    sp.name(),
                    sp.sourceUrl()
            ));
        }

        return records;
    }

    public CrawlResult crawl(ParserContext ctx, Map<String, String> params) {
        List<SecretaryProfile> profiles = new ArrayList<>();
        List<String> brokenSources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (ctx.rawDir() != null) {
            try {
                Files.createDirectories(ctx.rawDir());
            } catch (IOException e) {
                log.warn("Impossible de créer rawDir {}: {}", ctx.rawDir(), e.getMessage());
            }
        }

        Path input = ctx.inputFile();
        Path testDir = null;
        if (input != null) {
            if (Files.isDirectory(input)) {
                testDir = input;
            } else if (Files.isRegularFile(input)) {
                testDir = input.getParent();
            }
        }

        // Test mode / Offline execution
        if (testDir != null && Files.exists(testDir.resolve("hub.html"))) {
            try {
                String hubHtml = Files.readString(testDir.resolve("hub.html"), StandardCharsets.UTF_8);
                if (ctx.rawDir() != null) {
                    Files.writeString(ctx.rawDir().resolve("hub.html"), hubHtml, StandardCharsets.UTF_8);
                }
                Set<String> slugs = extractChildSlugs(hubHtml);
                for (String slug : slugs) {
                    Path childFile = testDir.resolve(slug + ".html");
                    if (Files.exists(childFile)) {
                        String childHtml = Files.readString(childFile, StandardCharsets.UTF_8);
                        if (ctx.rawDir() != null) {
                            Files.writeString(ctx.rawDir().resolve(slug + ".html"), childHtml, StandardCharsets.UTF_8);
                        }
                        SecretaryProfile profile = parseProfileHtml(slug, childHtml, "https://www.bmvg.de/de/ministerium/staatssekretaere/" + slug);
                        profiles.add(profile);
                    } else {
                        warnings.add("Fichier test manquant pour le profil : " + slug);
                    }
                }
            } catch (Exception e) {
                brokenSources.add("testDir: " + e.getMessage());
            }
            return new CrawlResult(profiles, brokenSources, warnings);
        }

        // Live network crawl
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        String hubUrl = params.getOrDefault("hubUrl", DEFAULT_HUB_URL);
        String hubHtml;
        try {
            hubHtml = fetchSafe(client, hubUrl);
            if (hubHtml == null) {
                brokenSources.add("SOURCE_BROKEN: " + hubUrl);
                return new CrawlResult(profiles, brokenSources, warnings);
            }
            if (ctx.rawDir() != null) {
                Files.writeString(ctx.rawDir().resolve("hub.html"), hubHtml, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            brokenSources.add("SOURCE_BROKEN: " + hubUrl + " (" + e.getMessage() + ")");
            return new CrawlResult(profiles, brokenSources, warnings);
        }

        Set<String> childUrls = extractChildUrls(hubHtml);
        if (childUrls.isEmpty()) {
            warnings.add("STRUCTURE_CHANGED: Aucun profil enfant découvert sur " + hubUrl);
        }

        int childIndex = 0;
        for (String childUrl : childUrls) {
            String slug = extractSlugFromUrl(childUrl);
            try {
                // Rate-limiting pause
                Thread.sleep(300);
                String childHtml = fetchSafe(client, childUrl);
                if (childHtml == null) {
                    brokenSources.add("SOURCE_BROKEN: " + childUrl);
                    continue;
                }
                if (ctx.rawDir() != null) {
                    childIndex++;
                    String fileName = slug != null ? slug + ".html" : String.format("secretary_%02d.html", childIndex);
                    Files.writeString(ctx.rawDir().resolve(fileName), childHtml, StandardCharsets.UTF_8);
                }
                SecretaryProfile p = parseProfileHtml(slug, childHtml, childUrl);
                profiles.add(p);
            } catch (Exception e) {
                brokenSources.add("SOURCE_BROKEN: " + childUrl + " (" + e.getMessage() + ")");
            }
        }

        return new CrawlResult(profiles, brokenSources, warnings);
    }

    public static Set<String> extractChildSlugs(String hubHtml) {
        Set<String> slugs = new LinkedHashSet<>();
        Matcher m = CHILD_LINK.matcher(hubHtml);
        while (m.find()) {
            String link = m.group(1);
            String slug = extractSlugFromUrl(link);
            if (slug != null && !slug.equals("staatssekretaere")) {
                slugs.add(slug);
            }
        }
        return slugs;
    }

    public static Set<String> extractChildUrls(String hubHtml) {
        Set<String> urls = new LinkedHashSet<>();
        Matcher m = CHILD_LINK.matcher(hubHtml);
        while (m.find()) {
            String link = m.group(1);
            String slug = extractSlugFromUrl(link);
            if (slug != null && !slug.equals("staatssekretaere")) {
                String fullUrl = link.startsWith("http") ? link : "https://www.bmvg.de" + link;
                urls.add(fullUrl);
            }
        }
        return urls;
    }

    public static String extractSlugFromUrl(String url) {
        if (url == null) return null;
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            String candidate = url.substring(lastSlash + 1);
            int q = candidate.indexOf('?');
            if (q >= 0) candidate = candidate.substring(0, q);
            int h = candidate.indexOf('#');
            if (h >= 0) candidate = candidate.substring(0, h);
            return candidate.trim();
        }
        return null;
    }

    public static SecretaryProfile parseProfileHtml(String slug, String html, String pageUrl) {
        String rawName = null;
        Matcher mTitle = OG_TITLE.matcher(html);
        if (mTitle.find()) {
            rawName = cleanText(mTitle.group(1));
        }
        if (rawName == null || rawName.isBlank() || rawName.equalsIgnoreCase("Staatssekretäre")) {
            Matcher mH1 = Pattern.compile("(?si)<h1[^>]*>(.*?)</h1>").matcher(html);
            if (mH1.find()) {
                rawName = cleanText(mH1.group(1));
            }
        }
        if (rawName == null || rawName.isBlank()) {
            rawName = slug.replace('-', ' ');
        }

        String title = null;
        String cleanName = rawName;
        if (rawName.startsWith("Dr. ") || rawName.contains(" Dr. ")) {
            title = "Dr.";
            cleanName = rawName.replaceAll("(?i)\\bDr\\.\\s*", "").trim();
        } else if (rawName.startsWith("Prof. ") || rawName.contains(" Prof. ")) {
            title = "Prof.";
            cleanName = rawName.replaceAll("(?i)\\bProf\\.\\s*", "").trim();
        }

        String[] parts = cleanName.split("\\s+", 2);
        String firstName = parts[0];
        String lastName = parts.length > 1 ? parts[1] : "";

        String photoUrl = null;
        Matcher mImg = OG_IMAGE.matcher(html);
        if (mImg.find()) {
            photoUrl = mImg.group(1);
        }

        // Table & role parsing
        String positionName = "Staatssekretär im Bundesministerium der Verteidigung";
        String startDate = null;
        StringBuilder notes = new StringBuilder();

        Matcher mTable = TABLE_BLOCK.matcher(html);
        if (mTable.find()) {
            Matcher mRow = ROW_BLOCK.matcher(mTable.group(1));
            while (mRow.find()) {
                List<String> cells = getCells(mRow.group(1));
                if (cells.size() >= 2 && !cells.get(0).equalsIgnoreCase("Zeitraum")) {
                    String time = cells.get(0);
                    String task = cells.get(1);

                    // Check for current role definition in table
                    if (task.toLowerCase(Locale.GERMAN).contains("staatssekretär") || task.toLowerCase(Locale.GERMAN).contains("staatssekretaer")) {
                        positionName = task;
                        if (startDate == null) {
                            startDate = parseStartDate(time);
                        }
                    }
                    notes.append("\n- ").append(time).append(" : ").append(task);
                }
            }
        }

        // Fallback role if table did not provide it
        if (positionName.contains("Parlamentarischer Staatssekretär")) {
            positionName = "Parlamentarischer Staatssekretär beim Bundesminister der Verteidigung";
        } else if (positionName.contains("Staatssekretär")) {
            positionName = "Staatssekretär im Bundesministerium der Verteidigung";
        }

        if (startDate == null) {
            Matcher mDesc = OG_DESC.matcher(html);
            if (mDesc.find()) {
                startDate = parseStartDate(mDesc.group(1));
            }
        }

        String notesText = "Position: " + positionName + " (GwG § 1 Abs. 12 S. 2 Nr. 1 - Class A.1)"
                + (notes.length() > 0 ? "\n" + notes.toString() : "");

        return new SecretaryProfile(
                slug,
                rawName,
                title,
                firstName,
                lastName,
                positionName,
                startDate,
                "current",
                pageUrl,
                photoUrl,
                notesText
        );
    }

    private static void writeProfile(SecretaryProfile sp, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", sp.slug());
        person.addValue("name", sp.name());
        if (sp.title() != null) person.addValue("title", sp.title());
        if (sp.firstName() != null && !sp.firstName().isBlank()) person.addValue("firstName", sp.firstName());
        if (sp.lastName() != null && !sp.lastName().isBlank()) person.addValue("lastName", sp.lastName());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", sp.positionName());
        person.addValue("sourceUrl", sp.sourceUrl());

        if (sp.notes() != null) {
            person.addValue("summary", sp.notes());
            person.addValue("notes", sp.notes());
        }

        dataset.addEntity(person);

        if (sp.photoUrl() != null && !sp.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), sp.photoUrl(), sp.sourceUrl(), "Amtliches Foto (bmvg.de)");
        }

        // Position entity
        boolean isParlamentarisch = sp.positionName().toLowerCase(Locale.GERMAN).contains("parlamentarisch");
        String posSlug = isParlamentarisch ? "parlamentarischer-staatssekretaer-bmvg" : "staatssekretaer-bmvg";

        FtmEntity position = dataset.newEntity("Position", posSlug);
        position.addValue("name", sp.positionName());
        position.addValue("country", "de");
        position.addValue("organization", "Bundesministerium der Verteidigung");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        // Occupancy entity
        FtmEntity occupancy = dataset.newEntity("Occupancy", sp.slug(), "sec");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", sp.status());
        occupancy.addValue("description", sp.positionName() + " (GwG § 1 Abs. 12 S. 2 Nr. 1 - Class A.1)");
        occupancy.addValue("sourceUrl", sp.sourceUrl());
        if (sp.startDate() != null) occupancy.addValue("startDate", sp.startDate());

        dataset.addEntity(occupancy);
    }

    private static String fetchSafe(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "gzip, deflate")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            log.warn("HTTP {} on {}", resp.statusCode(), url);
            return null;
        }

        String encoding = resp.headers().firstValue("Content-Encoding").orElse("");
        InputStream in = resp.body();
        if ("gzip".equalsIgnoreCase(encoding)) {
            in = new GZIPInputStream(in);
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    public static String cleanText(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<String> getCells(String rowHtml) {
        List<String> cells = new ArrayList<>();
        Matcher m = CELL_BLOCK.matcher(rowHtml);
        while (m.find()) {
            cells.add(cleanText(m.group(1)));
        }
        return cells;
    }

    public static String parseStartDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.GERMAN);

        // format dd.MM.yyyy first (e.g. 17.03.2023)
        Matcher mFull = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})").matcher(s);
        if (mFull.find()) {
            int day = Integer.parseInt(mFull.group(1));
            int month = Integer.parseInt(mFull.group(2));
            int year = Integer.parseInt(mFull.group(3));
            return String.format("%04d-%02d-%02d", year, month, day);
        }

        // format MM/yyyy or MM.yyyy (e.g. 05/2025, 03.2023)
        Matcher mMonthYear = Pattern.compile("(\\d{1,2})[./](\\d{4})").matcher(s);
        if (mMonthYear.find()) {
            int month = Integer.parseInt(mMonthYear.group(1));
            int year = Integer.parseInt(mMonthYear.group(2));
            return String.format("%04d-%02d-01", year, month);
        }

        // format year only (e.g. 2025)
        Matcher mYear = Pattern.compile("\\b(20\\d{2})\\b").matcher(s);
        if (mYear.find()) {
            return mYear.group(1) + "-01-01";
        }

        return null;
    }

    public static String slugify(String text) {
        if (text == null || text.isBlank()) return "unknown";
        String s = text.trim().toLowerCase(Locale.GERMAN).replace("ß", "ss");
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = s.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "item" : s;
    }
}
