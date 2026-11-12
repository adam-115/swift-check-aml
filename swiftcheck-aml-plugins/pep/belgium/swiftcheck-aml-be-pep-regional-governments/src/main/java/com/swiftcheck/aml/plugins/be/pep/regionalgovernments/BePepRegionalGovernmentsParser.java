package com.swiftcheck.aml.plugins.be.pep.regionalgovernments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser PEP Belgique – Gouvernements des Régions et des Communautés
 * ({@code be_pep_regional_governments}).
 *
 * <p>Cible légale : Loi du 18 septembre 2017, art. 4, 28°, a) et Annexe IV –
 * « Ministre-président, vice-ministre-président, ministre ou secrétaire d'État
 * d'une Région ou d'une Communauté » (catégorie {@code BE-a}).</p>
 */
@Extension
public class BePepRegionalGovernmentsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepRegionalGovernmentsParser.class);

    static final String CONFIG_RESOURCE = "/regional-governments.json";
    static final String ID_PREFIX = "be-pep-regional-governments";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";

    private static final Pattern VLAANDEREN_CARD_PATTERN = Pattern.compile(
            "<div data-pt-type=\"WpPtSpotlightPerson\"[\\s\\S]*?"
            + "(?:<img[^>]+src=\"([^\"]+)\"[^>]*>[\\s\\S]*?)?"
            + "<div class=\"vl-spotlight__title\">\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>[\\s\\S]*?"
            + "<span class=\"wp-pt-spotlight__title\">\\s*<span>([^<]+)</span>[\\s\\S]*?"
            + "<span class=\"vl-annotation\">([\\s\\S]*?)</span>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CFWB_LI_PATTERN = Pattern.compile(
            "<li>\\s*<b>([^<:]+?)(?:\\s*:\\s*(?:&nbsp;)*)?</b>\\s*:?\\s*([\\s\\S]*?)</li>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern OSTBELGIEN_LI_PATTERN = Pattern.compile(
            "<li>\\s*<strong>([^<]+)</strong>\\s*,\\s*([^<]+)</li>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern GENERIC_HEADING_ROLE_PATTERN = Pattern.compile(
            "<h[2-4][^>]*>([^<]{4,80})</h[2-4]>\\s*<p[^>]*>([^<]*(?:Ministre|Secrétaire d'État|Minister)[^<]*)</p>",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        GovernmentsConfig config = loadConfig();
        List<String> failures = new ArrayList<>();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        boolean first = true;
        for (GovernmentEntry gov : config.governments()) {
            if (!first && config.delayMs() > 0) {
                Thread.sleep(config.delayMs());
            }
            first = false;

            HttpRequest req = HttpRequest.newBuilder(URI.create(gov.url()))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - gouvernements regionaux)")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "fr-BE,fr;q=0.9,nl;q=0.8,de;q=0.7")
                    .GET()
                    .build();
            try {
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    Files.writeString(ctx.rawDir().resolve(gov.fileName()), resp.body(), StandardCharsets.UTF_8);
                    log.info("[be_pep_regional_governments] {} téléchargé ({} caractères)",
                            gov.code(), resp.body().length());
                } else {
                    failures.add(gov.code() + " (HTTP " + resp.statusCode() + ")");
                    log.warn("[be_pep_regional_governments] Échec sur {} : HTTP {}", gov.url(), resp.statusCode());
                }
            } catch (Exception e) {
                failures.add(gov.code() + " (" + e.getClass().getSimpleName() + ")");
                log.warn("[be_pep_regional_governments] Site injoignable {} : {}", gov.url(), e.getMessage());
            }
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<MinisterRecord> records = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        String msg = records.size() + " membres de gouvernements régionaux/communautaires extraits.";
        if (!failures.isEmpty()) {
            msg += " Exécutifs injoignables : " + String.join(", ", failures) + ".";
        }
        return dataset.toConversionResult().withMessage(msg);
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    List<MinisterRecord> convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        GovernmentsConfig config = loadConfig();
        Map<String, MinisterRecord> records = new LinkedHashMap<>();

        for (GovernmentEntry gov : config.governments()) {
            Path htmlFile = rawDir.resolve(gov.fileName());
            if (!Files.isRegularFile(htmlFile)) {
                continue;
            }
            String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
            List<MinisterRecord> parsed = parseGovernmentHtml(gov, html);
            for (MinisterRecord r : parsed) {
                records.put(gov.code() + "-" + r.slug(), r);
            }
        }

        if (records.isEmpty()) {
            throw new IllegalStateException("Aucun membre de gouvernement régional extrait depuis " + rawDir);
        }

        List<MinisterRecord> list = new ArrayList<>(records.values());
        for (MinisterRecord rec : list) {
            writeRecord(rec, dataset);
        }
        return list;
    }

    static List<MinisterRecord> parseGovernmentHtml(GovernmentEntry gov, String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        return switch (gov.format()) {
            case "VLAANDEREN_SPOTLIGHT" -> parseVlaanderen(gov, html);
            case "CFWB_LIST" -> parseCfwb(gov, html);
            case "OSTBELGIEN_LIST" -> parseOstbelgien(gov, html);
            default -> parseGeneric(gov, html);
        };
    }

    private static List<MinisterRecord> parseVlaanderen(GovernmentEntry gov, String html) {
        List<MinisterRecord> list = new ArrayList<>();
        Matcher m = VLAANDEREN_CARD_PATTERN.matcher(html);
        while (m.find()) {
            String photoUrl = cleanText(m.group(1));
            String href = cleanText(m.group(2));
            String fullName = cleanText(m.group(3));
            String titleRaw = cleanText(m.group(4));
            if (fullName.isEmpty()) continue;

            String[] fl = splitFirstLast(fullName);
            String roleCategory = classifyRoleCategory(titleRaw, gov.entityName());
            String profileUrl = href.startsWith("http") ? href : "https://www.vlaanderen.be" + href;

            list.add(new MinisterRecord(
                    fullName.toLowerCase(Locale.ROOT),
                    fl[0],
                    fl[1],
                    fullName,
                    roleCategory,
                    titleRaw,
                    gov.name(),
                    gov.entityName(),
                    profileUrl,
                    photoUrl.isEmpty() ? null : photoUrl
            ));
        }
        return list;
    }

    private static List<MinisterRecord> parseCfwb(GovernmentEntry gov, String html) {
        List<MinisterRecord> list = new ArrayList<>();
        Matcher m = CFWB_LI_PATTERN.matcher(html);
        while (m.find()) {
            String rawName = cleanText(m.group(1)).replaceAll(":$", "").trim();
            String titleRaw = cleanText(m.group(2)).replaceAll("\\.$", "").trim();
            if (rawName.isEmpty() || titleRaw.isEmpty()) continue;
            if (!titleRaw.toLowerCase(Locale.ROOT).contains("ministre")) continue;

            String[] fl = splitFirstLast(rawName);
            String displayName = (fl[0].isEmpty() ? "" : fl[0] + " ") + fl[1];
            String roleCategory = classifyRoleCategory(titleRaw, gov.entityName());

            list.add(new MinisterRecord(
                    displayName.toLowerCase(Locale.ROOT),
                    fl[0],
                    fl[1],
                    displayName.trim(),
                    roleCategory,
                    titleRaw,
                    gov.name(),
                    gov.entityName(),
                    gov.url(),
                    null
            ));
        }
        return list;
    }

    private static List<MinisterRecord> parseOstbelgien(GovernmentEntry gov, String html) {
        List<MinisterRecord> list = new ArrayList<>();
        Matcher m = OSTBELGIEN_LI_PATTERN.matcher(html);
        while (m.find()) {
            String fullName = cleanText(m.group(1));
            String titleRaw = cleanText(m.group(2));
            if (fullName.isEmpty() || titleRaw.isEmpty()) continue;
            if (!titleRaw.toLowerCase(Locale.ROOT).contains("minister")) continue;

            String[] fl = splitFirstLast(fullName);
            String roleCategory = classifyRoleCategory(titleRaw, gov.entityName());

            list.add(new MinisterRecord(
                    fullName.toLowerCase(Locale.ROOT),
                    fl[0],
                    fl[1],
                    fullName,
                    roleCategory,
                    titleRaw,
                    gov.name(),
                    gov.entityName(),
                    gov.url(),
                    null
            ));
        }
        return list;
    }

    private static List<MinisterRecord> parseGeneric(GovernmentEntry gov, String html) {
        List<MinisterRecord> list = new ArrayList<>();
        Matcher m = GENERIC_HEADING_ROLE_PATTERN.matcher(html);
        while (m.find()) {
            String fullName = cleanText(m.group(1));
            String titleRaw = cleanText(m.group(2));
            if (fullName.isEmpty() || titleRaw.isEmpty()) continue;

            String[] fl = splitFirstLast(fullName);
            String roleCategory = classifyRoleCategory(titleRaw, gov.entityName());

            list.add(new MinisterRecord(
                    fullName.toLowerCase(Locale.ROOT),
                    fl[0],
                    fl[1],
                    fullName,
                    roleCategory,
                    titleRaw,
                    gov.name(),
                    gov.entityName(),
                    gov.url(),
                    null
            ));
        }
        return list;
    }

    static String classifyRoleCategory(String rawTitle, String entityName) {
        String lower = rawTitle == null ? "" : rawTitle.toLowerCase(Locale.ROOT);
        if (lower.contains("viceminister-president")
                || lower.contains("vice-ministre-président")
                || lower.contains("vice-président")
                || lower.contains("vice-president")) {
            return "Vice-ministre-président – " + entityName;
        }
        if (lower.contains("minister-president")
                || lower.contains("ministre-président")
                || lower.contains("ministerpräsident")) {
            return "Ministre-président – " + entityName;
        }
        if (lower.contains("secrétaire d'état") || lower.contains("staatssecretaris")) {
            return "Secrétaire d'État – " + entityName;
        }
        return "Ministre – " + entityName;
    }

    static void writeRecord(MinisterRecord rec, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", "minister", rec.fullName());
        person.addValue("name", rec.fullName());
        person.addValue("firstName", rec.firstName());
        person.addValue("lastName", rec.lastName());
        person.addValue("country", COUNTRY);
        person.addValue("position", rec.roleCategory());
        person.addValue("notes", rec.fullPortfolio() + " (" + rec.governmentName() + ")");
        person.addValue("sourceUrl", rec.sourceUrl());
        dataset.addEntity(person);

        if (rec.photoUrl() != null && !rec.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), rec.photoUrl(), rec.sourceUrl(), rec.governmentName());
        }

        FtmEntity position = dataset.newEntity("Position", rec.roleCategory());
        position.addValue("name", rec.roleCategory());
        position.addValue("country", COUNTRY);
        position.addValue("organization", rec.governmentName());
        position.addValue("topics", "gov.state");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        FtmEntity occ = dataset.newEntity("Occupancy", rec.fullName(), rec.roleCategory());
        occ.addValue("holder", person.getId());
        occ.addValue("post", position.getId());
        occ.addValue("status", "current");
        occ.addValue("description", rec.fullPortfolio());
        occ.addValue("sourceUrl", rec.sourceUrl());
        dataset.addEntity(occ);
    }

    static GovernmentsConfig loadConfig() throws IOException {
        try (InputStream in = BePepRegionalGovernmentsParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IOException("Ressource introuvable : " + CONFIG_RESOURCE);
            }
            return new ObjectMapper().readValue(in, GovernmentsConfig.class);
        }
    }

    static String[] splitFirstLast(String rawName) {
        String cleaned = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
        String[] tokens = cleaned.split(" ");
        if (tokens.length <= 1) {
            return new String[]{"", cleaned};
        }
        List<String> upperTokens = new ArrayList<>();
        List<String> mixedTokens = new ArrayList<>();
        for (String t : tokens) {
            String lettersOnly = t.replaceAll("[^\\p{L}]", "");
            if (lettersOnly.length() >= 2 && lettersOnly.equals(lettersOnly.toUpperCase(Locale.ROOT))) {
                upperTokens.add(t);
            } else {
                mixedTokens.add(t);
            }
        }
        if (!upperTokens.isEmpty() && !mixedTokens.isEmpty()) {
            return new String[]{String.join(" ", mixedTokens), String.join(" ", upperTokens)};
        }
        return new String[]{tokens[0], String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length))};
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&rsquo;", "'")
                .replace("&eacute;", "é")
                .replace("&Eacute;", "É")
                .replace("&egrave;", "è")
                .replace("&ecirc;", "ê")
                .replace("&agrave;", "à")
                .replace("&ouml;", "ö")
                .replace("&uuml;", "ü")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GovernmentsConfig(int delayMs, List<GovernmentEntry> governments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GovernmentEntry(
            String code,
            String name,
            String entityName,
            String url,
            String fileName,
            String format
    ) {}

    public record MinisterRecord(
            String slug,
            String firstName,
            String lastName,
            String fullName,
            String roleCategory,
            String fullPortfolio,
            String governmentName,
            String entityName,
            String sourceUrl,
            String photoUrl
    ) {}
}
