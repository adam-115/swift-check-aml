package com.swiftcheck.aml.plugins.ropep.deputies;

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
 * <b>ro_pep_deputies</b> – Députés de la Chambre des députés de Roumanie (Camera Deputaților).
 *
 * <p>Couvre la catégorie <b>RO-b</b>, fonction <b>Parlementaire</b> (fonction 735)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 * Source dynamique : MediaWiki API (Wikipedia - Legislatura 2020–2024 (Camera Deputaților)).
 */
@Extension
public class RoPepDeputiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepDeputiesParser.class);

    static final String ID_PREFIX = "ro-pep-deputies";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String JSON_FILE = "ro-deputies-wikipedia.json";

    public static final String PARAM_WIKIPEDIA_API_URL = "wikipedia.api.url";
    public static final String PARAM_PAGE_TITLE = "wikipedia.page.title";

    private static final String DEFAULT_API_URL = "https://ro.wikipedia.org/w/api.php";
    private static final String DEFAULT_TITLE = "Legislatura_2020–2024_(Camera_Deputaților)";

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
                String.format(Locale.ROOT, "%d députés de la Chambre des députés de Roumanie indexés.", count)
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
                records.add(new SourceRecord(entity.getId(), "Person", name, "https://www.cdep.ro/"));
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
        log.info("MediaWiki : page de la Chambre des députés roumaine téléchargée vers {}", destination);
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

        Map<String, DeputyData> deputies = extractDeputies(wikitext);
        int count = 0;

        for (DeputyData d : deputies.values()) {
            FtmEntity person = dataset.newEntity("Person", d.name);
            person.addValue("name", d.name);
            for (String alias : d.aliases) {
                person.addValue("alias", alias);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", d.position);
            person.addValue("sourceUrl", "https://www.cdep.ro/");
            dataset.addEntity(person);

            String posName = d.position;
            String posSlug = (posName + (d.party.isEmpty() ? "" : "-" + d.party)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", posName);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Camera Deputaților a României" + (d.party.isEmpty() ? "" : " (" + d.party + ")"));
            pos.addValue("topics", "gov.legislature");
            pos.addValue("sourceUrl", "https://www.cdep.ro/");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("startDate", "2020-12-21");
            occupancy.addValue("status", d.isEnded ? "ended" : "current");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private Map<String, DeputyData> extractDeputies(String wikitext) {
        Map<String, DeputyData> map = new LinkedHashMap<>();

        int startIdx = wikitext.indexOf("==Listă deputați==");
        if (startIdx < 0) {
            startIdx = wikitext.indexOf("Listă deputați");
        }
        if (startIdx < 0) {
            return map;
        }

        int endIdx = wikitext.indexOf("== Note ==", startIdx);
        if (endIdx < 0) {
            endIdx = wikitext.length();
        }

        String section = wikitext.substring(startIdx, endIdx);
        String[] rows = section.split("(?m)^\\|-");

        for (String row : rows) {
            List<String> cells = new ArrayList<>();
            String[] lines = row.split("\r?\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("|") && !trimmed.startsWith("|-") && !trimmed.startsWith("|}") && !trimmed.startsWith("|+")) {
                    String lineContent = trimmed.substring(1).trim();
                    String[] lineCells = lineContent.split("\\|\\|");
                    for (String c : lineCells) {
                        cells.add(c.trim());
                    }
                }
            }

            if (cells.size() >= 4) {
                String rawNameCell = cells.get(1);
                String countyRaw = cells.get(2);
                String partyRaw = cells.get(3);
                String obsRaw = cells.size() > 4 ? cells.get(4) : "";

                String name = extractLinkText(rawNameCell);
                if (name == null || name.length() < 3 || name.contains("Prenume")) {
                    continue;
                }

                String party = cleanWikitext(partyRaw);
                boolean isEnded = obsRaw.toLowerCase(Locale.ROOT).contains("decedat")
                        || obsRaw.toLowerCase(Locale.ROOT).contains("demisionat");

                DeputyData dd = new DeputyData(name, "Deputat în Camera Deputaților a României", party, isEnded);

                // Add common variants / aliases
                if ("Vasile-Daniel Suciu".equals(name)) {
                    dd.aliases.add("Daniel Suciu");
                } else if ("Daniel Suciu".equals(name)) {
                    dd.aliases.add("Vasile-Daniel Suciu");
                }

                if ("Lucian Nicolae Bode".equals(name)) {
                    dd.aliases.add("Lucian-Nicolae Bode");
                    dd.aliases.add("Lucian Bode");
                } else if ("Lucian-Nicolae Bode".equals(name)) {
                    dd.aliases.add("Lucian Nicolae Bode");
                    dd.aliases.add("Lucian Bode");
                }

                map.put(name, dd);
            }
        }

        return map;
    }

    private String extractLinkText(String cell) {
        Matcher m = WIKILINK_PATTERN.matcher(cell);
        if (m.find()) {
            return m.group(2) != null ? m.group(2).trim() : m.group(1).trim();
        }
        String cleaned = cleanWikitext(cell);
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String cleanWikitext(String text) {
        String s = text.replaceAll("<ref[^>]*>.*?</ref>", "")
                .replaceAll("<ref[^>]*/>", "")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\{\\{[^}]+\\}\\}", "")
                .trim();

        Matcher mWlink = WIKILINK_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (mWlink.find()) {
            String label = mWlink.group(2) != null ? mWlink.group(2) : mWlink.group(1);
            mWlink.appendReplacement(sb, Matcher.quoteReplacement(label));
        }
        mWlink.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static class DeputyData {
        final String name;
        final String position;
        final String party;
        final boolean isEnded;
        final List<String> aliases = new ArrayList<>();

        DeputyData(String name, String position, String party, boolean isEnded) {
            this.name = name;
            this.position = position;
            this.party = party;
            this.isEnded = isEnded;
        }
    }
}
