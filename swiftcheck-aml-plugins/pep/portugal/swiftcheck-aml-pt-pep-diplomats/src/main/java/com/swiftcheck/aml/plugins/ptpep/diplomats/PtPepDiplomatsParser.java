package com.swiftcheck.aml.plugins.ptpep.diplomats;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 * <b>pt_pep_diplomats</b> – Ambassadeurs et chefs de mission diplomatique du Portugal.
 *
 * <p>Couvre la catégorie <b>PT-e</b>, fonction <b>Chef de mission diplomatique ou de poste consulaire</b> (fonction 262)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia Diplomates et Ambassadeurs du Portugal).
 */
@Extension
public class PtPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepDiplomatsParser.class);

    static final String ID_PREFIX = "pt-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String JSON_FILE = "pt-diplomats.json";

    static final String[] WIKI_CATEGORIES = {
            "Categoria:Embaixadores_de_Portugal",
            "Categoria:Diplomatas_de_Portugal"
    };

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PtPepDiplomatsParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PtPepDiplomatsParser(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        fetchLive(rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset, ctx);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d ambassadeurs et chefs de mission diplomatique du Portugal indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            fetchLive(rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset, ctx);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://pt.wikipedia.org/wiki/Categoria:Embaixadores_de_Portugal"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        List<JsonNode> responses = new ArrayList<>();
        for (String cat : WIKI_CATEGORIES) {
            String url = "https://pt.wikipedia.org/w/api.php?action=query&list=categorymembers&cmtitle=" + cat + "&cmlimit=100&format=json";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            log.info("Interrogation MediaWiki pour la catégorie diplomatique : {}", cat);
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                responses.add(json.readTree(resp.body()));
            }
        }
        Files.writeString(target, json.writeValueAsString(responses), StandardCharsets.UTF_8);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        return convert(rawFile, dataset, null);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset, ParserContext ctx) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        List<JsonNode> responseNodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(responseNodes::add);
        } else {
            responseNodes.add(root);
        }

        Set<String> seen = new HashSet<>();
        int count = 0;

        for (JsonNode resp : responseNodes) {
            JsonNode members = resp.path("query").path("categorymembers");
            if (members.isArray()) {
                for (JsonNode m : members) {
                    String title = m.path("title").asText("").trim();
                    if (title.isEmpty() || title.startsWith("Categoria:") || title.startsWith("Ficheiro:") || title.startsWith("Usuário")) {
                        continue;
                    }

                    String name = cleanTitle(title);
                    if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                        continue;
                    }

                    String position = "Embaixador / Diplomata de Portugal";
                    addDiplomat(dataset, name, position, "current", "https://pt.wikipedia.org/wiki/" + title.replaceAll(" ", "_"));
                    count++;
                }
            }
        }

        // Additional diplomats passed via parameters if any
        if (ctx != null && ctx.parameters() != null) {
            String extraAmbassadorsJson = ctx.parameters().get("ambassadors");
            if (extraAmbassadorsJson != null && !extraAmbassadorsJson.isBlank()) {
                try {
                    JsonNode extra = json.readTree(extraAmbassadorsJson);
                    JsonNode list = extra.isArray() ? extra : extra.path("ambassadors");
                    if (list.isArray()) {
                        for (JsonNode a : list) {
                            String name = a.path("name").asText("").trim();
                            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                                continue;
                            }
                            String position = a.path("position").asText("Embaixador");
                            addDiplomat(dataset, name, position, "current", "https://portaldiplomatico.mne.gov.pt/");
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Impossible de parser le paramètre ambassadors : {}", e.getMessage());
                }
            }
        }

        return count;
    }

    private void addDiplomat(FtmDatasetBuilder dataset, String name, String position, String status, String sourceUrl) {
        FtmEntity person = dataset.newEntity("Person", name);
        person.addValue("name", name);
        person.addValue("country", COUNTRY);
        person.addValue("nationality", COUNTRY);
        person.addValue("position", position);
        person.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(person);

        FtmEntity posEntity = dataset.newEntity("Position", "pt-amb-" + person.getId());
        posEntity.addValue("name", position);
        posEntity.addValue("country", COUNTRY);
        posEntity.addValue("organization", "Corpo Diplomático de Portugal");
        posEntity.addValue("topics", "gov.diplomatic");
        posEntity.addValue("topics", "gov.national");
        dataset.addEntity(posEntity);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", posEntity.getId());
        occupancy.addValue("status", status);
        dataset.addEntity(occupancy);
    }

    private String cleanTitle(String title) {
        return title.replaceAll("\\s*\\([^\\)]+\\)", "").trim();
    }
}
