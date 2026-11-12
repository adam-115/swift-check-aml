package com.swiftcheck.aml.plugins.grpep.rca;

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
 * <b>gr_pep_rca</b> – Membres de la famille et proches associés de PEP grecques (RCA).
 *
 * <p>Couvre la catégorie <b>GR-RCA</b>, fonctions:
 * <ul>
 *   <li><b>Membre de la famille d'une PEP grecque</b> (fonction 669)</li>
 *   <li><b>Proche associé d'une PEP grecque</b> (fonction 670)</li>
 * </ul>
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via Wikidata SPARQL.
 */
@Extension
public class GrPepRcaParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepRcaParser.class);

    static final String ID_PREFIX = "gr-pep-rca";
    static final String TOPIC = "role.rca";
    static final String COUNTRY = "gr";
    static final String JSON_FILE = "gr-rca-wikidata.json";

    static final String DEFAULT_SPARQL = """
            SELECT DISTINCT ?leader ?leaderLabel ?person ?personLabel ?personLabelEl ?rel ?birthDate WHERE {
              ?leader p:P39 ?st .
              ?st ps:P39 ?pos .
              VALUES ?pos { wd:Q4377230 wd:Q3409203 } # Prime Minister or President of Greece
              ?st pq:P580 ?start .
              FILTER(?start >= "1974-01-01T00:00:00Z"^^xsd:dateTime)
              {
                ?leader p:P26 ?stmt .
                ?stmt ps:P26 ?person .
                BIND("spouse" AS ?rel)
              } UNION {
                ?leader p:P451 ?stmt .
                ?stmt ps:P451 ?person .
                BIND("partner" AS ?rel)
              } UNION {
                ?leader p:P40 ?stmt .
                ?stmt ps:P40 ?person .
                BIND("child" AS ?rel)
              } UNION {
                ?leader p:P3373 ?stmt .
                ?stmt ps:P3373 ?person .
                BIND("sibling" AS ?rel)
              } UNION {
                ?leader p:P22 ?stmt .
                ?stmt ps:P22 ?person .
                BIND("parent" AS ?rel)
              } UNION {
                ?leader p:P25 ?stmt .
                ?stmt ps:P25 ?person .
                BIND("parent" AS ?rel)
              }
              OPTIONAL { ?person wdt:P569 ?birthDate . }
              OPTIONAL { ?person rdfs:label ?personLabelEl . FILTER(LANG(?personLabelEl) = "el") }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en,el". }
            }
            ORDER BY ?leaderLabel ?rel
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public GrPepRcaParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GrPepRcaParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d proches et membres de la famille de PEP helléniques indexés.", count)
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
                        "https://www.hellenicparliament.gr/"));
            }
        }
        return records;
    }

    private void fetchLive(Path rawFile, ParserContext ctx) throws IOException, InterruptedException {
        String endpoint = ctx.parameters().getOrDefault("endpoint", "https://query.wikidata.org/sparql");
        String query = ctx.parameters().getOrDefault("sparql", DEFAULT_SPARQL);

        URI uri = URI.create(endpoint + "?query=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json");

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "SwiftCheckAmlBot/1.0 (gr_pep_rca; contact@swiftcheck.com)")
                .header("Accept", "application/sparql-results+json, application/json")
                .GET()
                .build();

        HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IOException("Erreur HTTP " + res.statusCode() + " lors de la requête Wikidata SPARQL: "
                    + new String(res.body(), StandardCharsets.UTF_8));
        }

        Files.write(rawFile, res.body());
        log.info("Données SPARQL RCA grecques téléchargées : {} octets", res.body().length);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun résultat SPARQL dans : " + rawFile);
        }

        Map<String, RcaData> relatives = new LinkedHashMap<>();

        for (JsonNode b : bindings) {
            String personUri = b.path("person").path("value").asText("").trim();
            if (personUri.isEmpty()) {
                continue;
            }
            String personQid = personUri.substring(personUri.lastIndexOf('/') + 1);

            String personName = b.path("personLabel").path("value").asText("").trim();
            if (personName.isEmpty() || (personName.startsWith("Q") && personName.matches("Q\\d+"))) {
                continue;
            }

            String leaderUri = b.path("leader").path("value").asText("").trim();
            String leaderQid = leaderUri.substring(leaderUri.lastIndexOf('/') + 1);
            String leaderName = b.path("leaderLabel").path("value").asText("").trim();
            if (leaderName.isEmpty()) {
                leaderName = "Greek Leader";
            }

            String rel = b.path("rel").path("value").asText("family").trim();
            String elName = b.path("personLabelEl").path("value").asText("").trim();
            String birthDate = cleanDate(b.path("birthDate").path("value").asText(""));

            RcaData rd = relatives.computeIfAbsent(personQid, k -> new RcaData(personQid, personName));
            if (!elName.isEmpty() && !elName.equals(personName)) {
                rd.aliases.add(elName);
            }
            if (!birthDate.isEmpty() && rd.birthDate.isEmpty()) {
                rd.birthDate = birthDate;
            }

            // Normalization for names with dashes like Mareva Grabowski–Mitsotakis
            String normalized = personName.replace('–', '-').trim();
            if (!normalized.equals(personName)) {
                rd.aliases.add(normalized);
            }
            if (normalized.endsWith("s")) {
                rd.aliases.add(normalized.substring(0, normalized.length() - 1));
            }

            rd.links.add(new RelLink(leaderQid, leaderName, rel));
        }

        if (relatives.isEmpty()) {
            throw new IllegalStateException("Aucun proche trouvé dans : " + rawFile);
        }

        int count = 0;
        for (RcaData rd : relatives.values()) {
            FtmEntity person = dataset.newEntity("Person", rd.qid);
            person.addValue("name", rd.name);
            for (String alias : rd.aliases) {
                if (!alias.equalsIgnoreCase(rd.name)) {
                    person.addValue("alias", alias);
                    person.addValue("name", alias);
                }
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!rd.birthDate.isEmpty()) {
                person.addValue("birthDate", rd.birthDate);
            }
            person.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + rd.qid);
            dataset.addEntity(person);

            for (RelLink link : rd.links) {
                FtmEntity leader = dataset.newEntity("Person", link.leaderQid);
                leader.addValue("name", link.leaderName);
                leader.addValue("country", COUNTRY);
                leader.addValue("topics", "role.pep");
                leader.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + link.leaderQid);
                dataset.addEntity(leader);

                String relId = rd.qid + "-rel-" + link.leaderQid;
                FtmEntity family = dataset.newEntity("Family", relId);
                family.addValue("person", person.getId());
                family.addValue("relative", leader.getId());
                family.addValue("relationship", link.relationship);
                dataset.addEntity(family);
            }

            count++;
        }

        return count;
    }

    private String cleanDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank() || dateStr.startsWith("http")) {
            return "";
        }
        int tIdx = dateStr.indexOf('T');
        if (tIdx > 0) {
            dateStr = dateStr.substring(0, tIdx);
        }
        dateStr = dateStr.trim();
        if (dateStr.matches("^\\d{4}(-\\d{2}(-\\d{2})?)?")) {
            return dateStr;
        }
        return "";
    }

    private static class RcaData {
        final String qid;
        final String name;
        final List<String> aliases = new ArrayList<>();
        String birthDate = "";
        final List<RelLink> links = new ArrayList<>();

        RcaData(String qid, String name) {
            this.qid = qid;
            this.name = name;
        }
    }

    private static class RelLink {
        final String leaderQid;
        final String leaderName;
        final String relationship;

        RelLink(String leaderQid, String leaderName, String relationship) {
            this.leaderQid = leaderQid;
            this.leaderName = leaderName;
            this.relationship = relationship;
        }
    }
}
