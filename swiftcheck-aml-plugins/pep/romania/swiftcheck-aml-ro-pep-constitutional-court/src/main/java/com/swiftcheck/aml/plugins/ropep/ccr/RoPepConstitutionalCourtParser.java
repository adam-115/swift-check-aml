package com.swiftcheck.aml.plugins.ropep.ccr;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>ro_pep_constitutional_court</b> – Juges de la Cour Constitutionnelle de Roumanie (Curtea Constituțională a României - CCR).
 *
 * <p>Couvre la catégorie <b>RO-d</b>, fonction <b>Juge de la Cour suprême ou constitutionnelle</b> (fonction 738)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 * Source dynamique : MediaWiki API (Wikipedia - Constitutional Court of Romania).
 */
@Extension
public class RoPepConstitutionalCourtParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepConstitutionalCourtParser.class);

    static final String ID_PREFIX = "ro-pep-ccr";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String JSON_FILE = "ro-constitutional-court-wikipedia.json";

    public static final String PARAM_WIKIPEDIA_API_URL = "wikipedia.api.url";
    public static final String PARAM_PAGE_TITLE = "wikipedia.page.title";

    private static final String DEFAULT_API_URL = "https://en.wikipedia.org/w/api.php";
    private static final String DEFAULT_TITLE = "Constitutional_Court_of_Romania";

    private static final Pattern ILINK_PATTERN = Pattern.compile("\\{\\{Interlanguage link(?: multi)?\\|([^|}]+)(?:\\|[^}]*)?\\}\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIKILINK_PATTERN = Pattern.compile("\\[\\[([^|\\]]+)(?:\\|([^|\\]]+))?\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);

        downloadWikipediaData(ctx, rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d juges de la Cour Constitutionnelle de Roumanie indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            downloadWikipediaData(ctx, rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                String name = entity.getValues("name").stream().findFirst().orElse(entity.getId());
                records.add(new SourceRecord(entity.getId(), "Person", name, "https://www.ccr.ro/"));
            }
        }
        return records;
    }

    private void downloadWikipediaData(ParserContext ctx, Path destination) throws IOException, InterruptedException {
        String apiUrl = ctx.parameters().getOrDefault(PARAM_WIKIPEDIA_API_URL, DEFAULT_API_URL);
        String title = ctx.parameters().getOrDefault(PARAM_PAGE_TITLE, DEFAULT_TITLE);

        String fullUrl = apiUrl + "?action=query&titles=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&prop=revisions&rvslots=main&rvprop=content&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("User-Agent", "SwiftCheckAML/1.0 (compliance@swiftcheck.com; automated PEP parser)")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("Erreur HTTP " + resp.statusCode() + " lors de la requête MediaWiki: " + fullUrl);
        }

        try (InputStream in = resp.body()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("MediaWiki : page de la Cour Constitutionnelle roumaine téléchargée vers {}", destination);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pages = root.path("query").path("pages");
        if (pages.isMissingNode() || !pages.isObject() || pages.isEmpty()) {
            throw new IllegalStateException("Aucune page retournée dans l'arbre JSON : " + rawFile);
        }

        String wikitext = "";
        for (Iterator<JsonNode> it = pages.elements(); it.hasNext(); ) {
            JsonNode page = it.next();
            JsonNode revs = page.path("revisions");
            if (revs.isArray() && !revs.isEmpty()) {
                wikitext = revs.get(0).path("slots").path("main").path("*").asText("");
                if (wikitext.isEmpty()) {
                    wikitext = revs.get(0).path("*").asText("");
                }
                break;
            }
        }

        if (wikitext.isEmpty()) {
            throw new IllegalStateException("Contenu wikitext vide pour : " + rawFile);
        }

        Map<String, JudgeData> judges = extractJudges(wikitext);
        int count = 0;

        for (JudgeData jd : judges.values()) {
            FtmEntity person = dataset.newEntity("Person", jd.name);
            person.addValue("name", jd.name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", jd.position);
            person.addValue("sourceUrl", "https://www.ccr.ro/");
            dataset.addEntity(person);

            String posSlug = jd.position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", jd.position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Curtea Constituțională a României");
            pos.addValue("topics", "gov.judiciary");
            pos.addValue("sourceUrl", "https://www.ccr.ro/");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("status", jd.isCurrent ? "current" : "ended");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private Map<String, JudgeData> extractJudges(String wikitext) {
        Map<String, JudgeData> map = new LinkedHashMap<>();
        String[] lines = wikitext.split("\r?\n");

        boolean inCurrentStructure = false;
        boolean inHistory = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("=== Current structure ===")) {
                inCurrentStructure = true;
                inHistory = false;
                continue;
            } else if (trimmed.contains("=== History ===")) {
                inHistory = true;
                inCurrentStructure = false;
                continue;
            } else if (trimmed.startsWith("==") && !trimmed.startsWith("===")) {
                inCurrentStructure = false;
                inHistory = false;
            }

            if ((inCurrentStructure || inHistory) && trimmed.startsWith("|")) {
                parseJudgeLine(trimmed, inCurrentStructure, map);
            }
        }

        return map;
    }

    private void parseJudgeLine(String line, boolean isCurrentStructure, Map<String, JudgeData> map) {
        // Strip table formatting: | rowspan=3 | [[Senate]] or | Name
        String content = line;
        if (content.startsWith("|")) {
            content = content.substring(1).trim();
        }

        // Could have multiple cells in one line: | cell1 || cell2
        String[] cells = content.split("\\|\\|");
        for (String cell : cells) {
            String trimmed = cell.trim();
            if (trimmed.startsWith("rowspan") || trimmed.startsWith("colspan") || trimmed.startsWith("style=")) {
                int pipeIdx = trimmed.indexOf('|');
                if (pipeIdx >= 0) {
                    trimmed = trimmed.substring(pipeIdx + 1).trim();
                } else {
                    continue;
                }
            }

            // Skip headers, years, senate/chamber/president labels
            if (trimmed.matches("^\\d{4}.*") || trimmed.isEmpty() || trimmed.contains("Senate")
                    || trimmed.contains("Chamber of Deputies") || trimmed.contains("President of Romania")
                    || trimmed.contains("Senate appointments") || trimmed.contains("Year") || trimmed.equals("!")) {
                continue;
            }

            boolean isPresident = trimmed.toLowerCase(Locale.ROOT).contains("president");
            String cleanName = cleanWikitext(trimmed);

            if (cleanName.length() >= 4 && !cleanName.equalsIgnoreCase("Senate")
                    && !cleanName.equalsIgnoreCase("Chamber of Deputies")
                    && !cleanName.equalsIgnoreCase("President of Romania")
                    && !cleanName.equalsIgnoreCase("President")
                    && !cleanName.matches("^\\d+$")) {

                // Standardize known name variants
                if ("Elena Tănăsescu".equals(cleanName) || "Simina Tănăsescu".equals(cleanName)) {
                    cleanName = "Elena Simina Tănăsescu";
                } else if ("Mihaela Cochină".equals(cleanName)) {
                    cleanName = "Mihaela Ciochină";
                } else if ("Dimitre Bogdan Licu".equals(cleanName)) {
                    cleanName = "Bogdan Licu";
                }

                String pos = isPresident ? "Președinte al Curții Constituționale a României"
                        : "Judecător la Curtea Constituțională a României";

                JudgeData existing = map.get(cleanName);
                if (existing == null) {
                    map.put(cleanName, new JudgeData(cleanName, pos, isCurrentStructure));
                } else {
                    if (isCurrentStructure) {
                        existing.isCurrent = true;
                    }
                    if (isPresident) {
                        existing.position = pos;
                    }
                }
            }
        }
    }

    private String cleanWikitext(String text) {
        String s = text.replaceAll("<ref[^>]*>.*?</ref>", "")
                .replaceAll("<ref[^>]*/>", "")
                .replaceAll("<[^>]+>", "")
                .replaceAll("<sup>.*?</sup>", "")
                .trim();

        Matcher mIlink = ILINK_PATTERN.matcher(s);
        if (mIlink.find()) {
            s = mIlink.group(1).trim();
        }

        Matcher mWlink = WIKILINK_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (mWlink.find()) {
            String label = mWlink.group(2) != null ? mWlink.group(2) : mWlink.group(1);
            mWlink.appendReplacement(sb, Matcher.quoteReplacement(label));
        }
        mWlink.appendTail(sb);
        s = sb.toString();

        return s.replaceAll("\\s+", " ").trim();
    }

    private static class JudgeData {
        final String name;
        String position;
        boolean isCurrent;

        JudgeData(String name, String position, boolean isCurrent) {
            this.name = name;
            this.position = position;
            this.isCurrent = isCurrent;
        }
    }
}
