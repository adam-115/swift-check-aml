package com.swiftcheck.aml.plugins.grpep.centralbank;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * <b>gr_pep_central_bank</b> – Conseil de la Banque de Grèce (Τράπεζα της Ελλάδος).
 *
 * <p>Couvre la catégorie <b>GR-e</b>, fonction <b>Membre du conseil de la Banque de Grèce</b> (fonction 664)
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via Wikidata SPARQL.
 */
@Extension
public class GrPepCentralBankParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepCentralBankParser.class);

    static final String ID_PREFIX = "gr-pep-central-bank";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String JSON_FILE = "gr-central-bank-sparql.json";

    static final String DEFAULT_SPARQL = """
            SELECT DISTINCT ?person ?personLabel ?personLabelEl ?role ?roleLabel ?startDate ?endDate ?birthDate WHERE {
              {
                wd:Q806700 p:P1037 ?st . # director/manager
                ?st ps:P1037 ?person .
                BIND("Governor of the Bank of Greece" AS ?role)
              } UNION {
                wd:Q806700 p:P169 ?st . # chief executive officer
                ?st ps:P169 ?person .
                BIND("Governor of the Bank of Greece" AS ?role)
              } UNION {
                wd:Q806700 p:P488 ?st . # chairperson
                ?st ps:P488 ?person .
                BIND("Governor of the Bank of Greece" AS ?role)
              } UNION {
                ?person p:P39 ?st .
                ?st ps:P39 wd:Q16327315 . # position: Governor of Bank of Greece
                BIND("Governor of the Bank of Greece" AS ?role)
              } UNION {
                ?person p:P108 ?st . # employer Bank of Greece
                ?st ps:P108 wd:Q806700 .
                OPTIONAL { ?st pq:P39 ?pos . }
                BIND("Member of the General Council of the Bank of Greece" AS ?role)
              }
              OPTIONAL { ?st pq:P580 ?startDate . }
              OPTIONAL { ?st pq:P582 ?endDate . }
              OPTIONAL { ?person wdt:P569 ?birthDate . }
              OPTIONAL { ?person rdfs:label ?personLabelEl . FILTER(LANG(?personLabelEl) = "el") }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en,el". }
            }
            ORDER BY ?role DESC(?startDate)
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public GrPepCentralBankParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GrPepCentralBankParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d membres du conseil de la Banque de Grèce indexés.", count)
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
                        "https://www.bankofgreece.gr/"));
            }
        }
        return records;
    }

    private void fetchLive(Path rawFile, ParserContext ctx) throws IOException, InterruptedException {
        String sparql = ctx.parameters().getOrDefault("sparql_query", DEFAULT_SPARQL);
        String endpoint = ctx.parameters().getOrDefault("sparql_endpoint", "https://query.wikidata.org/sparql");
        String uri = endpoint + "?query=" + java.net.URLEncoder.encode(sparql, StandardCharsets.UTF_8) + "&format=json";

        log.info("Interrogation SPARQL Wikidata pour Banque de Grèce : {}", endpoint);
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
            throw new IllegalStateException("Aucun membre trouvé dans : " + rawFile);
        }

        Map<String, BankOfficial> officials = new LinkedHashMap<>();

        for (JsonNode b : bindings) {
            String name = b.path("personLabel").path("value").asText("").trim();
            String nameEl = b.path("personLabelEl").path("value").asText("").trim();
            String role = b.path("role").path("value").asText("Member of the General Council of the Bank of Greece").trim();
            String startDate = b.path("startDate").path("value").asText("").trim();
            String endDate = b.path("endDate").path("value").asText("").trim();
            String birthDate = b.path("birthDate").path("value").asText("").trim();

            if (name.isEmpty() || name.startsWith("Q") && name.matches("Q\\d+")) {
                if (!nameEl.isEmpty()) {
                    name = nameEl;
                } else {
                    continue;
                }
            }

            if (startDate.contains("T")) {
                startDate = startDate.substring(0, startDate.indexOf("T"));
            }
            if (endDate.contains("T")) {
                endDate = endDate.substring(0, endDate.indexOf("T"));
            }
            if (birthDate.contains("T")) {
                birthDate = birthDate.substring(0, birthDate.indexOf("T"));
            }

            officials.putIfAbsent(name, new BankOfficial(name, nameEl, role, startDate, endDate, birthDate));
        }

        if (officials.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant extrait depuis " + rawFile);
        }

        int count = 0;
        for (BankOfficial off : officials.values()) {
            FtmEntity person = dataset.newEntity("Person", off.name);
            person.addValue("name", off.name);
            if (!off.nameEl.isEmpty()) {
                person.addValue("name", off.nameEl);
                person.addValue("alias", off.nameEl);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!off.birthDate.isEmpty()) {
                person.addValue("birthDate", off.birthDate);
            }
            person.addValue("position", off.role);
            person.addValue("sourceUrl", "https://www.bankofgreece.gr/");
            dataset.addEntity(person);

            String posSlug = off.role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", off.role);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Τράπεζα της Ελλάδος (Banque de Grèce)");
            pos.addValue("sourceUrl", "https://www.bankofgreece.gr/");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            if (!off.startDate.isEmpty()) {
                occupancy.addValue("startDate", off.startDate);
            }
            if (!off.endDate.isEmpty()) {
                occupancy.addValue("endDate", off.endDate);
                occupancy.addValue("status", "ended");
            } else {
                occupancy.addValue("status", "current");
            }
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private static class BankOfficial {
        final String name;
        final String nameEl;
        final String role;
        final String startDate;
        final String endDate;
        final String birthDate;

        BankOfficial(String name, String nameEl, String role, String startDate, String endDate, String birthDate) {
            this.name = name;
            this.nameEl = nameEl;
            this.role = role;
            this.startDate = startDate;
            this.endDate = endDate;
            this.birthDate = birthDate;
        }
    }
}
