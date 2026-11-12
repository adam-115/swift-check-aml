package com.swiftcheck.aml.plugins.plpep.rca;

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
 * <b>pl_pep_rca</b> – Membres de la famille et proches associés des personnalités politiques polonaises (RCA).
 *
 * <p>Couvre la catégorie <b>PL-RCA</b>, fonctions <b>Membre de la famille</b> (497) et <b>Proche associé</b> (498)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via Wikidata SPARQL.
 */
@Extension
public class PlPepRcaParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepRcaParser.class);

    static final String ID_PREFIX = "pl-pep-rca";
    static final String TOPIC = "role.rca";
    static final String COUNTRY = "pl";
    static final String JSON_FILE = "pl-rca-sparql.json";

    static final String DEFAULT_SPARQL = """
            SELECT DISTINCT ?pep ?pepLabel ?rca ?rcaLabel ?rel ?birthDate WHERE {
              VALUES ?pep {
                wd:Q9151911   # Andrzej Duda
                wd:Q946       # Donald Tusk
                wd:Q11771436  # Mateusz Morawiecki
                wd:Q9167733   # Beata Szydło
                wd:Q263158    # Ewa Kopacz
                wd:Q55917     # Jarosław Kaczyński
                wd:Q444       # Lech Wałęsa
                wd:Q55758     # Aleksander Kwaśniewski
                wd:Q15764     # Bronisław Komorowski
                wd:Q9382218   # Władysław Kosiniak-Kamysz
                wd:Q9352125   # Szymon Hołownia
                wd:Q11749117  # Krzysztof Bosak
                wd:Q9382594   # Włodzimierz Czarzasty
                wd:Q55913     # Radosław Sikorski
                wd:Q17704749  # Karol Nawrocki
              }
              {
                ?pep wdt:P26 ?rca . BIND("spouse" AS ?rel)
              } UNION {
                ?pep wdt:P40 ?rca . BIND("child" AS ?rel)
              } UNION {
                ?pep wdt:P22 ?rca . BIND("father" AS ?rel)
              } UNION {
                ?pep wdt:P25 ?rca . BIND("mother" AS ?rel)
              } UNION {
                ?pep wdt:P3373 ?rca . BIND("sibling" AS ?rel)
              } UNION {
                ?pep wdt:P1038 ?rca . BIND("relative" AS ?rel)
              }
              OPTIONAL { ?rca wdt:P569 ?birthDate . }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "pl,mul,en". }
            }
            ORDER BY ?pepLabel ?rel ?rcaLabel
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PlPepRcaParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PlPepRcaParser(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        fetchLive(rawFile, ctx);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d membres de la famille et proches associés de PPE polonaises indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            fetchLive(rawFile, ctx);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://www.sejm.gov.pl/"));
            }
        }
        return records;
    }

    private void fetchLive(Path rawFile, ParserContext ctx) throws IOException, InterruptedException {
        String sparql = ctx.parameters().getOrDefault("sparql_query", DEFAULT_SPARQL);
        String endpoint = ctx.parameters().getOrDefault("sparql_endpoint", "https://query.wikidata.org/sparql");
        String uri = endpoint + "?query=" + java.net.URLEncoder.encode(sparql, StandardCharsets.UTF_8) + "&format=json";

        log.info("Interrogation SPARQL Wikidata pour RCA Pologne : {}", endpoint);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .header("Accept", "application/sparql-results+json, application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Échec requête SPARQL (" + response.statusCode() + ") : " + response.body());
        }

        Files.writeString(rawFile, response.body(), StandardCharsets.UTF_8);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucune relation RCA trouvée dans : " + rawFile);
        }

        Set<String> seenRca = new HashSet<>();
        int count = 0;

        for (JsonNode b : bindings) {
            String rcaName = b.path("rcaLabel").path("value").asText("").trim();
            String pepName = b.path("pepLabel").path("value").asText("").trim();
            String relationship = b.path("rel").path("value").asText("relative").trim();
            String birthDate = b.path("birthDate").path("value").asText("").trim();

            if (rcaName.isEmpty() || pepName.isEmpty()) {
                continue;
            }
            if (birthDate.contains("T")) {
                birthDate = birthDate.substring(0, birthDate.indexOf("T"));
            }

            // RCA Person
            FtmEntity rcaPerson = dataset.newEntity("Person", rcaName);
            rcaPerson.addValue("name", rcaName);
            rcaPerson.addValue("country", COUNTRY);
            rcaPerson.addValue("nationality", COUNTRY);
            if (!birthDate.isEmpty()) {
                rcaPerson.addValue("birthDate", birthDate);
            }
            rcaPerson.addValue("topics", TOPIC);
            rcaPerson.addValue("sourceUrl", "https://www.wikidata.org/");
            dataset.addEntity(rcaPerson);

            // Target PEP Person
            FtmEntity pepPerson = dataset.newEntity("Person", pepName);
            pepPerson.addValue("name", pepName);
            pepPerson.addValue("country", COUNTRY);
            pepPerson.addValue("topics", "role.pep");
            pepPerson.addValue("sourceUrl", "https://www.wikidata.org/");
            dataset.addEntity(pepPerson);

            // Family relationship edge
            FtmEntity edge = dataset.newEntity("Family", "rel-" + rcaPerson.getId() + "-" + pepPerson.getId());
            edge.addValue("person", pepPerson.getId());
            edge.addValue("relative", rcaPerson.getId());
            edge.addValue("relationship", relationship);
            dataset.addEntity(edge);

            if (seenRca.add(rcaPerson.getId())) {
                count++;
            }
        }

        return count;
    }
}
