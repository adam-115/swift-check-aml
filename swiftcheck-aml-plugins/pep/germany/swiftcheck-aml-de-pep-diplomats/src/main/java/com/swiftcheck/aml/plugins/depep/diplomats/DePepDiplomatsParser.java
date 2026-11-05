package com.swiftcheck.aml.plugins.depep.diplomats;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
 * <b>de_pep_diplomats</b> – Ambassadeurs, représentants permanents et chargés d'affaires de la République fédérale d'Allemagne.
 * <p>
 * Catégorie <b>DE-g</b> (§ 1 (12) g) GwG ; liste JO C/2023/724).
 * Source officielle : Auswärtiges Amt (dtauslandsvertretungenliste-data.pdf / diplo.de).
 */
@Extension
public class DePepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepDiplomatsParser.class);

    static final String ID_PREFIX = "de-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String TOPIC_DIPLO = "role.diplo";

    public static final String DEFAULT_URL =
            "https://www.auswaertiges-amt.de/resource/blob/199314/a29ab590846468fabe65e1e9a9d5522c/dtauslandsvertretungenliste-data.pdf";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    private static final Pattern CIVILITY_PREFIX = Pattern.compile(
            "^(?:(?:Frau|Herr)\\s+)+", Pattern.CASE_INSENSITIVE);

    /** Format ligne: "Frankreich | Paris | Botschafter | François Delattre" ou "USA : Washington : Botschafterin : ..." */
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^\\s*([^|:;]+?)\\s*[|:;]\\s*([^|:;]+?)\\s*[|:;]\\s*(Botschafter(?:in)?|Ständige[rn]?\\s+Vertreter(?:in)?|Geschäftsträger(?:in)?(?:\\s+a\\.i\\.)?)\\s*[|:;]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    /** Format carte HTML Auswärtiges Amt / diplo.de */
    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?si)<(?:div|li|article|tr)[^>]*class=\"[^\"]*(?:teaser|card|mission|vertretung|embassy|entry)[^\"]*\"[^>]*>(.*?)(?=<div[^>]*class=\"[^\"]*(?:teaser|card|mission|vertretung|embassy)|<li[^>]*class=\"[^\"]*(?:teaser|card|mission)|</(?:body|ul|main|table)>|$)");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "(?si)<(?:h[2-4]|td|strong|b)[^>]*>\\s*(?:<a[^>]*href=\"([^\"]*)\"[^>]*>)?\\s*([^<]+?)\\s*(?:</a>)?\\s*</(?:h[2-4]|td|strong|b)>");

    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(?si)<(?:p|div|span|td)[^>]*class=\"[^\"]*(?:role|text|subline|subtitle|function|position|amt)[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</(?:p|div|span|td)>");

    private static final Pattern COUNTRY_PATTERN = Pattern.compile(
            "(?si)<(?:span|div|p|td)[^>]*class=\"[^\"]*(?:country|land|ort|city|staat)[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</(?:span|div|p|td)>");

    record Diplomat(String country, String cityOrMission, String role,
                    String fullName, String title, String cleanName, String profileUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        byte[] rawBytes = loadBytes(ctx);
        List<Diplomat> diplomats = extractDiplomats(rawBytes);

        if (diplomats.isEmpty()) {
            throw new IllegalStateException("Aucun diplomate extrait de la source.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(diplomats, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                diplomats.size() + " ambassadeurs et chefs de poste diplomatique extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        byte[] rawBytes = loadBytes(ctx);
        List<Diplomat> diplomats = extractDiplomats(rawBytes);
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (Diplomat d : diplomats) {
            records.add(new SourceRecord(
                    ids.buildEntityId(d.country(), d.cleanName()),
                    "Person",
                    d.fullName(),
                    d.country() + " (" + d.cityOrMission() + ") – " + d.role()
            ));
        }
        return records;
    }

    private byte[] loadBytes(ParserContext ctx) throws Exception {
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            return Files.readAllBytes(ctx.inputFile());
        }
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        boolean allowCrawl = !"false".equalsIgnoreCase(p.get("crawl"));
        String url = p.getOrDefault("url", DEFAULT_URL).trim();

        if (allowCrawl) {
            byte[] downloaded = download(url, "SwiftCheck-AML/1.0 (liste PEP Allemagne - Diplomates)");
            if (downloaded != null && downloaded.length > 0) {
                if (ctx.rawDir() != null) {
                    Files.createDirectories(ctx.rawDir());
                    String ext = isPdf(downloaded) ? ".pdf" : ".html";
                    Files.write(ctx.rawDir().resolve("diplomats" + ext), downloaded);
                }
                return downloaded;
            }
        }

        throw new IllegalStateException("Impossible de télécharger la liste officielle des ambassadeurs depuis " + url);
    }

    private static boolean isPdf(byte[] data) {
        return data != null && data.length >= 4 &&
                data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
    }

    static List<Diplomat> extractDiplomats(byte[] data) {
        if (isPdf(data)) {
            return extractFromPdf(data);
        }
        String text = new String(data, StandardCharsets.UTF_8);
        return extractFromText(text);
    }

    static List<Diplomat> extractFromPdf(byte[] pdfBytes) {
        List<Diplomat> list = new ArrayList<>();
        Map<String, Diplomat> unique = new LinkedHashMap<>();

        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(doc);

            String[] lines = fullText.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.startsWith("L:")) {
                    continue;
                }
                String contentAfterL = line.substring(2).trim();
                // Format: <Name>, <Role>
                int commaIdx = contentAfterL.indexOf(',');
                if (commaIdx <= 0) {
                    continue;
                }
                String rawName = contentAfterL.substring(0, commaIdx).trim();
                String rawRole = contentAfterL.substring(commaIdx + 1).trim();

                // Exclude honorary consuls
                if (rawRole.toLowerCase().contains("honorar") || rawName.toLowerCase().contains("honorar")) {
                    continue;
                }

                // Check city (usually line directly before L:)
                String city = "";
                for (int c = i - 1; c >= Math.max(0, i - 3); c--) {
                    String prev = lines[c].trim();
                    if (!prev.isEmpty() && !prev.startsWith("-") && !prev.startsWith("Gz.:") && !prev.startsWith("Hinweise")) {
                        city = prev;
                        break;
                    }
                }

                // Check country & URL forward
                String country = city;
                String profileUrl = null;
                for (int j = i + 1; j < Math.min(i + 20, lines.length); j++) {
                    String forward = lines[j].trim();
                    if (forward.startsWith("URL:")) {
                        profileUrl = forward.substring(4).trim().split("[,;\\s]+")[0];
                        if (profileUrl.startsWith("http://") || profileUrl.startsWith("https://")) {
                            // ok
                        } else {
                            profileUrl = "https://" + profileUrl;
                        }
                    }
                    if (forward.startsWith("Botschaft") || forward.startsWith("Generalkonsulat") || forward.startsWith("Ständige Vertretung")) {
                        // The line before is often the country
                        if (j > 0 && !lines[j - 1].trim().isEmpty() && !lines[j - 1].trim().startsWith("PA:") && !lines[j - 1].trim().startsWith("F:")) {
                            String possibleCountry = lines[j - 1].trim();
                            if (possibleCountry.length() < 50 && !possibleCountry.contains(":")) {
                                country = possibleCountry;
                            }
                        }
                    }
                    if (lines[j].trim().startsWith("L:")) {
                        break;
                    }
                }

                // Clean name and role
                Matcher civm = CIVILITY_PREFIX.matcher(rawName);
                if (civm.find()) {
                    rawName = rawName.substring(civm.end()).trim();
                }

                String title = null;
                String cleanName = rawName;
                Matcher tm = ACADEMIC_TITLE.matcher(rawName);
                if (tm.find()) {
                    title = tm.group(0).trim();
                    cleanName = rawName.substring(tm.end()).trim();
                }

                if (cleanName.length() < 3 || cleanName.equalsIgnoreCase("N.N.") || cleanName.equalsIgnoreCase("unbesetzt")) {
                    continue;
                }

                String role = normalizeRole(rawRole);
                if (country == null || country.isBlank()) {
                    country = city;
                }

                Diplomat d = new Diplomat(country, city, role, rawName, title, cleanName, profileUrl);
                unique.putIfAbsent(cleanName, d);
            }
        } catch (Exception e) {
            log.warn("Erreur d'extraction du PDF Auswärtiges Amt : {}", e.getMessage(), e);
        }

        list.addAll(unique.values());
        return list;
    }

    private static String normalizeRole(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("botschafterin")) return "Botschafterin";
        if (lower.contains("botschafter")) return "Botschafter";
        if (lower.contains("ständige vertreterin") || lower.contains("ständiger vertreter")) return "Ständiger Vertreter";
        if (lower.contains("geschäftsträgerin")) return "Geschäftsträgerin a. i.";
        if (lower.contains("geschäftsträger")) return "Geschäftsträger a. i.";
        if (lower.contains("generalkonsulin") || lower.contains("generalkonsul")) return "Generalkonsul";
        return raw;
    }

    static List<Diplomat> extractFromText(String content) {
        List<Diplomat> list = new ArrayList<>();

        // 1. Détection format structuré lignes
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher m = LINE_PATTERN.matcher(trimmed);
            if (m.find()) {
                String country = m.group(1).trim();
                String city = m.group(2).trim();
                String role = m.group(3).trim();
                String rawName = m.group(4).trim();

                String title = null;
                String cleanName = rawName;
                Matcher tm = ACADEMIC_TITLE.matcher(rawName);
                if (tm.find()) {
                    title = tm.group(0).trim();
                    cleanName = rawName.substring(tm.end()).trim();
                }
                list.add(new Diplomat(country, city, role, rawName, title, cleanName, null));
            }
        }

        // 2. Si non structuré en lignes, format HTML
        if (list.isEmpty()) {
            Matcher cardMatcher = CARD_PATTERN.matcher(content);
            while (cardMatcher.find()) {
                String card = cardMatcher.group(1);
                Matcher headMatcher = HEADING_PATTERN.matcher(card);
                if (!headMatcher.find()) {
                    continue;
                }
                String profileUrl = headMatcher.group(1);
                String rawName = headMatcher.group(2).trim();
                if (rawName.equalsIgnoreCase("Vertretungen") || rawName.equalsIgnoreCase("Botschaften")) {
                    continue;
                }

                String role = "Botschafter/in";
                Matcher rm = ROLE_PATTERN.matcher(card);
                if (rm.find()) {
                    role = rm.group(1).trim();
                }

                String country = "Ausland";
                Matcher cm = COUNTRY_PATTERN.matcher(card);
                if (cm.find()) {
                    country = cm.group(1).trim();
                }

                String title = null;
                String cleanName = rawName;
                Matcher tm = ACADEMIC_TITLE.matcher(rawName);
                if (tm.find()) {
                    title = tm.group(0).trim();
                    cleanName = rawName.substring(tm.end()).trim();
                }

                list.add(new Diplomat(country, country, role, rawName, title, cleanName, profileUrl));
            }
        }

        return list;
    }

    private static byte[] download(String url, String userAgent) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
        } catch (Exception e) {
            log.warn("Échec du téléchargement de {} : {}", url, e.getMessage());
        }
        return null;
    }

    private void convert(List<Diplomat> diplomats, FtmDatasetBuilder dataset) {
        for (Diplomat d : diplomats) {
            String posKey = "mission-" + d.country().replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase();

            FtmEntity position = dataset.newEntity("Position", posKey);
            position.addValue("name", d.role() + " der Bundesrepublik Deutschland (" + d.country() + ")");
            position.addValue("country", "de");
            position.addValue("organization", "Auswärtiges Amt");
            position.addValue("topics", "gov.national");
            position.addValue("topics", TOPIC_DIPLO);
            dataset.addEntity(position);

            FtmEntity person = dataset.newEntity("Person", d.country(), d.cleanName());
            person.addValue("name", d.cleanName());
            person.addValue("title", d.title());
            String[] parts = d.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            person.addValue("position", d.role() + " (" + d.country() + ")");
            person.addValue("topics", TOPIC_DIPLO);
            if (d.profileUrl() != null) {
                person.addValue("sourceUrl", d.profileUrl());
            }
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", d.country(), d.cleanName(), "diplo");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", d.role() + " (" + d.cityOrMission() + ")");
            dataset.addEntity(occ);
        }
    }
}
