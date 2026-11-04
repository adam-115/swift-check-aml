package com.swiftcheck.aml.plugins.depep.landtage;

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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_landtage</b> – Députés des 16 parlements régionaux des Länder (Landtage / Bürgerschaften / Abgeordnetenhaus).
 * <p>
 * Catégorie <b>DE-b (optionnelle / importance comparable)</b>.
 * Environ 1 900 parlementaires régionaux à travers les 16 Länder.
 * Sources : API AbgeordnetenWatch (CC0) et registres des parlements des Länder.
 */
@Extension
public class DePepLandtageParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepLandtageParser.class);

    static final String ID_PREFIX = "de-pep-landtage";
    static final String TOPIC = "role.pep";
    static final String CONFIG_RESOURCE = "/landtage.json";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    /** Format ligne: "Baden-Württemberg | Winfried Kretschmann | GRÜNE | Wahlkreis Nürtingen" */
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^\\s*([^|:;]+?)\\s*[|:;]\\s*([^|:;]+?)\\s*[|:;]\\s*([^|:;]+?)(?:\\s*[|:;]\\s*(.*))?$",
            Pattern.CASE_INSENSITIVE);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParliamentConfig(String key, String name, String state, String city, int seats) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<ParliamentConfig> parliaments) {
    }

    record MP(String parliamentKey, String parliamentName, String state,
              String fullName, String title, String cleanName, String party, String constituency) {
    }

    static final String DEFAULT_API_URL = "https://www.abgeordnetenwatch.de/api/v2/candidacies-mandates?range_end=200";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<MP> allMps = new ArrayList<>();

        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String content = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            allMps.addAll(parseContent(content, config));
            FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
            convert(allMps, dataset);
            dataset.writeNdjsonFile(ctx.outputFile());
            return dataset.toConversionResult().withMessage(
                    allMps.size() + " députés des parlements régionaux des Länder extraits.");
        }

        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        boolean allowCrawl = !"false".equalsIgnoreCase(p.get("crawl"));

        if (ctx.rawDir() != null) {
            Files.createDirectories(ctx.rawDir());
            Path mandatesJson = ctx.rawDir().resolve("landtage-mandates.json");

            if (Files.isRegularFile(mandatesJson)) {
                allMps.addAll(parseContent(Files.readString(mandatesJson, StandardCharsets.UTF_8), config));
            } else if (allowCrawl) {
                String apiUrl = p.getOrDefault("endpoint", DEFAULT_API_URL).trim();
                if (!apiUrl.contains("candidacies-mandates")) {
                    apiUrl = apiUrl + (apiUrl.endsWith("/") ? "" : "/") + "candidacies-mandates?range_end=200";
                }
                String json = crawl(apiUrl, "SwiftCheck-AML/1.0 (liste PEP Allemagne - Parlements des Länder)");
                if (json != null && json.contains("data")) {
                    Files.writeString(mandatesJson, json, StandardCharsets.UTF_8);
                    allMps.addAll(parseContent(json, config));
                }
            }

            for (ParliamentConfig pc : config.parliaments()) {
                Path f = ctx.rawDir().resolve(pc.key() + ".txt");
                if (!Files.isRegularFile(f)) {
                    f = ctx.rawDir().resolve(pc.key() + ".json");
                }
                if (Files.isRegularFile(f)) {
                    String content = Files.readString(f, StandardCharsets.UTF_8);
                    allMps.addAll(parseContent(content, config));
                }
            }
        }

        if (allMps.isEmpty()) {
            throw new IllegalStateException("Aucun député de Landtag extrait.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(allMps, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                allMps.size() + " députés des parlements régionaux des Länder extraits.");
    }

    static String crawl(String url, String userAgent) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                return resp.body();
            }
        } catch (Exception e) {
            log.warn("Échec du crawl de {} : {}", url, e.getMessage());
        }
        return null;
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<MP> mps = new ArrayList<>();
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            mps.addAll(parseContent(Files.readString(ctx.inputFile(), StandardCharsets.UTF_8), config));
        }
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (MP mp : mps) {
            records.add(new SourceRecord(
                    ids.buildEntityId(mp.parliamentKey(), mp.cleanName()),
                    "Person",
                    mp.fullName(),
                    mp.parliamentName() + " (" + mp.party() + ")"
            ));
        }
        return records;
    }

    static Config loadConfig(ParserContext ctx) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String configFile = ctx.parameters().get("configFile");
        if (configFile != null && !configFile.isBlank()) {
            Path p = Path.of(configFile);
            if (Files.isRegularFile(p)) {
                return mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), Config.class);
            }
        }
        try (InputStream in = DePepLandtageParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return mapper.readValue(in, Config.class);
        }
    }

    static List<MP> parseContent(String content, Config config) {
        List<MP> list = new ArrayList<>();
        String trimmed = content.trim();

        // 1. Détection JSON (AbgeordnetenWatch API ou array JSON)
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(content);
                JsonNode array = root.isArray() ? root : (root.has("data") ? root.get("data") : null);
                if (array != null && array.isArray()) {
                    for (JsonNode item : array) {
                        String name = item.has("label") ? item.get("label").asText()
                                : (item.has("name") ? item.get("name").asText()
                                : (item.has("first_name") && item.has("last_name")
                                ? item.get("first_name").asText() + " " + item.get("last_name").asText() : null));
                        if (name == null || name.isBlank()) {
                            continue;
                        }
                        String party = item.has("party") ? item.get("party").asText()
                                : (item.has("fraction") ? item.get("fraction").asText() : null);
                        String state = item.has("parliament") ? item.get("parliament").asText()
                                : (item.has("state") ? item.get("state").asText() : "Baden-Württemberg");
                        ParliamentConfig pc = findParliament(state, config);
                        addMp(list, pc, name, party, null);
                    }
                    if (!list.isEmpty()) {
                        return list;
                    }
                }
            } catch (Exception e) {
                log.debug("Format JSON non parsable : {}", e.getMessage());
            }
        }

        // 2. Format lignes texte délimitées
        for (String line : content.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            Matcher m = LINE_PATTERN.matcher(l);
            if (m.find()) {
                String stateOrParl = m.group(1).trim();
                String rawName = m.group(2).trim();
                String party = m.group(3).trim();
                String constituency = m.group(4) != null ? m.group(4).trim() : null;

                ParliamentConfig pc = findParliament(stateOrParl, config);
                addMp(list, pc, rawName, party, constituency);
            }
        }

        return list;
    }

    private static ParliamentConfig findParliament(String query, Config config) {
        String lower = query.toLowerCase();
        for (ParliamentConfig pc : config.parliaments()) {
            if (pc.key().equalsIgnoreCase(query) || pc.state().equalsIgnoreCase(query)
                    || lower.contains(pc.state().toLowerCase()) || lower.contains(pc.key())) {
                return pc;
            }
        }
        return config.parliaments().get(0);
    }

    private static void addMp(List<MP> list, ParliamentConfig pc, String rawName, String party, String constituency) {
        String title = null;
        String cleanName = rawName;
        Matcher tm = ACADEMIC_TITLE.matcher(rawName);
        if (tm.find()) {
            title = tm.group(0).trim();
            cleanName = rawName.substring(tm.end()).trim();
        }
        list.add(new MP(pc.key(), pc.name(), pc.state(), rawName, title, cleanName, party, constituency));
    }

    private void convert(List<MP> mps, FtmDatasetBuilder dataset) {
        for (MP mp : mps) {
            String posKey = mp.parliamentKey() + "-mp";

            FtmEntity position = dataset.newEntity("Position", posKey);
            position.addValue("name", "Mitglied des Landtages (" + mp.state() + ")");
            position.addValue("country", "de");
            position.addValue("organization", mp.parliamentName());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.state");
            dataset.addEntity(position);

            FtmEntity person = dataset.newEntity("Person", mp.parliamentKey(), mp.cleanName());
            person.addValue("name", mp.cleanName());
            person.addValue("title", mp.title());
            String[] parts = mp.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            if (mp.party() != null && !mp.party().isBlank()) {
                person.addValue("political", mp.party());
            }
            person.addValue("position", "Mitglied des Landtages (" + mp.state() + ")");
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", mp.parliamentKey(), mp.cleanName(), "mp");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            if (mp.party() != null && !mp.party().isBlank()) {
                occ.addValue("politicalGroup", mp.party());
            }
            if (mp.constituency() != null && !mp.constituency().isBlank()) {
                occ.addValue("description", mp.constituency());
            }
            dataset.addEntity(occ);
        }
    }
}

