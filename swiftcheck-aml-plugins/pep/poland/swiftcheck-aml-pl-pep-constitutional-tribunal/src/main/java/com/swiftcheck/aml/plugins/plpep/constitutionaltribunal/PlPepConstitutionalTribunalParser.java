package com.swiftcheck.aml.plugins.plpep.constitutionaltribunal;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>pl_pep_constitutional_tribunal</b> – Juges du Tribunal constitutionnel polonais (Trybunał Konstytucyjny).
 *
 * <p>Couvre la catégorie <b>PL-d</b>, fonction <b>Juge du Tribunal constitutionnel</b> (fonction 490)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia Trybunał Konstytucyjny).
 */
@Extension
public class PlPepConstitutionalTribunalParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepConstitutionalTribunalParser.class);

    static final String ID_PREFIX = "pl-pep-constitutional-tribunal";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String JSON_FILE = "pl-constitutional-tribunal-wikipedia.json";

    static final String DEFAULT_PAGE = "Trybuna\u0142_Konstytucyjny_(Polska)";

    private static final Pattern REF_PATTERN = Pattern.compile("(?i)<ref[^>]*>.*?</ref>|<ref[^>]*/>|<ref[^>]*>");
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{[^{}]*\\}\\}");
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");

    private static final Map<String, String> PL_MONTHS = Map.ofEntries(
            Map.entry("stycznia", "01"),
            Map.entry("lutego", "02"),
            Map.entry("marca", "03"),
            Map.entry("kwietnia", "04"),
            Map.entry("maja", "05"),
            Map.entry("czerwca", "06"),
            Map.entry("lipca", "07"),
            Map.entry("sierpnia", "08"),
            Map.entry("wrze\u015bnia", "09"),
            Map.entry("pa\u017adziernika", "10"),
            Map.entry("listopada", "11"),
            Map.entry("grudnia", "12")
    );

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PlPepConstitutionalTribunalParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PlPepConstitutionalTribunalParser(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        fetchLive(rawFile, ctx);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d juges du Tribunal constitutionnel de Pologne index\u00e9s.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            fetchLive(rawFile, ctx);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://trybunal.gov.pl/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target, ParserContext ctx) throws IOException, InterruptedException {
        String pageParam = null;
        if (ctx != null && ctx.parameters() != null) {
            pageParam = ctx.parameters().get("page");
        }
        String page = (pageParam != null && !pageParam.isBlank()) ? pageParam : DEFAULT_PAGE;

        String url = "https://pl.wikipedia.org/w/api.php?action=parse&page="
                + URLEncoder.encode(page, StandardCharsets.UTF_8)
                + "&prop=wikitext&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        log.info("Interrogation MediaWiki pour le Tribunal constitutionnel polonais : {}", url);
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 200) {
            Files.writeString(target, resp.body(), StandardCharsets.UTF_8);
            log.info("Donn\u00e9es MediaWiki enregistr\u00e9es dans {}", target);
        } else {
            throw new IOException("MediaWiki API a r\u00e9pondu avec le statut " + resp.statusCode());
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        String wikitext = root.path("parse").path("wikitext").path("*").asText("");
        if (wikitext.isEmpty()) {
            // Also check query.pages format
            JsonNode pages = root.path("query").path("pages");
            if (pages.isObject()) {
                for (JsonNode p : pages) {
                    wikitext = p.path("revisions").path(0).path("slots").path("main").path("*").asText("");
                    if (!wikitext.isEmpty()) break;
                }
            }
        }

        if (wikitext.isEmpty()) {
            throw new IllegalStateException("Aucun contenu wikitext trouv\u00e9 dans : " + rawFile);
        }

        int count = 0;
        Set<String> seenJudges = new HashSet<>();
        String pageUrl = "https://pl.wikipedia.org/wiki/Trybuna%C5%82_Konstytucyjny_(Polska)";

        // Extract judges from the table in section Sędziowie
        Pattern datePattern = Pattern.compile("(?m)^\\|(?:[^|\\n]*\\|)*\\s*od\\s+(\\d{1,2}\\s+[^\\s\\d<]+\\s+(\\d{4}))(?:\\s+do\\s+\\d{1,2}\\s+[^\\s\\d<]+\\s+(\\d{4}))?");
        Pattern namePattern = Pattern.compile("(?m)^\\|(?:bgcolor=[^|]*\\|)?\\s*\\[\\[([^|\\]]+)(?:\\|([^\\|\\]]+))?\\]\\]");

        String[] rows = wikitext.split("(?m)^\\|-");
        for (String r : rows) {
            Matcher dm = datePattern.matcher(r);
            if (!dm.find()) {
                continue;
            }

            String startStr = dm.group(1);
            int startYear = Integer.parseInt(dm.group(2));
            int endYear = dm.group(3) != null ? Integer.parseInt(dm.group(3)) : 9999;

            // Only consider judges appointed 2015+ or serving in 2024+
            if (startYear < 2015 && endYear < 2024) {
                continue;
            }

            Matcher nm = namePattern.matcher(r);
            if (!nm.find()) {
                continue;
            }

            String target = nm.group(1);
            String label = nm.group(2) != null ? nm.group(2) : target;
            if (target.matches("(?i).*(Sejm|PRL|PZPR|ZSL|SD|Monitor|Dziennik|Koalicja|Trybuna\u0142|Plik:|File:|Kadencja|stan spoczynku).*")) {
                continue;
            }

            String name = cleanName(label);
            if (name.length() < 3 || seenJudges.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            seenJudges.add(name.toLowerCase(Locale.ROOT));

            String role;
            if (name.contains("\u015awi\u0119czkowski") || name.contains("Przy\u0142\u0119bska")) {
                role = "Prezes Trybuna\u0142u Konstytucyjnego";
            } else if (name.contains("Socha\u0144ski") || name.contains("Muszy\u0144ski")) {
                role = "Wiceprezes Trybuna\u0142u Konstytucyjnego";
            } else {
                role = "S\u0119dzia Trybuna\u0142u Konstytucyjnego";
            }

            String isoStart = parsePolishDate(startStr);

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            String[] nameParts = splitName(name);
            if (!nameParts[0].isEmpty()) {
                person.addValue("firstName", nameParts[0]);
            }
            if (!nameParts[1].isEmpty()) {
                person.addValue("lastName", nameParts[1]);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", role);
            person.addValue("sourceUrl", pageUrl);
            dataset.addEntity(person);

            String posSlug = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", role);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Trybuna\u0142 Konstytucyjny");
            pos.addValue("sourceUrl", pageUrl);
            pos.addValue("topics", "gov.judicial");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            if (!isoStart.isEmpty()) {
                occupancy.addValue("startDate", isoStart);
            }
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    static String parsePolishDate(String raw) {
        Pattern p = Pattern.compile("(\\d{1,2})\\s+([a-zA-Z\u0105\u0119\u015b\u0107\u017a\u017c\u00f3\u0142\u0144]+)\\s+(\\d{4})");
        Matcher m = p.matcher(raw);
        if (m.find()) {
            String day = m.group(1);
            if (day.length() == 1) {
                day = "0" + day;
            }
            String monthName = m.group(2).toLowerCase(Locale.ROOT);
            String month = PL_MONTHS.get(monthName);
            String year = m.group(3);
            if (month != null) {
                return year + "-" + month + "-" + day;
            }
        }
        return "";
    }

    private static String cleanName(String name) {
        String n = REF_PATTERN.matcher(name).replaceAll("");
        while (TEMPLATE_PATTERN.matcher(n).find()) {
            n = TEMPLATE_PATTERN.matcher(n).replaceAll("");
        }
        n = n.replaceAll("\\s*\\([^)]*\\)", "");
        n = n.replaceAll("[\\(\\)\\[\\]\\{\\}\\\"']", "");
        return n.replaceAll("\\s+", " ").trim();
    }

    private static String[] splitName(String fullName) {
        String[] parts = fullName.split("\\s+");
        if (parts.length == 1) {
            return new String[]{parts[0], ""};
        }
        return new String[]{parts[0], parts[parts.length - 1]};
    }
}
