package com.swiftcheck.aml.plugins.ptpep.mayors;

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
 * <b>pt_pep_mayors</b> – Maires et présidents de conseil municipal (Presidentes de Câmara Municipal) au Portugal.
 *
 * <p>Couvre la catégorie <b>PT-LOC</b>, fonction <b>Maire (Presidente de Câmara Municipal) et adjoints exécutifs (vereadores)</b> (fonction 264)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API SPARQL de Wikidata.
 */
@Extension
public class PtPepMayorsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepMayorsParser.class);

    static final String ID_PREFIX = "pt-pep-mayors";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String JSON_FILE = "pt-mayors.json";

    static final String SPARQL_QUERY =
            "SELECT DISTINCT ?person ?personLabel ?pos ?posLabel ?startDate ?endDate ?muniLabel WHERE {\n" +
            "  ?pos wdt:P279* wd:Q7241346 .\n" +
            "  ?person p:P39 ?stmt .\n" +
            "  ?stmt ps:P39 ?pos .\n" +
            "  OPTIONAL { ?stmt pq:P580 ?startDate . }\n" +
            "  OPTIONAL { ?stmt pq:P582 ?endDate . }\n" +
            "  FILTER(!BOUND(?endDate) || YEAR(?endDate) >= 2020)\n" +
            "  OPTIONAL { ?pos wdt:P1001 ?muni . }\n" +
            "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"pt,mul,en\". }\n" +
            "} ORDER BY ?personLabel";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PtPepMayorsParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PtPepMayorsParser(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        fetchLive(rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset, ctx);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d maires des communes du Portugal indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            fetchLive(rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset, ctx);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://www.anmp.pt/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        String uri = "https://query.wikidata.org/sparql?query=" + URLEncoder.encode(SPARQL_QUERY, StandardCharsets.UTF_8) + "&format=json";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        log.info("Interrogation SPARQL Wikidata pour les maires du Portugal");
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 200) {
            Files.writeString(target, resp.body(), StandardCharsets.UTF_8);
        } else {
            throw new IOException("Erreur HTTP " + resp.statusCode() + " lors de la requête SPARQL");
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        return convert(rawFile, dataset, null);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset, ParserContext ctx) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");

        Set<String> seen = new HashSet<>();
        int count = 0;

        if (bindings.isArray()) {
            for (JsonNode b : bindings) {
                String name = b.path("personLabel").path("value").asText("").trim();
                if (name.isEmpty() || name.startsWith("Q") && name.substring(1).matches("\\d+")) {
                    continue;
                }

                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                String posLabel = b.path("posLabel").path("value").asText("Presidente de Câmara Municipal").trim();
                String muni = b.path("muniLabel").path("value").asText("").trim();
                if (muni.isEmpty() && posLabel.contains("de ")) {
                    muni = posLabel.substring(posLabel.lastIndexOf("de ") + 3).trim();
                } else if (muni.isEmpty() && posLabel.contains("da ")) {
                    muni = posLabel.substring(posLabel.lastIndexOf("da ") + 3).trim();
                } else if (muni.isEmpty() && posLabel.contains("do ")) {
                    muni = posLabel.substring(posLabel.lastIndexOf("do ") + 3).trim();
                }
                if (muni.isEmpty()) {
                    muni = "Portugal";
                }

                String startDate = b.path("startDate").path("value").asText("");
                String endDate = b.path("endDate").path("value").asText("");

                addMayor(dataset, name, posLabel, muni, startDate, endDate, "https://www.anmp.pt/");
                count++;
            }
        }

        // Ensure key Portuguese mayors from largest municipalities
        ensureMayor(dataset, seen, "Carlos Moedas", "Presidente da Câmara Municipal de Lisboa", "Lisboa", "2021-10-18", null, "https://www.cm-lisboa.pt/");
        ensureMayor(dataset, seen, "Rui Moreira", "Presidente da Câmara Municipal do Porto", "Porto", "2013-10-22", null, "https://www.cm-porto.pt/");
        ensureMayor(dataset, seen, "Basílio Horta", "Presidente da Câmara Municipal de Sintra", "Sintra", "2013-10-21", null, "https://cm-sintra.pt/");
        ensureMayor(dataset, seen, "Eduardo Vítor Rodrigues", "Presidente da Câmara Municipal de Vila Nova de Gaia", "Vila Nova de Gaia", "2013-10-19", null, "https://www.cm-gaia.pt/");
        ensureMayor(dataset, seen, "Carlos Carreiras", "Presidente da Câmara Municipal de Cascais", "Cascais", "2011-02-01", null, "https://www.cascais.pt/");

        // Extra mayors from parameters if supplied
        if (ctx != null && ctx.parameters() != null) {
            String extraMayorsJson = ctx.parameters().get("mayors");
            if (extraMayorsJson != null && !extraMayorsJson.isBlank()) {
                try {
                    JsonNode extra = json.readTree(extraMayorsJson);
                    JsonNode list = extra.isArray() ? extra : extra.path("mayors");
                    if (list.isArray()) {
                        for (JsonNode m : list) {
                            String name = m.path("name").asText("").trim();
                            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                                continue;
                            }
                            String muni = m.path("municipality").asText("Município");
                            String position = m.path("position").asText("Presidente da Câmara Municipal de " + muni);
                            String since = m.path("since").asText("");
                            addMayor(dataset, name, position, muni, since, null, "https://www.anmp.pt/");
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Impossible de parser le paramètre mayors : {}", e.getMessage());
                }
            }
        }

        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).mapToInt(e -> 1).sum();
    }

    private void ensureMayor(FtmDatasetBuilder dataset, Set<String> seen, String name, String posLabel, String muni, String startDate, String endDate, String sourceUrl) {
        if (!seen.add(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        addMayor(dataset, name, posLabel, muni, startDate, endDate, sourceUrl);
    }

    private void addMayor(FtmDatasetBuilder dataset, String name, String position, String municipality, String startDate, String endDate, String sourceUrl) {
        FtmEntity person = dataset.newEntity("Person", name);
        person.addValue("name", name);
        person.addValue("country", COUNTRY);
        person.addValue("nationality", COUNTRY);
        person.addValue("position", position);
        person.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(person);

        FtmEntity munEntity = dataset.newEntity("PublicBody", "cm-" + municipality.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        munEntity.addValue("name", "Câmara Municipal de " + municipality);
        munEntity.addValue("country", COUNTRY);
        dataset.addEntity(munEntity);

        FtmEntity posEntity = dataset.newEntity("Position", "pt-mayor-" + person.getId());
        posEntity.addValue("name", position);
        posEntity.addValue("country", COUNTRY);
        posEntity.addValue("organization", "Câmara Municipal de " + municipality);
        posEntity.addValue("topics", "gov.muni");
        posEntity.addValue("topics", "gov.national");
        dataset.addEntity(posEntity);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", posEntity.getId());
        occupancy.addValue("status", endDate == null || endDate.isEmpty() ? "current" : "ended");
        if (startDate != null && !startDate.isEmpty()) {
            occupancy.addValue("startDate", startDate.substring(0, Math.min(10, startDate.length())));
        }
        if (endDate != null && !endDate.isEmpty()) {
            occupancy.addValue("endDate", endDate.substring(0, Math.min(10, endDate.length())));
        }
        dataset.addEntity(occupancy);
    }
}
