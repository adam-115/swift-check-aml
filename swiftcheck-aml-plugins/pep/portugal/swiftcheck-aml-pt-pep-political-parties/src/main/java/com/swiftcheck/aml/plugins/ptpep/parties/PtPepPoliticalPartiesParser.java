package com.swiftcheck.aml.plugins.ptpep.parties;

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
 * <b>pt_pep_political_parties</b> – Dirigeants exécutifs des partis politiques représentés au Parlement portugais.
 *
 * <p>Couvre la catégorie <b>PT-g</b>, fonction <b>Membre de l'organe exécutif d'un parti politique</b> (fonction 266)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API SPARQL de Wikidata.
 */
@Extension
public class PtPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "pt-pep-political-parties";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String JSON_FILE = "pt-political-parties.json";

    static final String SPARQL_QUERY =
            "SELECT DISTINCT ?party ?partyLabel ?person ?personLabel ?prop ?propLabel ?startDate ?endDate WHERE {\n" +
            "  {\n" +
            "    VALUES ?party {\n" +
            "      wd:Q595575   # PSD\n" +
            "      wd:Q847263   # PS\n" +
            "      wd:Q63645885 # Chega\n" +
            "      wd:Q46122950 # IL\n" +
            "      wd:Q884840   # BE\n" +
            "      wd:Q769829   # PCP\n" +
            "      wd:Q1054298  # CDS-PP\n" +
            "      wd:Q2054840  # PAN\n" +
            "    }\n" +
            "    {\n" +
            "      ?party p:P488 ?stmt .\n" +
            "      ?stmt ps:P488 ?person .\n" +
            "      BIND(wd:P488 AS ?prop)\n" +
            "      OPTIONAL { ?stmt pq:P580 ?startDate . }\n" +
            "      OPTIONAL { ?stmt pq:P582 ?endDate . }\n" +
            "    } UNION {\n" +
            "      ?party p:P1037 ?stmt .\n" +
            "      ?stmt ps:P1037 ?person .\n" +
            "      BIND(wd:P1037 AS ?prop)\n" +
            "      OPTIONAL { ?stmt pq:P580 ?startDate . }\n" +
            "      OPTIONAL { ?stmt pq:P582 ?endDate . }\n" +
            "    } UNION {\n" +
            "      ?party p:P3975 ?stmt .\n" +
            "      ?stmt ps:P3975 ?person .\n" +
            "      BIND(wd:P3975 AS ?prop)\n" +
            "      OPTIONAL { ?stmt pq:P580 ?startDate . }\n" +
            "      OPTIONAL { ?stmt pq:P582 ?endDate . }\n" +
            "    }\n" +
            "  } UNION {\n" +
            "    ?person p:P39 ?stmt .\n" +
            "    ?stmt ps:P39 wd:Q82563249 .\n" +
            "    BIND(wd:Q884840 AS ?party)\n" +
            "    BIND(wd:Q82563249 AS ?prop)\n" +
            "    OPTIONAL { ?stmt pq:P580 ?startDate . }\n" +
            "    OPTIONAL { ?stmt pq:P582 ?endDate . }\n" +
            "  }\n" +
            "  FILTER(!BOUND(?endDate) || YEAR(?endDate) >= 2020)\n" +
            "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"pt,mul,en\". }\n" +
            "} ORDER BY ?partyLabel ?personLabel";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PtPepPoliticalPartiesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PtPepPoliticalPartiesParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants des partis politiques portugais indexés.", count)
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
                        "https://www.parlamento.pt/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        String uri = "https://query.wikidata.org/sparql?query=" + URLEncoder.encode(SPARQL_QUERY, StandardCharsets.UTF_8) + "&format=json";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        log.info("Interrogation SPARQL Wikidata pour les partis politiques portugais");
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 200) {
            Files.writeString(target, resp.body(), StandardCharsets.UTF_8);
        } else {
            throw new IOException("Erreur HTTP " + resp.statusCode() + " lors de la requête SPARQL");
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        return convert(rawFile, dataset, null);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset, ParserContext ctx) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");

        Set<String> seen = new HashSet<>();
        int count = 0;

        if (bindings.isArray()) {
            for (JsonNode b : bindings) {
                String name = b.path("personLabel").path("value").asText("").trim();
                if (name.isEmpty() || name.startsWith("Q") && name.substring(1).matches("\\d+")) {
                    continue;
                }

                String party = b.path("partyLabel").path("value").asText("Parti politique").trim();
                String role = b.path("propLabel").path("value").asText("Dirigeant").trim();
                String startDate = b.path("startDate").path("value").asText("");
                String endDate = b.path("endDate").path("value").asText("");

                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                String position = role + " – " + party;
                addLeader(dataset, name, position, party, startDate, endDate, "https://www.parlamento.pt/");
                count++;
            }
        }

        // Extra party leaders from parameters if supplied
        if (ctx != null && ctx.parameters() != null) {
            String extraLeadersJson = ctx.parameters().get("leaders");
            if (extraLeadersJson != null && !extraLeadersJson.isBlank()) {
                try {
                    JsonNode extra = json.readTree(extraLeadersJson);
                    JsonNode list = extra.isArray() ? extra : extra.path("leaders");
                    if (list.isArray()) {
                        for (JsonNode l : list) {
                            String name = l.path("name").asText("").trim();
                            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                                continue;
                            }
                            String party = l.path("party").asText("Parti politique");
                            String position = l.path("position").asText("Dirigeant");
                            String since = l.path("since").asText("");
                            addLeader(dataset, name, position + " – " + party, party, since, null, "https://www.parlamento.pt/");
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Impossible de parser le paramètre leaders : {}", e.getMessage());
                }
            }
        }

        return count;
    }

    private void addLeader(FtmDatasetBuilder dataset, String name, String position, String party, String startDate, String endDate, String sourceUrl) {
        FtmEntity person = dataset.newEntity("Person", name);
        person.addValue("name", name);
        person.addValue("country", COUNTRY);
        person.addValue("nationality", COUNTRY);
        person.addValue("position", position);
        person.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(person);

        String posSlug = (party + "-" + position).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        FtmEntity posEntity = dataset.newEntity("Position", posSlug);
        posEntity.addValue("name", position);
        posEntity.addValue("country", COUNTRY);
        posEntity.addValue("organization", party);
        posEntity.addValue("topics", "pol.party");
        posEntity.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(posEntity);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", posEntity.getId());
        occupancy.addValue("status", endDate == null || endDate.isEmpty() ? "current" : "ended");
        if (startDate != null && !startDate.isEmpty()) {
            occupancy.addValue("startDate", startDate.substring(0, Math.min(10, startDate.length())));
        }
        if (endDate != null && !endDate.isEmpty()) {
            occupancy.addValue("endDate", endDate.substring(0, Math.min(10, endDate.length())));
        }
        dataset.addEntity(occupancy);
    }
}
