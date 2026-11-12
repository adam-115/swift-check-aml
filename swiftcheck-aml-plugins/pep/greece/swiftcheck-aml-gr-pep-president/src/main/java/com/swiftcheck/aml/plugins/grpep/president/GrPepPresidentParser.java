package com.swiftcheck.aml.plugins.grpep.president;

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
 * <b>gr_pep_president</b> – Président de la République hellénique (Πρόεδρος της Δημοκρατίας) et chefs d'État.
 *
 * <p>Couvre la catégorie <b>GR-a</b>, fonction <b>Président de la République</b> (fonction 657)
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via Wikidata SPARQL.
 */
@Extension
public class GrPepPresidentParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepPresidentParser.class);

    static final String ID_PREFIX = "gr-pep-president";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String JSON_FILE = "gr-president-wikidata.json";

    static final String DEFAULT_SPARQL = """
            SELECT DISTINCT ?person ?personLabel ?personLabelEl ?pos ?posLabel ?startDate ?endDate ?birthDate WHERE {
              ?person wdt:P31 wd:Q5 .
              ?person p:P39 ?stmt .
              ?stmt ps:P39 ?pos .
              VALUES ?pos { wd:Q3409203 } # President of Greece
              OPTIONAL { ?stmt pq:P580 ?startDate . }
              OPTIONAL { ?stmt pq:P582 ?endDate . }
              OPTIONAL { ?person wdt:P569 ?birthDate . }
              OPTIONAL {
                ?person rdfs:label ?personLabelEl .
                FILTER(LANG(?personLabelEl) = "el")
              }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en,el,fr". }
            }
            ORDER BY DESC(?startDate)
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public GrPepPresidentParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GrPepPresidentParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d présidents et chefs d'État helléniques indexés.", count)
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
                        "https://www.presidency.gr/"));
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
                .header("User-Agent", "SwiftCheckAmlBot/1.0 (gr_pep_president; contact@swiftcheck.com)")
                .header("Accept", "application/sparql-results+json, application/json")
                .GET()
                .build();

        HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IOException("Erreur HTTP " + res.statusCode() + " lors de la requête Wikidata SPARQL: "
                    + new String(res.body(), StandardCharsets.UTF_8));
        }

        Files.write(rawFile, res.body());
        log.info("Données SPARQL Présidence hellénique téléchargées : {} octets", res.body().length);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun résultat SPARQL dans : " + rawFile);
        }

        Map<String, PresidentData> presidents = new LinkedHashMap<>();

        for (JsonNode b : bindings) {
            String personUri = b.path("person").path("value").asText("").trim();
            if (personUri.isEmpty()) {
                continue;
            }
            String qid = personUri.substring(personUri.lastIndexOf('/') + 1);

            String label = b.path("personLabel").path("value").asText("").trim();
            if (label.isEmpty() || label.startsWith("Q") && label.matches("Q\\d+")) {
                continue;
            }

            String elLabel = b.path("personLabelEl").path("value").asText("").trim();
            String startDate = cleanDate(b.path("startDate").path("value").asText(""));
            String endDate = cleanDate(b.path("endDate").path("value").asText(""));
            String birthDate = cleanDate(b.path("birthDate").path("value").asText(""));

            PresidentData pd = presidents.computeIfAbsent(qid, k -> new PresidentData(qid, label));
            if (!elLabel.isEmpty() && !elLabel.equals(label)) {
                pd.greekAlias = elLabel;
            }
            if (!birthDate.isEmpty() && pd.birthDate.isEmpty()) {
                pd.birthDate = birthDate;
            }
            pd.terms.add(new TermData(startDate, endDate));
        }

        int count = 0;
        for (PresidentData pd : presidents.values()) {
            FtmEntity person = dataset.newEntity("Person", pd.qid);
            person.addValue("name", pd.name);
            if (!pd.greekAlias.isEmpty()) {
                person.addValue("alias", pd.greekAlias);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!pd.birthDate.isEmpty()) {
                person.addValue("birthDate", pd.birthDate);
            }
            person.addValue("position", "Président de la République hellénique (Πρόεδρος της Δημοκρατίας)");
            person.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + pd.qid);
            dataset.addEntity(person);

            String posSlug = "president-de-la-republique-hellenique";
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", "Président de la République hellénique (Πρόεδρος της Δημοκρατίας)");
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Προεδρία της Ελληνικής Δημοκρατίας (Présidence de la République hellénique)");
            pos.addValue("sourceUrl", "https://www.presidency.gr/");
            dataset.addEntity(pos);

            for (TermData term : pd.terms) {
                String occId = "occ-" + pd.qid + "-" + Math.abs((term.startDate + term.endDate).hashCode());
                FtmEntity occ = dataset.newEntity("Occupancy", occId);
                occ.addValue("holder", person.getId());
                occ.addValue("post", pos.getId());
                if (!term.startDate.isEmpty()) {
                    occ.addValue("startDate", term.startDate);
                }
                if (!term.endDate.isEmpty()) {
                    occ.addValue("endDate", term.endDate);
                    occ.addValue("status", "ended");
                } else {
                    occ.addValue("status", "current");
                }
                dataset.addEntity(occ);
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

    private static class PresidentData {
        final String qid;
        final String name;
        String greekAlias = "";
        String birthDate = "";
        final List<TermData> terms = new ArrayList<>();

        PresidentData(String qid, String name) {
            this.qid = qid;
            this.name = name;
        }
    }

    private static class TermData {
        final String startDate;
        final String endDate;

        TermData(String startDate, String endDate) {
            this.startDate = startDate;
            this.endDate = endDate;
        }
    }
}
