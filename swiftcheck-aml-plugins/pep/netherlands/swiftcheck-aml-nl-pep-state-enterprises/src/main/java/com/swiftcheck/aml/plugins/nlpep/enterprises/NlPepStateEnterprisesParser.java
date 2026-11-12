package com.swiftcheck.aml.plugins.nlpep.enterprises;

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
 * <b>nl_pep_state_enterprises</b> – Dirigeants des entreprises publiques et participations de l'État néerlandais (Staatsdeelnemingen).
 *
 * <p>Couvre la catégorie <b>NL-g</b>, fonction <b>Organe dirigeant d'une entreprise publique</b> (fonction 389)
 * (Wwft art. 1 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API SPARQL de Wikidata.
 */
@Extension
public class NlPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(NlPepStateEnterprisesParser.class);

    static final String ID_PREFIX = "nl-pep-state-enterprises";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "nl";
    static final String JSON_FILE = "nl-state-enterprises.json";

    static final String WIKIDATA_SPARQL_URL = "https://query.wikidata.org/sparql";

    static final String SPARQL_QUERY =
            "SELECT DISTINCT ?org ?orgLabel ?person ?personLabel ?roleLabel ?startDate ?endDate WHERE {\n" +
            "  VALUES ?org {\n" +
            "    wd:Q23076   # Nederlandse Spoorwegen\n" +
            "    wd:Q4290932 # Royal Schiphol Group\n" +
            "    wd:Q1127569 # TenneT\n" +
            "    wd:Q918239  # Gasunie\n" +
            "    wd:Q181912  # KLM\n" +
            "    wd:Q2187039 # Havenbedrijf Rotterdam\n" +
            "    wd:Q1432506 # Urenco\n" +
            "  }\n" +
            "  {\n" +
            "    ?org p:P488 ?st .\n" +
            "    ?st ps:P488 ?person .\n" +
            "    BIND(\"Voorzitter van de Raad van Commissarissen/Bestuur\"@nl AS ?roleLabel)\n" +
            "    OPTIONAL { ?st pq:P580 ?startDate . }\n" +
            "    OPTIONAL { ?st pq:P582 ?endDate . }\n" +
            "  } UNION {\n" +
            "    ?org p:P1037 ?st .\n" +
            "    ?st ps:P1037 ?person .\n" +
            "    BIND(\"CEO / President-directeur\"@nl AS ?roleLabel)\n" +
            "    OPTIONAL { ?st pq:P580 ?startDate . }\n" +
            "    OPTIONAL { ?st pq:P582 ?endDate . }\n" +
            "  } UNION {\n" +
            "    VALUES (?org ?person ?roleLabel ?startDate) {\n" +
            "      (wd:Q23076 wd:Q2595414 \"President-directeur en CEO van NS\"@nl \"2022-11-01T00:00:00Z\"^^xsd:dateTime)\n" +
            "      (wd:Q4290932 wd:Q131986203 \"President-directeur en CEO van Royal Schiphol Group\"@nl \"2024-06-01T00:00:00Z\"^^xsd:dateTime)\n" +
            "      (wd:Q1127569 wd:Q108483360 \"CEO van TenneT\"@nl \"2018-09-01T00:00:00Z\"^^xsd:dateTime)\n" +
            "      (wd:Q181912 wd:Q101627126 \"President-directeur en CEO van KLM\"@nl \"2022-07-01T00:00:00Z\"^^xsd:dateTime)\n" +
            "      (wd:Q2187039 wd:Q141553001 \"CEO van Havenbedrijf Rotterdam\"@nl \"2024-02-01T00:00:00Z\"^^xsd:dateTime)\n" +
            "    }\n" +
            "  }\n" +
            "  FILTER(!BOUND(?endDate) || ?endDate >= \"2020-01-01\"^^xsd:dateTime)\n" +
            "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"nl,mul,en\". }\n" +
            "}";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public NlPepStateEnterprisesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    NlPepStateEnterprisesParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants des entreprises publiques d'État des Pays-Bas indexés.", count)
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
                        "https://www.rijksoverheid.nl/"));
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

        log.info("Interrogation de Wikidata SPARQL pour les entreprises d'État néerlandaises : {}", endpoint);
        HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() != 200) {
            throw new IOException("Échec requête SPARQL Wikidata (HTTP " + resp.statusCode() + ")");
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant d'entreprise publique trouvé dans : " + rawFile);
        }

        Set<String> seen = new HashSet<>();
        int count = 0;
        for (JsonNode b : bindings) {
            String personUri = b.path("person").path("value").asText("");
            String name = b.path("personLabel").path("value").asText("").trim();
            String orgName = b.path("orgLabel").path("value").asText("").trim();
            String role = b.path("roleLabel").path("value").asText("Bestuurslid");
            String startDate = b.path("startDate").path("value").asText("");
            String endDate = b.path("endDate").path("value").asText("");

            if (startDate.length() >= 10) startDate = startDate.substring(0, 10);
            if (endDate.length() >= 10) endDate = endDate.substring(0, 10);

            if (name.isEmpty() || name.startsWith("Q") || !seen.add(personUri)) {
                continue;
            }

            String fullPosition = role + (!orgName.isEmpty() ? " - " + orgName : "");

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", fullPosition);
            person.addValue("sourceUrl", personUri.isEmpty() ? "https://www.rijksoverheid.nl/" : personUri);
            dataset.addEntity(person);

            FtmEntity posEntity = dataset.newEntity("Position", "enterprise-" + person.getId());
            posEntity.addValue("name", fullPosition);
            posEntity.addValue("country", COUNTRY);
            if (!orgName.isEmpty()) {
                posEntity.addValue("organization", orgName);
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
