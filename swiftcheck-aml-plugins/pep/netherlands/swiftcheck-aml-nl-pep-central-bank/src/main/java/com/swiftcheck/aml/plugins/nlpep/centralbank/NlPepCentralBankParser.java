package com.swiftcheck.aml.plugins.nlpep.centralbank;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <b>nl_pep_central_bank</b> – Membres de la direction et de la gouvernance de De Nederlandsche Bank (DNB).
 *
 * <p>Couvre la catégorie <b>NL-e</b>, fonction <b>Membre de la direction de De Nederlandsche Bank</b> (fonction 386)
 * (Wwft art. 1 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API SPARQL de Wikidata.
 */
@Extension
public class NlPepCentralBankParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(NlPepCentralBankParser.class);

    static final String ID_PREFIX = "nl-pep-central-bank";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "nl";
    static final String JSON_FILE = "nl-central-bank.json";

    static final String WIKIDATA_SPARQL_URL = "https://query.wikidata.org/sparql";

    static final String SPARQL_QUERY =
            "SELECT DISTINCT ?person ?personLabel ?roleLabel ?startDate ?endDate ?birthDate WHERE {\n" +
            "  {\n" +
            "    ?person p:P39 ?statement .\n" +
            "    ?statement ps:P39 wd:Q109549719 .\n" +
            "    BIND(\"President van De Nederlandsche Bank\"@nl AS ?roleLabel)\n" +
            "    OPTIONAL { ?statement pq:P580 ?startDate . }\n" +
            "    OPTIONAL { ?statement pq:P582 ?endDate . }\n" +
            "    FILTER(!BOUND(?endDate) || ?endDate >= \"2010-01-01\"^^xsd:dateTime)\n" +
            "  } UNION {\n" +
            "    VALUES (?person ?roleLabel ?startDate) {\n" +
            "      (wd:Q27305420 \"Directeur Toezicht van De Nederlandsche Bank\"@nl \"2021-04-01T00:00:00Z\"^^xsd:dateTime)\n" +
            "      (wd:Q102178084 \"Directeur van De Nederlandsche Bank\"@nl \"2011-07-01T00:00:00Z\"^^xsd:dateTime)\n" +
            "    }\n" +
            "    OPTIONAL { ?person p:P108 ?st . ?st ps:P108 wd:Q1180205 . OPTIONAL { ?st pq:P582 ?endDate . } }\n" +
            "  }\n" +
            "  OPTIONAL { ?person wdt:P569 ?birthDate . }\n" +
            "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"nl,en\". }\n" +
            "}";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public NlPepCentralBankParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    NlPepCentralBankParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants de De Nederlandsche Bank indexés.", count)
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
                        "https://www.dnb.nl/"));
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

        log.info("Interrogation de Wikidata SPARQL pour De Nederlandsche Bank : {}", endpoint);
        HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() != 200) {
            throw new IOException("Échec requête SPARQL Wikidata (HTTP " + resp.statusCode() + ")");
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant de De Nederlandsche Bank trouvé dans : " + rawFile);
        }

        Set<String> seen = new HashSet<>();
        int count = 0;
        for (JsonNode b : bindings) {
            String personUri = b.path("person").path("value").asText("");
            String name = b.path("personLabel").path("value").asText("").trim();
            if (name.isEmpty() || name.startsWith("Q") || !seen.add(personUri)) {
                continue;
            }

            String role = b.path("roleLabel").path("value").asText("Directielid van De Nederlandsche Bank");
            String birthDate = b.path("birthDate").path("value").asText("");
            String startDate = b.path("startDate").path("value").asText("");
            String endDate = b.path("endDate").path("value").asText("");

            if (birthDate.length() >= 10) birthDate = birthDate.substring(0, 10);
            if (startDate.length() >= 10) startDate = startDate.substring(0, 10);
            if (endDate.length() >= 10) endDate = endDate.substring(0, 10);

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", role);
            person.addValue("sourceUrl", personUri.isEmpty() ? "https://www.dnb.nl/" : personUri);
            dataset.addEntity(person);

            FtmEntity posEntity = dataset.newEntity("Position", "dnb-" + person.getId());
            posEntity.addValue("name", role);
            posEntity.addValue("country", COUNTRY);
            posEntity.addValue("organization", "De Nederlandsche Bank");
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
