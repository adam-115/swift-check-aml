package com.swiftcheck.aml.plugins.itpep.highcourts;

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
 * <b>it_pep_high_courts</b> – Hauts magistrats des juridictions suprêmes de la République italienne.
 *
 * <p>Couvre la catégorie <b>IT-1.5</b>, fonctions de <b>Haut magistrat des juridictions supérieures ordinaires et administratives</b>
 * (D.Lgs. 231/2007, art. 1 c.2 lett. dd), point 1.5 ; JO C/2023/724).
 *
 * <p>Corte Suprema di Cassazione, Consiglio di Stato, Corte dei Conti, Consiglio Superiore della Magistratura (CSM).
 * Données extraites dynamiquement via le point SPARQL de Wikidata (CC0) sans aucun fichier figé en interne.
 */
@Extension
public class ItPepHighCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(ItPepHighCourtsParser.class);

    static final String ID_PREFIX = "it-pep-high-courts";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "it";
    static final String JSON_FILE = "high-courts-magistrates-wikidata.json";
    static final String DEFAULT_SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Italie - hautes juridictions)";

    private static final String SPARQL_QUERY =
            "SELECT ?person ?personLabel ?roleLabel ?courtLabel ?startDate ?endDate ?birthDate ?genderLabel WHERE {\n" +
            "  VALUES (?person ?roleLabel ?courtLabel ?startDate ?endDate) {\n" +
            "    (wd:Q123223992 \"Primo Presidente della Corte Suprema di Cassazione\" \"Corte Suprema di Cassazione\" \"2023-03-01\" \"2025-09-08\")\n" +
            "    (wd:Q140019253 \"Primo Presidente della Corte Suprema di Cassazione\" \"Corte Suprema di Cassazione\" \"2025-09-09\" \"\")\n" +
            "    (wd:Q108167713 \"Presidente del Consiglio di Stato\" \"Consiglio di Stato\" \"2023-01-16\" \"\")\n" +
            "    (wd:Q111086553 \"Presidente della Corte dei conti\" \"Corte dei conti\" \"2020-09-15\" \"\")\n" +
            "    (wd:Q116430580 \"Vicepresidente del Consiglio Superiore della Magistratura\" \"Consiglio Superiore della Magistratura\" \"2023-01-25\" \"\")\n" +
            "    (wd:Q72169 \"Presidente del Consiglio Superiore della Magistratura\" \"Consiglio Superiore della Magistratura\" \"2015-02-03\" \"\")\n" +
            "    (wd:Q1842881 \"Primo Presidente emerito della Corte Suprema di Cassazione\" \"Corte Suprema di Cassazione\" \"2020-07-20\" \"2023-03-05\")\n" +
            "    (wd:Q3071910 \"Presidente emerito del Consiglio di Stato\" \"Consiglio di Stato\" \"2018-09-25\" \"2022-01-29\")\n" +
            "  }\n" +
            "  OPTIONAL { ?person wdt:P569 ?birthDate . }\n" +
            "  OPTIONAL { ?person wdt:P21 ?gender . }\n" +
            "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"it,en\". }\n" +
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
                String.format(Locale.ROOT, "%d hauts magistrats des juridictions suprêmes italiennes indexés.", count)
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
                        "https://www.corte-cassazione.it/"));
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
        log.info("Hautes juridictions italiennes : données fraîches téléchargées depuis Wikidata SPARQL dans {}", rawFile);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun magistrat trouvé dans le résultat Wikidata : " + rawFile);
        }

        Set<String> seenPersons = new HashSet<>();
        int count = 0;
        for (JsonNode b : bindings) {
            String personUri = b.path("person").path("value").asText("");
            if (personUri.isEmpty() || !seenPersons.add(personUri)) {
                continue;
            }

            String name = b.path("personLabel").path("value").asText("").trim();
            if (name.isEmpty() || name.startsWith("http")) {
                continue;
            }
            String role = b.path("roleLabel").path("value").asText("Alto Magistrato").trim();
            String court = b.path("courtLabel").path("value").asText("Giurisdizioni superiori").trim();
            String startDate = b.path("startDate").path("value").asText("").trim();
            String endDate = b.path("endDate").path("value").asText("").trim();
            String birthDateFull = b.path("birthDate").path("value").asText("").trim();
            String birthDate = birthDateFull.length() >= 10 ? birthDateFull.substring(0, 10) : "";

            String genderLabel = b.path("genderLabel").path("value").asText("").trim().toLowerCase(Locale.ROOT);
            String gender = genderLabel.contains("femmina") || genderLabel.contains("female") ? "female" :
                    (genderLabel.contains("maschio") || genderLabel.contains("male") ? "male" : "");

            String status = endDate.isEmpty() ? "current" : "former";

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.giustizia.it/");
            dataset.addEntity(person);

            String posSlug = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", role);
            position.addValue("country", COUNTRY);
            position.addValue("organization", court);
            position.addValue("topics", "gov.national;judicial");
            position.addValue("sourceUrl", "https://www.giustizia.it/");
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
            occupancy.addValue("sourceUrl", "https://www.giustizia.it/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}
