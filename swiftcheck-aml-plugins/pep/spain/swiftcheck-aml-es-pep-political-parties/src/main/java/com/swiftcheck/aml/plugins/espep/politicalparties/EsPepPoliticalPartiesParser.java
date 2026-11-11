package com.swiftcheck.aml.plugins.espep.politicalparties;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * <b>es_pep_political_parties</b> – Dirigeants des partis politiques représentés aux Cortes Generales.
 *
 * <p>Couvre la catégorie <b>ES-h</b>, fonction <b>Membre de la direction d'un parti représenté au Parlement</b>
 * (Ley 10/2010, art. 14.2 ; JO C/2023/724).
 *
 * <p>Données extraites dynamiquement via le point SPARQL de Wikidata (CC0) sans aucun fichier figé en interne.
 */
@Extension
public class EsPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "es-pep-political-parties";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "es";
    static final String JSON_FILE = "spanish-party-leaders-wikidata.json";
    static final String DEFAULT_SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Espagne - partis politiques)";

    private static final String SPARQL_QUERY =
            "SELECT ?party ?partyLabel ?person ?personLabel ?prop ?startDate ?endDate WHERE {\n" +
            "  VALUES ?party {\n" +
            "    wd:Q185088    # PP\n" +
            "    wd:Q138198    # PSOE\n" +
            "    wd:Q15630787  # Vox\n" +
            "    wd:Q112098527 # Sumar\n" +
            "    wd:Q150068    # ERC\n" +
            "    wd:Q97155800  # Junts\n" +
            "    wd:Q1029761   # EH Bildu\n" +
            "    wd:Q43093     # EAJ-PNV\n" +
            "    wd:Q885433    # BNG\n" +
            "    wd:Q1104327   # Coalicion Canaria\n" +
            "    wd:Q16059622  # Podemos\n" +
            "    wd:Q2001535   # UPN\n" +
            "  }\n" +
            "  VALUES ?prop { p:P488 p:P3975 p:P3320 }\n" +
            "  ?party ?prop ?stmt .\n" +
            "  ?stmt ?ps ?person .\n" +
            "  FILTER(?ps IN (ps:P488, ps:P3975, ps:P3320))\n" +
            "  OPTIONAL { ?stmt pq:P580 ?startDate . }\n" +
            "  OPTIONAL { ?stmt pq:P582 ?endDate . }\n" +
            "  FILTER(!bound(?endDate) || ?endDate >= \"2020-01-01\"^^xsd:dateTime)\n" +
            "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"es,en\". }\n" +
            "}";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        download(rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d dirigeants des partis politiques espagnols indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            download(rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://www.interior.gob.es/"));
            }
        }
        return records;
    }

    void download(Path rawFile) throws IOException, InterruptedException {
        String body = "query=" + URLEncoder.encode(SPARQL_QUERY, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(DEFAULT_SPARQL_ENDPOINT))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/sparql-results+json")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Wikidata SPARQL returned HTTP " + resp.statusCode() + " : " + resp.body());
        }
        Files.writeString(rawFile, resp.body(), StandardCharsets.UTF_8);
        log.info("Partis politiques espagnols : données fraîches téléchargées depuis Wikidata SPARQL dans {}", rawFile);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant trouvé dans le résultat Wikidata : " + rawFile);
        }

        int count = 0;
        for (JsonNode b : bindings) {
            String name = b.path("personLabel").path("value").asText("").trim();
            if (name.isEmpty() || name.startsWith("http")) {
                continue;
            }
            String party = b.path("partyLabel").path("value").asText("").trim();
            String propUri = b.path("prop").path("value").asText("");
            String roleName = propUri.endsWith("P3975") ? "Secretario/a General" :
                    (propUri.endsWith("P488") ? "Presidente/a" : "Líder");
            String fullRole = String.format(Locale.ROOT, "%s de %s", roleName, party);

            String startDateFull = b.path("startDate").path("value").asText("").trim();
            String startDate = startDateFull.length() >= 10 ? startDateFull.substring(0, 10) : "";
            String endDateFull = b.path("endDate").path("value").asText("").trim();
            String endDate = endDateFull.length() >= 10 ? endDateFull.substring(0, 10) : "";

            String status = endDate.isEmpty() ? "current" : "former";

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", fullRole);
            person.addValue("sourceUrl", "https://www.interior.gob.es/");
            dataset.addEntity(person);

            String posSlug = (party + "-" + roleName).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", fullRole);
            position.addValue("country", COUNTRY);
            position.addValue("organization", party);
            position.addValue("topics", "gov.national;pol.party");
            position.addValue("sourceUrl", "https://www.interior.gob.es/");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", person.getId(), position.getId(), startDate);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            if (!startDate.isEmpty()) {
                occupancy.addValue("startDate", startDate);
            }
            if (!endDate.isEmpty()) {
                occupancy.addValue("endDate", endDate);
            }
            occupancy.addValue("status", status);
            occupancy.addValue("sourceUrl", "https://www.interior.gob.es/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}
