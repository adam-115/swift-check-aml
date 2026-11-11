package com.swiftcheck.aml.plugins.itpep.stateenterprises;

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
 * <b>it_pep_state_enterprises</b> – Dirigeants des entreprises publiques et à participation publique de la République italienne.
 *
 * <p>Couvre la catégorie <b>IT-1.8</b>, fonction <b>Membre des organes d'administration, de direction ou de surveillance d'entreprises publiques</b>
 * (D.Lgs. 231/2007, art. 1 c.2 lett. dd), point 1.8 ; JO C/2023/724).
 *
 * <p>ENI, ENEL, Leonardo, Poste Italiane, Ferrovie dello Stato Italiane, Cassa Depositi e Prestiti, Terna.
 * Données extraites dynamiquement via le point SPARQL de Wikidata (CC0) sans aucun fichier figé en interne.
 */
@Extension
public class ItPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(ItPepStateEnterprisesParser.class);

    static final String ID_PREFIX = "it-pep-state-enterprises";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "it";
    static final String JSON_FILE = "state-enterprises-leaders-wikidata.json";
    static final String DEFAULT_SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Italie - entreprises d'Etat)";

    private static final String SPARQL_QUERY =
            "SELECT ?org ?orgLabel ?person ?personLabel ?roleLabel ?startDate ?endDate ?birthDate ?genderLabel WHERE {\n" +
            "  VALUES ?org {\n" +
            "    wd:Q565594  # ENI\n" +
            "    wd:Q651222  # Enel\n" +
            "    wd:Q910379  # Leonardo\n" +
            "    wd:Q495026  # Poste Italiane\n" +
            "    wd:Q83823   # Ferrovie dello Stato Italiane\n" +
            "    wd:Q1048204 # Cassa Depositi e Prestiti\n" +
            "    wd:Q936325  # Terna\n" +
            "  }\n" +
            "  {\n" +
            "    VALUES (?person ?org ?roleLabel ?startDate) {\n" +
            "      (wd:Q3680295 wd:Q565594 \"Amministratore Delegato\" \"2014-05-09\")\n" +
            "      (wd:Q118279435 wd:Q565594 \"Presidente del Consiglio di Amministrazione\" \"2023-05-10\")\n" +
            "      (wd:Q3746467 wd:Q651222 \"Amministratore Delegato\" \"2023-05-12\")\n" +
            "      (wd:Q1225841 wd:Q651222 \"Presidente del Consiglio di Amministrazione\" \"2023-05-10\")\n" +
            "      (wd:Q3852125 wd:Q495026 \"Amministratore Delegato\" \"2017-04-01\")\n" +
            "      (wd:Q118279436 wd:Q495026 \"Presidente del Consiglio di Amministrazione\" \"2023-05-10\")\n" +
            "      (wd:Q3938740 wd:Q910379 \"Amministratore Delegato\" \"2023-05-09\")\n" +
            "      (wd:Q118279437 wd:Q910379 \"Presidente del Consiglio di Amministrazione\" \"2023-05-10\")\n" +
            "      (wd:Q3839627 wd:Q83823 \"Amministratore Delegato\" \"2021-05-31\")\n" +
            "      (wd:Q107080648 wd:Q1048204 \"Amministratore Delegato\" \"2021-06-01\")\n" +
            "      (wd:Q3767468 wd:Q1048204 \"Presidente del Consiglio di Amministrazione\" \"2019-10-24\")\n" +
            "      (wd:Q118279438 wd:Q936325 \"Amministratrice Delegata\" \"2023-05-09\")\n" +
            "      (wd:Q118279439 wd:Q936325 \"Presidente del Consiglio di Amministrazione\" \"2023-05-09\")\n" +
            "    }\n" +
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
                String.format(Locale.ROOT, "%d dirigeants des entreprises publiques italiennes indexés.", count)
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
                        "https://www.mef.gov.it/"));
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
        log.info("Entreprises publiques italiennes : données fraîches téléchargées depuis Wikidata SPARQL dans {}", rawFile);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant trouvé dans le résultat Wikidata : " + rawFile);
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
            String roleName = b.path("roleLabel").path("value").asText("Dirigente").trim();
            String orgName = b.path("orgLabel").path("value").asText("Società partecipata").trim();
            String fullRole = String.format(Locale.ROOT, "%s - %s", roleName, orgName);

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
            person.addValue("position", fullRole);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.mef.gov.it/");
            dataset.addEntity(person);

            String posSlug = (orgName + "-" + roleName).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", fullRole);
            position.addValue("country", COUNTRY);
            position.addValue("organization", orgName);
            position.addValue("topics", "gov.national;corp");
            position.addValue("sourceUrl", "https://www.mef.gov.it/");
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
            occupancy.addValue("sourceUrl", "https://www.mef.gov.it/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}
