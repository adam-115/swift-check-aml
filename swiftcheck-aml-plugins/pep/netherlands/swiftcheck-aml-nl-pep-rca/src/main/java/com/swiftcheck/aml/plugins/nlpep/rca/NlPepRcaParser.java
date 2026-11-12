package com.swiftcheck.aml.plugins.nlpep.rca;

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
 * <b>nl_pep_rca</b> – Membres de la famille et proches associés de PEP néerlandaises (RCA).
 *
 * <p>Couvre la catégorie <b>NL-RCA</b>, fonctions <b>Membre de la famille d'une PEP néerlandaise</b> (fonction 392)
 * et <b>Proche associé d'une PEP néerlandaise</b> (fonction 393)
 * (Wwft art. 1 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API SPARQL de Wikidata.
 */
@Extension
public class NlPepRcaParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(NlPepRcaParser.class);

    static final String ID_PREFIX = "nl-pep-rca";
    static final String TOPIC = "role.rca";
    static final String COUNTRY = "nl";
    static final String JSON_FILE = "nl-rca.json";

    static final String WIKIDATA_SPARQL_URL = "https://query.wikidata.org/sparql";

    static final String SPARQL_QUERY =
            "SELECT DISTINCT ?person ?personLabel ?prop ?propLabel ?birthDate WHERE {\n" +
            "  VALUES ?king { wd:Q154952 } # Willem-Alexander\n" +
            "  VALUES ?prop { wdt:P26 wdt:P40 wdt:P25 wdt:P3373 } # spouse, children, mother, siblings\n" +
            "  ?king ?prop ?person .\n" +
            "  OPTIONAL { ?person wdt:P569 ?birthDate . }\n" +
            "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"nl,en\". }\n" +
            "}";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public NlPepRcaParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    NlPepRcaParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d proches et associés (RCA) de PEP néerlandaises indexés.", count)
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
                        "https://www.koninklijkhuis.nl/"));
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

        log.info("Interrogation de Wikidata SPARQL pour la famille royale néerlandaise : {}", endpoint);
        HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() != 200) {
            throw new IOException("Échec requête SPARQL Wikidata (HTTP " + resp.statusCode() + ")");
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun membre de la famille royale trouvé dans : " + rawFile);
        }

        Set<String> seen = new HashSet<>();
        int count = 0;
        for (JsonNode b : bindings) {
            String personUri = b.path("person").path("value").asText("");
            String name = b.path("personLabel").path("value").asText("").trim();
            String propUri = b.path("prop").path("value").asText("");
            String birthDate = b.path("birthDate").path("value").asText("");

            if (birthDate.length() >= 10) birthDate = birthDate.substring(0, 10);

            if (name.isEmpty() || name.startsWith("Q") || !seen.add(personUri)) {
                continue;
            }

            String relDesc;
            if (propUri.endsWith("P26")) {
                relDesc = "Echtgenote van Koning Willem-Alexander";
            } else if (propUri.endsWith("P40")) {
                relDesc = "Kind van Koning Willem-Alexander";
            } else if (propUri.endsWith("P25")) {
                relDesc = "Moeder van Koning Willem-Alexander (Voormalig Staatshoofd)";
            } else if (propUri.endsWith("P3373")) {
                relDesc = "Broer van Koning Willem-Alexander";
            } else {
                relDesc = "Familielid van Koning Willem-Alexander";
            }

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", relDesc);
            person.addValue("sourceUrl", personUri.isEmpty() ? "https://www.koninklijkhuis.nl/" : personUri);
            dataset.addEntity(person);

            FtmEntity rel = dataset.newEntity("Family", "rel-" + person.getId());
            rel.addValue("person", person.getId());
            rel.addValue("relationship", relDesc);
            dataset.addEntity(rel);

            count++;
        }
        return count;
    }
}
