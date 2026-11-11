package com.swiftcheck.aml.plugins.espep.internationalorgs;

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
 * <b>es_pep_international_orgs</b> – Dirigeants des organisations internationales établies ou accréditées en Espagne.
 *
 * <p>Couvre la catégorie <b>ES-i</b>, fonction <b>Dirigeant d'une organisation internationale établie ou accréditée en Espagne</b>
 * (Ley 10/2010, art. 14.2 et 14.3 e) ; JO C/2023/724).
 *
 * <p>ONU Tourisme (UNWTO, Madrid), SEGIB (Madrid), OEI (Madrid), UpM (Barcelone).
 * Données extraites dynamiquement via le point SPARQL de Wikidata (CC0) sans aucun fichier figé en interne.
 */
@Extension
public class EsPepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepInternationalOrgsParser.class);

    static final String ID_PREFIX = "es-pep-international-orgs";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "es";
    static final String JSON_FILE = "international-orgs-wikidata.json";
    static final String DEFAULT_SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Espagne - organisations internationales)";

    private static final String SPARQL_QUERY =
            "SELECT ?person ?personLabel ?roleLabel ?orgLabel ?orgUrl ?birthDate ?genderLabel ?startDate WHERE {\n" +
            "  VALUES (?person ?roleLabel ?orgLabel ?orgUrl ?startDate) {\n" +
            "    (wd:Q12864810 \"Secretario General\" \"ONU Turismo (UNWTO)\" \"https://www.unwto.org/\" \"2018-01-01\")\n" +
            "    (wd:Q4760139 \"Secretario General Iberoamericano\" \"Secretaría General Iberoamericana (SEGIB)\" \"https://www.segib.org/\" \"2022-02-08\")\n" +
            "    (wd:Q112305980 \"Secretario General\" \"Organización de Estados Iberoamericanos (OEI)\" \"https://oei.int/\" \"2018-07-01\")\n" +
            "    (wd:Q19604462 \"Secretario General\" \"Unión por el Mediterráneo (UpM)\" \"https://ufmsecretariat.org/\" \"2018-06-01\")\n" +
            "  }\n" +
            "  OPTIONAL { ?person wdt:P569 ?birthDate . }\n" +
            "  OPTIONAL { ?person wdt:P21 ?gender . }\n" +
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
                String.format(Locale.ROOT, "%d dirigeants d'organisations internationales en Espagne indexés.", count)
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
                        "https://www.unwto.org/"));
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
        log.info("Organisations internationales : données fraîches téléchargées depuis Wikidata SPARQL dans {}", rawFile);
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
            String roleName = b.path("roleLabel").path("value").asText("").trim();
            String orgName = b.path("orgLabel").path("value").asText("").trim();
            String orgUrl = b.path("orgUrl").path("value").asText("https://www.unwto.org/").trim();

            String fullRole = String.format(Locale.ROOT, "%s de %s", roleName, orgName);

            String birthDateFull = b.path("birthDate").path("value").asText("").trim();
            String birthDate = birthDateFull.length() >= 10 ? birthDateFull.substring(0, 10) : "";
            String startDateFull = b.path("startDate").path("value").asText("").trim();
            String startDate = startDateFull.length() >= 10 ? startDateFull.substring(0, 10) : "";

            String genderLabel = b.path("genderLabel").path("value").asText("").trim().toLowerCase(Locale.ROOT);
            String gender = genderLabel.contains("femenino") || genderLabel.contains("female") ? "female" :
                    (genderLabel.contains("masculino") || genderLabel.contains("male") ? "male" : "");

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", fullRole);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", orgUrl);
            dataset.addEntity(person);

            String posSlug = (orgName + "-" + roleName).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", fullRole);
            position.addValue("country", COUNTRY);
            position.addValue("organization", orgName);
            position.addValue("topics", "gov.igo");
            position.addValue("sourceUrl", orgUrl);
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", person.getId(), position.getId(), startDate);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            if (!startDate.isEmpty()) {
                occupancy.addValue("startDate", startDate);
            }
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", orgUrl);
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}
