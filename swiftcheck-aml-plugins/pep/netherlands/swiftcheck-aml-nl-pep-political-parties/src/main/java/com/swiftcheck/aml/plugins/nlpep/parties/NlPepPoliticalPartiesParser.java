package com.swiftcheck.aml.plugins.nlpep.parties;

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
 * <b>nl_pep_political_parties</b> – Dirigeants des partis politiques siégeant aux États généraux des Pays-Bas.
 *
 * <p>Couvre la catégorie <b>NL-c</b>, fonction <b>Membre d'un organe dirigeant d'un parti</b> (fonction 382)
 * (Wwft art. 1 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API SPARQL de Wikidata.
 */
@Extension
public class NlPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(NlPepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "nl-pep-political-parties";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "nl";
    static final String JSON_FILE = "nl-political-parties.json";

    static final String WIKIDATA_SPARQL_URL = "https://query.wikidata.org/sparql";

    static final String SPARQL_QUERY =
            "SELECT DISTINCT ?party ?partyLabel ?person ?personLabel ?roleLabel ?startDate ?endDate WHERE {\n" +
            "  VALUES ?party {\n" +
            "    wd:Q332739   # PVV\n" +
            "    wd:Q110853869 # GroenLinks-PvdA\n" +
            "    wd:Q239333   # VVD\n" +
            "    wd:Q121705847 # NSC\n" +
            "    wd:Q101083924 # BBB\n" +
            "    wd:Q747910   # D66\n" +
            "    wd:Q273749   # CDA\n" +
            "    wd:Q849580   # SP\n" +
            "    wd:Q28163962 # FVD\n" +
            "    wd:Q663535   # PvdD\n" +
            "    wd:Q239539   # CU\n" +
            "    wd:Q60172    # SGP\n" +
            "    wd:Q105412880 # Volt NL\n" +
            "    wd:Q104244010 # JA21\n" +
            "  }\n" +
            "  {\n" +
            "    ?party p:P488 ?st .\n" +
            "    ?st ps:P488 ?person .\n" +
            "    BIND(\"Partijvoorzitter\"@nl AS ?roleLabel)\n" +
            "    OPTIONAL { ?st pq:P580 ?startDate . }\n" +
            "    OPTIONAL { ?st pq:P582 ?endDate . }\n" +
            "  } UNION {\n" +
            "    ?party p:P1037 ?st .\n" +
            "    ?st ps:P1037 ?person .\n" +
            "    BIND(\"Partijleider\"@nl AS ?roleLabel)\n" +
            "    OPTIONAL { ?st pq:P580 ?startDate . }\n" +
            "    OPTIONAL { ?st pq:P582 ?endDate . }\n" +
            "  } UNION {\n" +
            "    VALUES (?party ?person ?roleLabel ?startDate) {\n" +
            "      (wd:Q110853869 wd:Q32681 \"Politiek leider van GroenLinks-PvdA\"@nl \"2023-08-22T00:00:00Z\"^^xsd:dateTime)\n" +
            "      (wd:Q121705847 wd:Q7192814 \"Partijleider van Nieuw Sociaal Contract\"@nl \"2023-08-19T00:00:00Z\"^^xsd:dateTime)\n" +
            "      (wd:Q121705847 wd:Q2353344 \"Fractievoorzitter van Nieuw Sociaal Contract\"@nl \"2024-09-01T00:00:00Z\"^^xsd:dateTime)\n" +
            "      (wd:Q663535 wd:Q434842 \"Partijleider van Partij voor de Dieren\"@nl \"2019-10-08T00:00:00Z\"^^xsd:dateTime)\n" +
            "      (wd:Q849580 wd:Q28861260 \"Fractievoorzitter van de SP\"@nl \"2023-12-13T00:00:00Z\"^^xsd:dateTime)\n" +
            "      (wd:Q60172 wd:Q51796208 \"Partijleider van de SGP\"@nl \"2023-08-25T00:00:00Z\"^^xsd:dateTime)\n" +
            "    }\n" +
            "  }\n" +
            "  FILTER(!BOUND(?endDate) || ?endDate >= \"2023-01-01\"^^xsd:dateTime)\n" +
            "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"nl,en\". }\n" +
            "}";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public NlPepPoliticalPartiesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    NlPepPoliticalPartiesParser(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        fetchLive(rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d dirigeants des partis politiques des Pays-Bas indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            fetchLive(rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://www.tweedekamer.nl/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        String endpoint = WIKIDATA_SPARQL_URL + "?format=json&query=" +
                java.net.URLEncoder.encode(SPARQL_QUERY, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .header("Accept", "application/sparql-results+json, application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        log.info("Interrogation de Wikidata SPARQL pour les partis politiques néerlandais : {}", endpoint);
        HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() != 200) {
            throw new IOException("Échec requête SPARQL Wikidata (HTTP " + resp.statusCode() + ")");
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant politique trouvé dans : " + rawFile);
        }

        Set<String> seen = new HashSet<>();
        int count = 0;
        for (JsonNode b : bindings) {
            String personUri = b.path("person").path("value").asText("");
            String name = b.path("personLabel").path("value").asText("").trim();
            String partyName = b.path("partyLabel").path("value").asText("").trim();
            String role = b.path("roleLabel").path("value").asText("Partijleider");
            String startDate = b.path("startDate").path("value").asText("");
            String endDate = b.path("endDate").path("value").asText("");

            if (startDate.length() >= 10) startDate = startDate.substring(0, 10);
            if (endDate.length() >= 10) endDate = endDate.substring(0, 10);

            if (name.isEmpty() || name.startsWith("Q") || !seen.add(personUri)) {
                continue;
            }

            String fullPosition = role + (!partyName.isEmpty() ? " - " + partyName : "");

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", fullPosition);
            person.addValue("sourceUrl", personUri.isEmpty() ? "https://www.tweedekamer.nl/" : personUri);
            dataset.addEntity(person);

            FtmEntity posEntity = dataset.newEntity("Position", "party-" + person.getId());
            posEntity.addValue("name", fullPosition);
            posEntity.addValue("country", COUNTRY);
            if (!partyName.isEmpty()) {
                posEntity.addValue("organization", partyName);
            }
            posEntity.addValue("topics", TOPIC);
            dataset.addEntity(posEntity);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", posEntity.getId());
            occupancy.addValue("status", !endDate.isEmpty() ? "ended" : "current");
            if (!startDate.isEmpty()) {
                occupancy.addValue("startDate", startDate);
            }
            if (!endDate.isEmpty()) {
                occupancy.addValue("endDate", endDate);
            }
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}
