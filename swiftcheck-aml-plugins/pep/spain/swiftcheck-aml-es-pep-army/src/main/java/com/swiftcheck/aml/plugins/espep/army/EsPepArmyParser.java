package com.swiftcheck.aml.plugins.espep.army;

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
 * <b>es_pep_army</b> – Haut commandement des Forces armées espagnoles (Fuerzas Armadas).
 *
 * <p>Couvre la catégorie <b>ES-e</b>, fonction <b>Chef d'état-major ou officier général</b>
 * (Ley 10/2010, art. 14.2 ; JO C/2023/724).
 *
 * <p>JEMAD, JEME (Terre), AJEMA (Armada), JEMA (Air et Espace), Guardia Civil.
 * Données extraites dynamiquement via le point SPARQL de Wikidata (CC0) sans aucun fichier figé en interne.
 */
@Extension
public class EsPepArmyParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepArmyParser.class);

    static final String ID_PREFIX = "es-pep-army";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "es";
    static final String JSON_FILE = "spanish-military-leaders-wikidata.json";
    static final String DEFAULT_SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Espagne - commandement militaire)";

    private static final String SPARQL_QUERY =
            "SELECT ?office ?officeLabel ?person ?personLabel ?startDate ?endDate ?birthDate ?genderLabel WHERE {\n" +
            "  VALUES ?office {\n" +
            "    wd:Q5928975  # JEMAD (Jefe de Estado Mayor de la Defensa)\n" +
            "    wd:Q30612644 # JEME (Jefe de Estado Mayor del Ejército de Tierra)\n" +
            "    wd:Q30612645 # AJEMA (Jefe de Estado Mayor de la Armada)\n" +
            "    wd:Q30612648 # JEMA (Jefe de Estado Mayor del Ejército de Aire y del Espacio)\n" +
            "    wd:Q36378291 # Director General de la Guardia Civil\n" +
            "  }\n" +
            "  ?person p:P39 ?stmt .\n" +
            "  ?stmt ps:P39 ?office .\n" +
            "  OPTIONAL { ?stmt pq:P580 ?startDate . }\n" +
            "  OPTIONAL { ?stmt pq:P582 ?endDate . }\n" +
            "  OPTIONAL { ?person wdt:P569 ?birthDate . }\n" +
            "  OPTIONAL { ?person wdt:P21 ?gender . }\n" +
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
                String.format(Locale.ROOT, "%d hauts commandants des Forces armées espagnoles indexés.", count)
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
                        "https://www.defensa.gob.es/"));
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
        log.info("Forces armées espagnoles : données fraîches téléchargées depuis Wikidata SPARQL dans {}", rawFile);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun commandant trouvé dans le résultat Wikidata : " + rawFile);
        }

        int count = 0;
        for (JsonNode b : bindings) {
            String name = b.path("personLabel").path("value").asText("").trim();
            if (name.isEmpty() || name.startsWith("http")) {
                continue;
            }
            String office = b.path("officeLabel").path("value").asText("").trim();
            String birthDateFull = b.path("birthDate").path("value").asText("").trim();
            String birthDate = birthDateFull.length() >= 10 ? birthDateFull.substring(0, 10) : "";
            String startDateFull = b.path("startDate").path("value").asText("").trim();
            String startDate = startDateFull.length() >= 10 ? startDateFull.substring(0, 10) : "";
            String endDateFull = b.path("endDate").path("value").asText("").trim();
            String endDate = endDateFull.length() >= 10 ? endDateFull.substring(0, 10) : "";

            String genderLabel = b.path("genderLabel").path("value").asText("").trim().toLowerCase(Locale.ROOT);
            String gender = genderLabel.contains("femenino") || genderLabel.contains("female") ? "female" :
                    (genderLabel.contains("masculino") || genderLabel.contains("male") ? "male" : "");

            String status = endDate.isEmpty() ? "current" : "former";

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", office);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.defensa.gob.es/");
            dataset.addEntity(person);

            String posSlug = office.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", office);
            position.addValue("country", COUNTRY);
            position.addValue("organization", "Fuerzas Armadas Españolas");
            position.addValue("topics", "gov.national;mil");
            position.addValue("sourceUrl", "https://www.defensa.gob.es/");
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
            occupancy.addValue("sourceUrl", "https://www.defensa.gob.es/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}
