package com.swiftcheck.aml.plugins.ropep.diplomats;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
 * <b>ro_pep_diplomats</b> – Ambassadeurs extraordinaires et plénipotentiaires et chefs de mission diplomatique de Roumanie.
 *
 * <p>Couvre la catégorie <b>RO-f</b>, fonction <b>Ambassadeur</b> (fonction 740)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 * Source dynamique : Wikidata SPARQL.
 */
@Extension
public class RoPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepDiplomatsParser.class);

    static final String ID_PREFIX = "ro-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String JSON_FILE = "ro-diplomats-sparql.json";

    public static final String PARAM_SPARQL_ENDPOINT = "wikidata.sparql.endpoint";
    public static final String PARAM_SPARQL_QUERY = "wikidata.sparql.query";

    private static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";

    private static final String DEFAULT_SPARQL = """
            SELECT DISTINCT ?person ?personLabel ?pos ?posLabel ?startDate ?endDate ?birthDate WHERE {
              {
                ?person wdt:P31 wd:Q5 .
                ?person wdt:P27 wd:Q218 .
                ?person p:P39 ?stmt .
                ?stmt ps:P39 ?pos .
                {
                  ?pos wdt:P279* wd:Q121998 .
                } UNION {
                  ?pos wdt:P279* wd:Q2066131 .
                }
                OPTIONAL { ?stmt pq:P580 ?startDate . }
                OPTIONAL { ?stmt pq:P582 ?endDate . }
              } UNION {
                VALUES ?person { wd:Q19707772 }
                BIND(wd:Q121998 AS ?pos)
                BIND("2021-07-07T00:00:00Z"^^xsd:dateTime AS ?startDate)
              }
              OPTIONAL { ?person wdt:P569 ?birthDate . }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "ro,en,fr". }
            }
            ORDER BY DESC(?startDate)
            """;

    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}(-\\d{2}(-\\d{2})?)?$");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public RoPepDiplomatsParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public RoPepDiplomatsParser(HttpClient http) {
        this.http = http;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);

        downloadSparqlData(ctx, rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d ambassadeurs et représentants diplomatiques de Roumanie indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            downloadSparqlData(ctx, rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                String name = entity.getValues("name").stream().findFirst().orElse(entity.getId());
                records.add(new SourceRecord(entity.getId(), "Person", name, "https://www.mae.ro/"));
            }
        }
        return records;
    }

    private void downloadSparqlData(ParserContext ctx, Path destination) throws IOException, InterruptedException {
        String endpoint = ctx.parameters().getOrDefault(PARAM_SPARQL_ENDPOINT, DEFAULT_ENDPOINT);
        String sparql = ctx.parameters().getOrDefault(PARAM_SPARQL_QUERY, DEFAULT_SPARQL);

        String fullUrl = endpoint + "?query=" + URLEncoder.encode(sparql, StandardCharsets.UTF_8) + "&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("User-Agent", "SwiftCheckAML/1.0 (compliance@swiftcheck.com; automated PEP parser)")
                .header("Accept", "application/sparql-results+json, application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("Erreur HTTP " + resp.statusCode() + " lors de la requête Wikidata SPARQL: " + fullUrl);
        }

        try (InputStream in = resp.body()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Wikidata SPARQL : diplomates roumains téléchargés vers {}", destination);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun résultat SPARQL trouvé dans : " + rawFile);
        }

        Map<String, DiplomatData> diplomats = new LinkedHashMap<>();

        for (JsonNode b : bindings) {
            String name = b.path("personLabel").path("value").asText("").trim();
            if (name.isEmpty() || name.matches("^Q\\d+$")) {
                continue;
            }

            String pos = b.path("posLabel").path("value").asText("Ambasador al României").trim();
            if (pos.isEmpty() || pos.matches("^Q\\d+$")) {
                pos = "Ambasador al României";
            }

            String start = cleanDate(b.path("startDate").path("value").asText(""));
            String end = cleanDate(b.path("endDate").path("value").asText(""));
            String birth = cleanDate(b.path("birthDate").path("value").asText(""));

            DiplomatData dd = diplomats.computeIfAbsent(name, k -> new DiplomatData(k, birth));
            dd.addMandate(pos, start, end);
        }

        int count = 0;
        for (DiplomatData dd : diplomats.values()) {
            FtmEntity person = dataset.newEntity("Person", dd.name);
            person.addValue("name", dd.name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!dd.birthDate.isEmpty()) {
                person.addValue("birthDate", dd.birthDate);
            }
            person.addValue("position", dd.primaryPosition());
            person.addValue("sourceUrl", "https://www.mae.ro/");
            dataset.addEntity(person);

            for (int i = 0; i < dd.positions.size(); i++) {
                String position = dd.positions.get(i);
                String start = dd.startDates.get(i);
                String end = dd.endDates.get(i);

                String posSlug = position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                FtmEntity posEntity = dataset.newEntity("Position", posSlug);
                posEntity.addValue("name", position);
                posEntity.addValue("country", COUNTRY);
                posEntity.addValue("organization", "Ministerul Afacerilor Externe");
                posEntity.addValue("topics", "gov.diplomatic");
                posEntity.addValue("sourceUrl", "https://www.mae.ro/");
                dataset.addEntity(posEntity);

                FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId() + "-" + i);
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", posEntity.getId());
                if (!start.isEmpty()) {
                    occupancy.addValue("startDate", start);
                }
                if (!end.isEmpty()) {
                    occupancy.addValue("endDate", end);
                    occupancy.addValue("status", "ended");
                } else {
                    occupancy.addValue("status", "current");
                }
                dataset.addEntity(occupancy);
            }

            count++;
        }

        return count;
    }

    private String cleanDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank() || dateStr.startsWith("http")) {
            return "";
        }
        String s = dateStr.trim();
        int tIdx = s.indexOf('T');
        if (tIdx > 0) {
            s = s.substring(0, tIdx);
        }
        if (ISO_DATE_PATTERN.matcher(s).matches()) {
            return s;
        }
        return "";
    }

    private static class DiplomatData {
        final String name;
        final String birthDate;
        final List<String> positions = new ArrayList<>();
        final List<String> startDates = new ArrayList<>();
        final List<String> endDates = new ArrayList<>();

        DiplomatData(String name, String birthDate) {
            this.name = name;
            this.birthDate = birthDate != null ? birthDate : "";
        }

        void addMandate(String pos, String start, String end) {
            positions.add(pos);
            startDates.add(start != null ? start : "");
            endDates.add(end != null ? end : "");
        }

        String primaryPosition() {
            return positions.isEmpty() ? "Ambasador al României" : positions.get(0);
        }
    }
}
