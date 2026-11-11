package com.swiftcheck.aml.plugins.itpep.constitutionalcourt;

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
 * <b>it_pep_constitutional_court</b> – Juges de la Cour constitutionnelle italienne (Corte costituzionale).
 *
 * <p>Couvre la catégorie <b>IT-1.4</b>, fonction <b>Juge de la Cour constitutionnelle</b> (D.Lgs. 231/2007,
 * art. 1 c.2 lett. dd), point 1.4 ; liste notifiée, JO C/2023/724).
 *
 * <p>Les 15 juges constitutionnels nommés par le Président de la République, le Parlement et les magistratures suprêmes.
 * Données extraites dynamiquement via le point SPARQL de Wikidata (CC0) sans aucun fichier figé en interne.
 */
@Extension
public class ItPepConstitutionalCourtParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(ItPepConstitutionalCourtParser.class);

    static final String ID_PREFIX = "it-pep-constitutional-court";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "it";
    static final String JSON_FILE = "constitutional-judges-wikidata.json";
    static final String DEFAULT_SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Italie - Cour constitutionnelle)";

    private static final String SPARQL_QUERY =
            "SELECT ?person ?personLabel ?roleLabel ?birthDate ?genderLabel WHERE {\n" +
            "  VALUES (?person ?roleLabel) {\n" +
            "    (wd:Q43043781 \"Presidente della Corte costituzionale\")\n" +
            "    (wd:Q7002449 \"Giudice costituzionale\")\n" +
            "    (wd:Q84603168 \"Giudice costituzionale\")\n" +
            "    (wd:Q99014823 \"Giudice costituzionale\")\n" +
            "    (wd:Q104245368 \"Giudice costituzionale\")\n" +
            "    (wd:Q76743515 \"Giudice costituzionale\")\n" +
            "    (wd:Q50096130 \"Giudice costituzionale\")\n" +
            "    (wd:Q3071910 \"Giudice costituzionale\")\n" +
            "    (wd:Q86353014 \"Giudice costituzionale\")\n" +
            "    (wd:Q3767954 \"Giudice costituzionale\")\n" +
            "    (wd:Q123397518 \"Giudice costituzionale\")\n" +
            "    (wd:Q131748150 \"Giudice costituzionale\")\n" +
            "    (wd:Q3847090 \"Giudice costituzionale\")\n" +
            "    (wd:Q583533 \"Giudice costituzionale\")\n" +
            "    (wd:Q7002773 \"Giudice costituzionale\")\n" +
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
                String.format(Locale.ROOT, "%d juges de la Cour constitutionnelle italienne (Corte costituzionale).", count)
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
                        "https://www.cortecostituzionale.it/"));
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
        log.info("Corte costituzionale : données fraîches téléchargées depuis Wikidata SPARQL dans {}", rawFile);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun juge trouvé dans le résultat Wikidata : " + rawFile);
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
            String role = b.path("roleLabel").path("value").asText("Giudice costituzionale").trim();
            String birthDateFull = b.path("birthDate").path("value").asText("").trim();
            String birthDate = birthDateFull.length() >= 10 ? birthDateFull.substring(0, 10) : "";

            String genderLabel = b.path("genderLabel").path("value").asText("").trim().toLowerCase(Locale.ROOT);
            String gender = genderLabel.contains("femmina") || genderLabel.contains("female") ? "female" :
                    (genderLabel.contains("maschio") || genderLabel.contains("male") ? "male" : "");

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
            person.addValue("sourceUrl", "https://www.cortecostituzionale.it/");
            dataset.addEntity(person);

            String posSlug = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", role);
            position.addValue("country", COUNTRY);
            position.addValue("organization", "Corte costituzionale della Repubblica Italiana");
            position.addValue("topics", "gov.national;judicial");
            position.addValue("sourceUrl", "https://www.cortecostituzionale.it/");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", person.getId(), position.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", "https://www.cortecostituzionale.it/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}
