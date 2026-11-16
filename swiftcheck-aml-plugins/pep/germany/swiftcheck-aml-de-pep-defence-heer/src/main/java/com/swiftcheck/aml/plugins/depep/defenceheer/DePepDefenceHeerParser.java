package com.swiftcheck.aml.plugins.depep.defenceheer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * <b>de_pep_defence_heer</b> - Commandement et divisions de l'Armée de terre allemande (Deutsches Heer / Bundeswehr).
 *
 * <p>Cadre réglementaire :
 * <ul>
 *   <li>Directive (UE) 2015/849 art. 3 pt 9 f) : Officiers supérieurs des forces armées</li>
 *   <li>GwG § 1 Abs. 12 S. 2 Nr. 1 : Classe F.1 (Officiers généraux : Generalleutnant, Generalmajor, Brigadegeneral, General)</li>
 * </ul>
 *
 * <p>Sources officielles sur bundeswehr.de :
 * <ul>
 *   <li>Kommando Heer (Strausberg) : Inspekteur des Heeres, Kommandeur Feldheer, adjoints</li>
 *   <li>Grandes unités et divisions : 10. Panzerdivision, 1. Panzerdivision, Division Schnelle Kräfte, etc.</li>
 * </ul>
 *
 * <p>Télécharge les CVs officiels au format PDF, calcule leur empreinte SHA-256 pour archivage probant,
 * et extrait les données directement de la page si le CV est indisponible (fail-safe).
 */
@Extension
public class DePepDefenceHeerParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepDefenceHeerParser.class);

    public static final String ID_PREFIX = "de-pep-defence-heer";
    public static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Map<String, String> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
            Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
            Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
            Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
            Map.entry("dezember", "12")
    );

    // Regex
    private static final Pattern ADDRESS_BLOCK = Pattern.compile("(?si)<address class=\"Address\">.*?</address>");
    private static final Pattern RANK_NAME_P = Pattern.compile("(?si)<p><strong>\\s*(Generalleutnant|Generalmajor|Brigadegeneral|General)\\s*</strong>\\s*([^<]+)</p>");
    private static final Pattern DESC_P = Pattern.compile("(?si)<p>\\s*(?:(?:Generalleutnant|Generalmajor|Brigadegeneral|General)\\s+)?([^<]+?)\\s+(?:führt|ist)\\s+seit\\s+([A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}[./]\\d{4}|\\d{4})(.*?)</p>");
    private static final Pattern PDF_LINK = Pattern.compile("(?si)<a[^>]+href=[\"']([^\"']+\\.pdf)[\"']");
    private static final Pattern IMG_SRC = Pattern.compile("(?si)<img[^>]+src=[\"']([^\"']*(?:portrait|bild-kdoh|bild)[^\"']*)[\"']");

    public record CommanderProfile(
            String slug,
            String name,
            String rank,
            String title,
            String firstName,
            String lastName,
            String positionName,
            String startDate,
            String status,
            String sourceUrl,
            String photoUrl,
            String pdfUrl,
            String pdfSha256,
            String notes
    ) {}

    public record CrawlResult(
            List<CommanderProfile> profiles,
            List<String> brokenSources,
            List<String> warnings
    ) {}

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> params = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, params);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);

        for (CommanderProfile cp : crawl.profiles()) {
            writeCommander(cp, dataset);
        }

        dataset.writeNdjsonFile(ctx.outputFile());

        StringBuilder msg = new StringBuilder();
        msg.append(crawl.profiles().size()).append(" officiers généraux du Heer (GwG F.1) extraits.");
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

        for (CommanderProfile cp : crawl.profiles()) {
            records.add(new SourceRecord(
                    ids.buildEntityId("person", cp.slug()),
                    "Person",
                    cp.rank() + " " + cp.name(),
                    cp.sourceUrl()
            ));
        }

        return records;
    }

    public CrawlResult crawl(ParserContext ctx, Map<String, String> params) {
        List<CommanderProfile> profiles = new ArrayList<>();
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
        if (testDir != null && Files.exists(testDir.resolve("kommando-heer.html"))) {
            try {
                String kdohHtml = Files.readString(testDir.resolve("kommando-heer.html"), StandardCharsets.UTF_8);
                if (ctx.rawDir() != null) {
                    Files.writeString(ctx.rawDir().resolve("kommando-heer.html"), kdohHtml, StandardCharsets.UTF_8);
                }
                String sourceUrl = params.getOrDefault("kommandoHeerUrl", "https://www.bundeswehr.de/de/organisation/heer/struktur/kommando-heer");
                List<CommanderProfile> list = parseCommandersFromHtml(
                        kdohHtml,
                        sourceUrl,
                        testDir.resolve("sample-vita.pdf")
                );
                profiles.addAll(list);

                Path div10File = testDir.resolve("10-panzerdivision.html");
                if (Files.exists(div10File)) {
                    String div10Html = Files.readString(div10File, StandardCharsets.UTF_8);
                    if (ctx.rawDir() != null) {
                        Files.writeString(ctx.rawDir().resolve("10-panzerdivision.html"), div10Html, StandardCharsets.UTF_8);
                    }
                    profiles.addAll(parseCommandersFromHtml(
                            div10Html,
                            "https://www.bundeswehr.de/de/organisation/heer/struktur/10-panzerdivision",
                            testDir.resolve("sample-vita.pdf")
                    ));
                }
            } catch (Exception e) {
                brokenSources.add("testDir: " + e.getMessage());
            }
            return new CrawlResult(profiles, brokenSources, warnings);
        }

        // Live network crawl - configuration injectee depuis ParserContext (table parser.parameters)
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        List<String> targetUrls = new ArrayList<>();
        if (params.containsKey("urls") && !params.get("urls").isBlank()) {
            for (String u : params.get("urls").split(",")) {
                String clean = u.trim();
                if (!clean.isEmpty() && !targetUrls.contains(clean)) {
                    targetUrls.add(clean);
                }
            }
        } else {
            String kommandoUrl = ctx.requireParameter("kommandoHeerUrl");
            targetUrls.add(kommandoUrl.trim());

            String rawUnits = params.get("units");
            if (rawUnits != null && !rawUnits.isBlank()) {
                for (String u : rawUnits.split(",")) {
                    String clean = u.trim();
                    if (!clean.isEmpty() && !targetUrls.contains(clean)) {
                        targetUrls.add(clean);
                    }
                }
            }
        }

        int index = 0;
        for (String url : targetUrls) {
            try {
                Thread.sleep(400); // Politeness delay
                String html = fetchSafe(client, url);
                if (html == null) {
                    brokenSources.add("SOURCE_BROKEN: " + url);
                    continue;
                }

                if (ctx.rawDir() != null) {
                    index++;
                    String safeName = String.format("source_%02d_%s.html", index, url.replaceAll("[^a-zA-Z0-9]+", "_"));
                    if (safeName.length() > 60) {
                        safeName = safeName.substring(0, 60) + ".html";
                    }
                    try {
                        Files.writeString(ctx.rawDir().resolve(safeName), html, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        log.warn("Échec écriture rawDir pour {}: {}", url, e.getMessage());
                    }
                }

                List<CommanderProfile> list = parseCommandersFromHtml(html, url, null);
                if (list.isEmpty()) {
                    log.debug("Aucun bloc de commandement détecté sur {}", url);
                }
                // Enrich profiles with PDF SHA-256
                for (CommanderProfile p : list) {
                    CommanderProfile enriched = enrichPdfSha256(client, p);
                    profiles.add(enriched);
                }
            } catch (Exception e) {
                brokenSources.add("SOURCE_BROKEN: " + url + " (" + e.getMessage() + ")");
            }
        }

        return new CrawlResult(profiles, brokenSources, warnings);
    }

    public static List<CommanderProfile> parseCommandersFromHtml(String html, String pageUrl, Path samplePdfForTest) {
        List<CommanderProfile> list = new ArrayList<>();

        Matcher mAddr = ADDRESS_BLOCK.matcher(html);
        while (mAddr.find()) {
            String addrBlock = mAddr.group();

            Matcher mRank = RANK_NAME_P.matcher(addrBlock);
            if (!mRank.find()) {
                continue;
            }

            String rank = cleanText(mRank.group(1));
            String rawName = cleanText(mRank.group(2));

            String title = null;
            String cleanName = rawName;
            if (rawName.startsWith("Dr. ") || rawName.contains(" Dr. ")) {
                title = "Dr.";
                cleanName = rawName.replaceAll("(?i)\\bDr\\.\\s*", "").trim();
            }

            String[] parts = cleanName.split("\\s+", 2);
            String firstName = parts[0];
            String lastName = parts.length > 1 ? parts[1] : "";

            String slug = slugify(cleanName);

            // Description / Role / Start date
            String startDate = null;
            String positionName = "Kommandeur (Heer)";
            String descSentence = "";

            Matcher mDesc = DESC_P.matcher(addrBlock);
            if (mDesc.find()) {
                String dateRaw = mDesc.group(2);
                startDate = parseGermanMonthYear(dateRaw);
                String rest = cleanText(mDesc.group(3));
                positionName = resolvePositionFromText(addrBlock, rest, pageUrl);
                descSentence = cleanText(mDesc.group(0));
            } else {
                positionName = resolvePositionFromText(addrBlock, "", pageUrl);
            }

            // PDF CV URL
            String pdfUrl = null;
            Matcher mPdf = PDF_LINK.matcher(addrBlock);
            if (mPdf.find()) {
                String href = mPdf.group(1);
                pdfUrl = href.startsWith("http") ? href : "https://www.bundeswehr.de" + href;
            }

            // Photo URL
            String photoUrl = null;
            int blockIdx = html.indexOf(addrBlock);
            if (blockIdx > 0) {
                int lookback = Math.max(0, blockIdx - 1500);
                String preceding = html.substring(lookback, blockIdx);
                Matcher mImg = IMG_SRC.matcher(preceding);
                while (mImg.find()) {
                    String src = mImg.group(1);
                    photoUrl = src.startsWith("http") ? src : "https://www.bundeswehr.de" + src;
                }
            }

            // SHA-256 computation in test mode
            String sha256 = null;
            if (samplePdfForTest != null && Files.exists(samplePdfForTest)) {
                try {
                    byte[] bytes = Files.readAllBytes(samplePdfForTest);
                    sha256 = computeSha256(bytes);
                } catch (Exception ignored) {}
            }

            StringBuilder notes = new StringBuilder();
            notes.append("Position: ").append(positionName).append(" (GwG § 1 Abs. 12 S. 2 Nr. 1 - Class F.1 - Officiers généraux)");
            notes.append("\nGrade: ").append(rank);
            if (startDate != null) notes.append("\nPrise de fonction: ").append(startDate);
            if (!descSentence.isEmpty()) notes.append("\nPrésentation: ").append(descSentence);
            if (pdfUrl != null) {
                notes.append("\nCV-PDF: ").append(pdfUrl);
                if (sha256 != null) notes.append(" (SHA-256: ").append(sha256).append(")");
            }

            list.add(new CommanderProfile(
                    slug,
                    cleanName,
                    rank,
                    title,
                    firstName,
                    lastName,
                    positionName,
                    startDate,
                    "current",
                    pageUrl,
                    photoUrl,
                    pdfUrl,
                    sha256,
                    notes.toString()
            ));
        }

        return list;
    }

    private static String resolvePositionFromText(String block, String context, String pageUrl) {
        String lower = (block + " " + context + " " + pageUrl).toLowerCase(Locale.GERMAN);

        if ((lower.contains("inspekteur") && lower.contains("heeres") && !lower.contains("stellvertreter"))
                || (lower.contains("führt") && lower.contains("kommando heer"))) {
            return "Inspekteur des Heeres";
        }
        if (lower.contains("stellvertreter des inspekteurs") || lower.contains("militärische grundorganisation")) {
            return "Stellvertreter des Inspekteurs des Heeres und Kommandeur Militärische Grundorganisation";
        }
        if (lower.contains("den stab des kommandos heer") || lower.contains("chef des stabes")) {
            return "Chef des Stabes Kommando Heer";
        }
        if (lower.contains("kommandeur feldheer") && !lower.contains("stellvertreter")) {
            return "Kommandeur Feldheer";
        }
        if (lower.contains("stellvertreter des kommandeurs feldheer")) {
            return "Stellvertreter des Kommandeurs Feldheer";
        }
        if (lower.contains("10. panzerdivision") || lower.contains("10-panzerdivision")) {
            return "Kommandeur 10. Panzerdivision";
        }
        if (lower.contains("1. panzerdivision") || lower.contains("1-panzerdivision")) {
            return "Kommandeur 1. Panzerdivision";
        }
        if (lower.contains("schnelle kräfte") || lower.contains("division-schnelle-kraefte")) {
            return "Kommandeur Division Schnelle Kräfte";
        }
        if (lower.contains("heimatschutzdivision")) {
            return "Kommandeur Heimatschutzdivision";
        }
        if (lower.contains("amt für heeresentwicklung") || lower.contains("amt-fuer-heeresentwicklung")) {
            return "Kommandeur Amt für Heeresentwicklung";
        }
        if (lower.contains("1-deutsch-niederlaendisches-korps") || lower.contains("deutsch-niederländische")) {
            return "Kommandeur 1. Deutsch-Niederländisches Korps";
        }

        return "Kommandeur im Heer";
    }

    private static CommanderProfile enrichPdfSha256(HttpClient client, CommanderProfile p) {
        if (p.pdfUrl() == null || p.pdfSha256() != null) {
            return p;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(p.pdfUrl()))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200 && resp.body().length > 0) {
                String sha256 = computeSha256(resp.body());
                String enrichedNotes = p.notes() + " (SHA-256: " + sha256 + ")";
                return new CommanderProfile(
                        p.slug(), p.name(), p.rank(), p.title(), p.firstName(), p.lastName(),
                        p.positionName(), p.startDate(), p.status(), p.sourceUrl(),
                        p.photoUrl(), p.pdfUrl(), sha256, enrichedNotes
                );
            }
        } catch (Exception e) {
            log.warn("Impossible de télécharger le PDF {} : {}", p.pdfUrl(), e.getMessage());
        }
        return p;
    }

    private static void writeCommander(CommanderProfile cp, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", cp.slug());
        person.addValue("name", cp.name());
        if (cp.rank() != null) person.addValue("title", cp.rank());
        if (cp.firstName() != null && !cp.firstName().isBlank()) person.addValue("firstName", cp.firstName());
        if (cp.lastName() != null && !cp.lastName().isBlank()) person.addValue("lastName", cp.lastName());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", cp.positionName());
        person.addValue("sourceUrl", cp.sourceUrl());

        if (cp.notes() != null) {
            person.addValue("summary", cp.notes());
            person.addValue("notes", cp.notes());
        }

        dataset.addEntity(person);

        if (cp.photoUrl() != null && !cp.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), cp.photoUrl(), cp.sourceUrl(), "Amtliches Porträt (bundeswehr.de)");
        }

        // Position entity
        String posSlug = slugify(cp.positionName());
        FtmEntity position = dataset.newEntity("Position", posSlug);
        position.addValue("name", cp.positionName());
        position.addValue("country", "de");
        position.addValue("organization", "Deutsches Heer");
        position.addValue("topics", "military");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        // Occupancy entity
        FtmEntity occupancy = dataset.newEntity("Occupancy", cp.slug(), "heer");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", cp.status());
        occupancy.addValue("description", cp.positionName() + " (GwG § 1 Abs. 12 S. 2 Nr. 1 - Class F.1)");
        occupancy.addValue("sourceUrl", cp.sourceUrl());
        if (cp.startDate() != null) occupancy.addValue("startDate", cp.startDate());

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

    public static String parseGermanMonthYear(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.GERMAN);

        Matcher m = Pattern.compile("([a-zäöü]+)\\s*(\\d{4})").matcher(s);
        if (m.find()) {
            String monthName = m.group(1);
            String year = m.group(2);
            String month = GERMAN_MONTHS.getOrDefault(monthName, "01");
            return String.format("%s-%s-01", year, month);
        }

        Matcher mNum = Pattern.compile("(\\d{1,2})[./](\\d{4})").matcher(s);
        if (mNum.find()) {
            int month = Integer.parseInt(mNum.group(1));
            int year = Integer.parseInt(mNum.group(2));
            return String.format("%04d-%02d-01", year, month);
        }

        Matcher mYear = Pattern.compile("\\b(20\\d{2})\\b").matcher(s);
        if (mYear.find()) {
            return mYear.group(1) + "-01-01";
        }

        return null;
    }

    public static String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithme SHA-256 non disponible", e);
        }
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
