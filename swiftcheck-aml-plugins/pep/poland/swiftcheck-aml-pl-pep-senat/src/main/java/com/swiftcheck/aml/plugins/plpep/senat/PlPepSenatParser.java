package com.swiftcheck.aml.plugins.plpep.senat;

import java.io.IOException;
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
 * <b>pl_pep_senat</b> – Sénateurs de la République de Pologne (Senat RP).
 *
 * <p>Couvre la catégorie <b>PL-b</b>, fonction <b>Sénateur</b> (fonction 486)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia PL - Senat XI kadencji).
 */
@Extension
public class PlPepSenatParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepSenatParser.class);

    static final String ID_PREFIX = "pl-pep-senat";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String JSON_FILE = "pl-senat-wikipedia.json";

    static final String DEFAULT_PAGE = "Senatorowie_XI_kadencji_Senatu_Rzeczypospolitej_Polskiej";

    private static final Pattern SENATOR_SORT_PATTERN = Pattern.compile("\\{\\{sort\\|[^|]+\\|\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]\\}\\}");
    private static final Pattern PREZYDIUM_ENTRY_PATTERN = Pattern.compile("(?m)^\\|\\s*(?:'''?)?\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\](?:'''?)?\\s*\\r?\\n\\|\\s*(?:'''?)?\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PlPepSenatParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PlPepSenatParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d sénateurs de la République de Pologne indexés.", count)
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
                        "https://www.senat.gov.pl/"));
            }
        }
        return records;
    }

    private void fetchLive(Path rawFile, ParserContext ctx) throws IOException, InterruptedException {
        String page = ctx.parameters().getOrDefault("wikipedia_page", DEFAULT_PAGE);
        String url = ctx.parameters().getOrDefault("wikipedia_api_url",
                "https://pl.wikipedia.org/w/api.php?action=parse&page=" + page + "&prop=wikitext&format=json");

        log.info("Récupération en direct des sénateurs polonais depuis : {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Échec requête MediaWiki API (" + response.statusCode() + ") : " + response.body());
        }

        Files.writeString(rawFile, response.body(), StandardCharsets.UTF_8);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        String wikitext = root.path("parse").path("wikitext").path("*").asText("");
        if (wikitext.isEmpty()) {
            throw new IllegalStateException("Aucun contenu wikitext dans : " + rawFile);
        }

        // 1. Extraire les fonctions spécifiques du Prezydium
        Map<String, String> prezydiumRoles = new LinkedHashMap<>();
        int idxP = wikitext.indexOf("== Prezydium Senatu");
        if (idxP >= 0) {
            int idxNext = wikitext.indexOf("== Senatorowie wybrani", idxP);
            String pSection = idxNext > idxP ? wikitext.substring(idxP, idxNext) : wikitext.substring(idxP);
            Matcher pm = PREZYDIUM_ENTRY_PATTERN.matcher(pSection);
            while (pm.find()) {
                String name = pm.group(2) != null ? pm.group(2).trim() : pm.group(1).trim();
                name = sanitizeName(name);
                String role = pm.group(3).trim();
                prezydiumRoles.put(name, role);
            }
        }

        // 2. Extraire la liste complète des sénateurs
        Map<String, String> senators = new LinkedHashMap<>();
        Matcher sm = SENATOR_SORT_PATTERN.matcher(wikitext);
        while (sm.find()) {
            String name = sm.group(2) != null ? sm.group(2).trim() : sm.group(1).trim();
            name = sanitizeName(name);
            if (!name.isEmpty()) {
                String role = prezydiumRoles.getOrDefault(name, "Senator Rzeczypospolitej Polskiej");
                senators.put(name, role);
            }
        }

        // Inclure également les membres du Prezydium si non capturés dans le tableau principal
        for (Map.Entry<String, String> entry : prezydiumRoles.entrySet()) {
            senators.putIfAbsent(entry.getKey(), entry.getValue());
        }

        if (senators.isEmpty()) {
            throw new IllegalStateException("Aucun sénateur trouvé dans : " + rawFile);
        }

        int count = 0;
        for (Map.Entry<String, String> entry : senators.entrySet()) {
            String name = entry.getKey();
            String position = entry.getValue();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", position);
            person.addValue("sourceUrl", "https://www.senat.gov.pl/");
            dataset.addEntity(person);

            String posSlug = position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Senat Rzeczypospolitej Polskiej");
            pos.addValue("sourceUrl", "https://www.senat.gov.pl/");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("startDate", "2023-11-13");
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private String sanitizeName(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)<ref[^>]*>.*?</ref>|<ref[^>]*/>|<ref[^>]*>", "")
                .replaceAll("'''?", "")
                .replaceAll("\\s*\\([^)]*\\)", "")
                .trim();
    }
}
