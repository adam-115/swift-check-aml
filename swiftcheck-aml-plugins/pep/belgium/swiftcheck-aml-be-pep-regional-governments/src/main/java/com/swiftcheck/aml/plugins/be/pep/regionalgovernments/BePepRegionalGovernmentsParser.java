package com.swiftcheck.aml.plugins.be.pep.regionalgovernments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
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

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile(
            "\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?\\]\\]"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        GovernmentsConfig config = loadConfig(ctx.parameters());
        List<String> failures = new ArrayList<>();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<String> successions = new ArrayList<>();
        boolean first = true;
        for (GovernmentEntry gov : config.governments()) {
            if (!first && config.delayMs() > 0) {
                Thread.sleep(config.delayMs());
            }
            first = false;

            String url = gov.url();
            try {
                String body = fetch(client, url);
                // Page Wikipédia d'un gouvernement terminé : on suit « suivant » jusqu'au gouvernement en fonction.
                if ("MEDIAWIKI_TABLE".equals(gov.format())) {
                    for (int hop = 0; hop < MAX_SUCCESSION_HOPS; hop++) {
                        String[] next = successorOfEndedGovernment(body);
                        if (next == null) {
                            break;
                        }
                        String nextUrl = withPage(url, next[1]);
                        log.info("[be_pep_regional_governments] {} : {} terminé le {}, lecture du suivant {}",
                                gov.code(), pageOf(url), next[0], next[1]);
                        successions.add(gov.code() + " : " + pageOf(url).replace('_', ' ') + " terminé le " + next[0]
                                + ", remplacé par " + next[1]);
                        url = nextUrl;
                        body = fetch(client, url);
                    }
                }
                Files.writeString(ctx.rawDir().resolve(gov.fileName()), body, StandardCharsets.UTF_8);
                log.info("[be_pep_regional_governments] {} téléchargé ({} caractères)", gov.code(), body.length());
            } catch (Exception e) {
                failures.add(gov.code() + " (" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + ")");
                log.warn("[be_pep_regional_governments] Échec sur {} : {}", url, e.getMessage());
            }
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<MinisterRecord> records = convert(ctx.rawDir(), dataset, ctx.parameters());
        dataset.writeNdjsonFile(ctx.outputFile());

        String msg = records.size() + " membres de gouvernements régionaux/communautaires extraits.";
        if (!successions.isEmpty()) {
            msg += " Gouvernement terminé, successeur lu (à reporter dans les paramètres) : " + String.join(" ; ", successions) + ".";
        }
        if (!failures.isEmpty()) {
            msg += " Exécutifs injoignables : " + String.join(", ", failures) + ".";
        }
        return dataset.toConversionResult().withMessage(msg);
    }

    /** Nombre maximal de gouvernements successifs suivis à partir de la page configurée. */
    static final int MAX_SUCCESSION_HOPS = 3;

    private static final Pattern INFOBOX_END = Pattern.compile("(?m)^\\|\\s*fin\\s*=\\s*([^\\n|]*?)\\s*$");
    private static final Pattern INFOBOX_NEXT = Pattern.compile("(?m)^\\|\\s*suivant\\s*=\\s*\\[\\[([^\\]|#]+)");

    /**
     * Réponse de l'API MediaWiki (ou wikitexte) d'un gouvernement : si l'infobox donne une date de fin et un
     * gouvernement « suivant », renvoie {date de fin, titre de la page suivante} ; sinon {@code null}
     * (gouvernement en fonction).
     */
    static String[] successorOfEndedGovernment(String apiResponse) {
        String wikitext = apiResponse;
        if (apiResponse != null && apiResponse.trim().startsWith("{")) {
            try {
                wikitext = MAPPER.readTree(apiResponse).path("parse").path("wikitext").path("*").asText("");
            } catch (Exception e) {
                return null;
            }
        }
        if (wikitext == null) {
            return null;
        }
        Matcher end = INFOBOX_END.matcher(wikitext);
        Matcher next = INFOBOX_NEXT.matcher(wikitext);
        if (!end.find() || end.group(1).isBlank() || !next.find()) {
            return null;
        }
        return new String[]{end.group(1).trim(), next.group(1).trim()};
    }

    /** Même adresse de l'API MediaWiki, avec une autre page ({@code page=…}). */
    static String withPage(String apiUrl, String pageTitle) {
        String encoded = java.net.URLEncoder.encode(pageTitle.replace(' ', '_'), StandardCharsets.UTF_8);
        return apiUrl.replaceFirst("([?&]page=)[^&]*", "$1" + Matcher.quoteReplacement(encoded));
    }

    private static String pageOf(String apiUrl) {
        Matcher m = Pattern.compile("[?&]page=([^&]*)").matcher(apiUrl);
        return m.find() ? java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : apiUrl;
    }

    private static String fetch(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - gouvernements regionaux)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-BE,fr;q=0.9,nl;q=0.8,de;q=0.7")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset, ctx.parameters());
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
        return convert(rawDir, dataset, Map.of());
    }

    List<MinisterRecord> convert(Path rawDir, FtmDatasetBuilder dataset, Map<String, String> params) throws IOException {
        GovernmentsConfig config = loadConfig(params);
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
            case "MEDIAWIKI_TABLE" -> parseMediaWikiTable(gov, html);
            default -> parseGeneric(gov, html);
        };
    }

    private static List<MinisterRecord> parseMediaWikiTable(GovernmentEntry gov, String rawContent) {
        String wikitext = rawContent;
        if (rawContent.trim().startsWith("{")) {
            try {
                wikitext = MAPPER.readTree(rawContent)
                        .path("parse")
                        .path("wikitext")
                        .path("*")
                        .asText("");
            } catch (Exception e) {
                log.warn("[be_pep_regional_governments] JSON MediaWiki invalide pour {} : {}",
                        gov.code(), e.getMessage());
            }
        }
        if (wikitext == null || wikitext.isBlank()) {
            return List.of();
        }

        int compIdx = wikitext.indexOf("== Composition");
        String section = compIdx >= 0 ? wikitext.substring(compIdx) : wikitext;
        int tableStart = section.indexOf("{|");
        int tableEnd = tableStart >= 0 ? section.indexOf("|}", tableStart) : -1;
        if (tableStart < 0 || tableEnd < 0) {
            return List.of();
        }
        String table = section.substring(tableStart, tableEnd);

        List<MinisterRecord> list = new ArrayList<>();
        String currentPortfolio = "";
        boolean afterFileCell = false;

        for (String rawLine : table.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("|-") || line.startsWith("!")) {
                afterFileCell = false;
                continue;
            }
            if (line.contains("[[Fichier:") || line.contains("[[File:")) {
                afterFileCell = true;
                continue;
            }
            if (afterFileCell && line.startsWith("|")) {
                afterFileCell = false;
                if (line.toLowerCase(Locale.ROOT).contains("jusqu'au")) {
                    continue;
                }
                String strippedCell = line
                        .replaceAll("<ref[^>]*>[\\s\\S]*?</ref>", "")
                        .replaceAll("<small>[\\s\\S]*?</small>", "");
                Matcher linkMatcher = WIKI_LINK_PATTERN.matcher(strippedCell);
                if (linkMatcher.find()) {
                    String target = linkMatcher.group(1).trim();
                    String label = linkMatcher.group(2) != null ? linkMatcher.group(2).trim() : target;
                    String fullName = cleanWikiText(label);
                    if (!fullName.isEmpty() && !currentPortfolio.isEmpty()) {
                        String[] fl = splitFirstLast(fullName);
                        String roleCategory = classifyRoleCategory(currentPortfolio, gov.entityName());
                        String profileUrl = "https://fr.wikipedia.org/wiki/" + target.replace(' ', '_');
                        list.add(new MinisterRecord(
                                fullName.toLowerCase(Locale.ROOT),
                                fl[0],
                                fl[1],
                                fullName,
                                roleCategory,
                                currentPortfolio,
                                gov.name(),
                                gov.entityName(),
                                profileUrl,
                                null
                        ));
                    }
                }
                continue;
            }
            if (line.startsWith("|") && !line.contains("{{Infobox Parti")) {
                String cell = line.replaceFirst("^\\|\\s*(?:(?:rowspan|bgcolor)[^|]*\\|\\s*)?", "").trim();
                if (!cell.startsWith("[[")) {
                    String cleanedPortfolio = cleanWikiText(cell);
                    String lower = cleanedPortfolio.toLowerCase(Locale.ROOT);
                    if (lower.contains("ministre") || lower.contains("secrétaire d'état")) {
                        currentPortfolio = cleanedPortfolio;
                    }
                }
            }
        }
        return list;
    }

    private static String cleanWikiText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw
                .replaceAll("<ref[^>]*>[\\s\\S]*?</ref>", "")
                .replaceAll("<small>[\\s\\S]*?</small>", "")
                .replaceAll("(?i)<br\\s*/?>", ", ");
        Matcher m = WIKI_LINK_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(2) != null ? m.group(2).trim() : m.group(1).trim();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return cleanText(sb.toString().replace("'''", "").replace("''", ""));
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
                || lower.contains("vice ministre-président")
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

    static GovernmentsConfig loadConfig(Map<String, String> params) throws IOException {
        Map<String, String> p = params == null ? Map.of() : params;
        String govsJson = p.getOrDefault("governments", p.get("configJson"));
        if (govsJson == null || govsJson.isBlank()) {
            throw new IllegalStateException("Paramètre « governments » absent : la liste des gouvernements régionaux"
                    + " se configure dans les paramètres du parser (colonne parser.parameters).");
        }
        com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(govsJson);
        int delay = root.has("delayMs") ? root.path("delayMs").asInt(1500) : 1500;
        if (p.containsKey("delayMs") && !p.get("delayMs").isBlank()) {
            delay = Integer.parseInt(p.get("delayMs").trim());
        }
        com.fasterxml.jackson.databind.JsonNode arrNode = root.has("governments") ? root.path("governments")
                : (root.has("value") ? root.path("value") : root);
        List<GovernmentEntry> parsed = MAPPER.readValue(
                arrNode.traverse(),
                new TypeReference<List<GovernmentEntry>>() {});
        List<GovernmentEntry> merged = parsed.stream()
                .map(g -> new GovernmentEntry(
                        g.code(),
                        p.getOrDefault(g.code() + ".name", g.name()),
                        p.getOrDefault(g.code() + ".entityName", g.entityName()),
                        p.getOrDefault(g.code() + ".url", g.url()),
                        p.getOrDefault(g.code() + ".fileName", g.fileName()),
                        p.getOrDefault(g.code() + ".format", g.format())
                ))
                .toList();
        return new GovernmentsConfig(delay, merged);
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
