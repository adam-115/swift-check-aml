package com.swiftcheck.aml.plugins.plpep.parties;

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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <b>pl_pep_political_parties</b> – Membres des organes dirigeants des partis politiques représentés au parlement polonais.
 *
 * <p>Couvre la catégorie <b>PL-c</b>, fonction <b>Membre d'un organe dirigeant d'un parti</b> (fonction 488)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via Wikidata SPARQL.
 */
@Extension
public class PlPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "pl-pep-political-parties";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String JSON_FILE = "pl-political-parties-sparql.json";

    static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String DEFAULT_PARTIES = "Q156868 Q156874 Q218477 Q100979832 Q107418166 Q61568052 Q21006495 Q16597751 Q1337891";

    private static final Pattern QID_PATTERN = Pattern.compile("^Q\\d+$");
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PlPepPoliticalPartiesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PlPepPoliticalPartiesParser(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        download(rawFile, ctx);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d dirigeants des partis politiques de Pologne index\u00e9s.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            download(rawFile, ctx);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://pkw.gov.pl/"));
            }
        }
        return records;
    }

    private void download(Path target, ParserContext ctx) throws IOException, InterruptedException {
        String endpoint = DEFAULT_ENDPOINT;
        String partiesParam = DEFAULT_PARTIES;
        if (ctx != null && ctx.parameters() != null) {
            String ep = ctx.parameters().get("endpoint");
            if (ep != null && !ep.isBlank()) endpoint = ep.trim();
            String pt = ctx.parameters().get("parties");
            if (pt != null && !pt.isBlank()) partiesParam = pt.trim();
        }

        List<String> partyQids = new ArrayList<>();
        for (String q : partiesParam.split("[\\s,]+")) {
            if (!q.isBlank() && QID_PATTERN.matcher(q.trim()).matches()) {
                partyQids.add("wd:" + q.trim());
            }
        }
        if (partyQids.isEmpty()) {
            throw new IllegalArgumentException("Aucun identifiant de parti valide.");
        }

        LocalDate cutoff = LocalDate.now().minusMonths(36);
        String sparql = "SELECT DISTINCT ?party ?partyLabel ?leader ?leaderLabel ?birth ?gender ?start ?end WHERE {\n"
                + "  VALUES ?party { " + String.join(" ", partyQids) + " }\n"
                + "  VALUES ?roleProp { p:P488 p:P1037 p:P169 p:P3922 p:P3320 }\n"
                + "  ?party ?roleProp ?statement .\n"
                + "  ?statement ps:P488|ps:P1037|ps:P169|ps:P3922|ps:P3320 ?leader .\n"
                + "  OPTIONAL { ?statement pq:P580 ?start . }\n"
                + "  OPTIONAL { ?statement pq:P582 ?end . }\n"
                + "  OPTIONAL { ?leader wdt:P569 ?birth . }\n"
                + "  OPTIONAL { ?leader wdt:P21 ?gender . }\n"
                + "  FILTER NOT EXISTS { ?leader wdt:P570 ?death }\n"
                + "  FILTER(!BOUND(?end) || ?end >= \"" + cutoff.atStartOfDay() + ":00Z\"^^xsd:dateTime)\n"
                + "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"pl,en\". }\n"
                + "}";

        String url = endpoint + "?format=json&query=" + URLEncoder.encode(sparql, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .header("Accept", "application/sparql-results+json")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        log.info("Interrogation Wikidata SPARQL pour les partis politiques polonais...");
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 200) {
            Files.writeString(target, resp.body(), StandardCharsets.UTF_8);
            log.info("Donn\u00e9es Wikidata enregistr\u00e9es dans {}", target);
        } else {
            throw new IOException("Wikidata SPARQL a r\u00e9pondu avec le statut " + resp.statusCode());
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            throw new IllegalStateException("Aucun r\u00e9sultat trouv\u00e9 dans : " + rawFile);
        }

        int count = 0;
        Set<String> seen = new HashSet<>();

        for (JsonNode row : bindings) {
            String name = row.path("leaderLabel").path("value").asText("").trim();
            if (name.isEmpty() || name.startsWith("Q")) {
                continue;
            }
            String party = row.path("partyLabel").path("value").asText("Parti politique polonais").trim();
            String key = name.toLowerCase(Locale.ROOT) + "@" + party.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);

            String birthDate = parseDate(row.path("birth").path("value").asText(null));
            String startDate = parseDate(row.path("start").path("value").asText(null));
            String leaderQid = extractQid(row.path("leader").path("value").asText(null));
            String sourceUrl = leaderQid != null ? "http://www.wikidata.org/entity/" + leaderQid : "https://pkw.gov.pl/";

            String position = "Lider / Cz\u0142onek organu wykonawczego \u2013 " + party;

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            String[] nameParts = splitName(name);
            if (!nameParts[0].isEmpty()) {
                person.addValue("firstName", nameParts[0]);
            }
            if (!nameParts[1].isEmpty()) {
                person.addValue("lastName", nameParts[1]);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (birthDate != null) {
                person.addValue("birthDate", birthDate);
            }
            person.addValue("position", position);
            person.addValue("political", party);
            person.addValue("sourceUrl", sourceUrl);
            dataset.addEntity(person);

            String partySlug = party.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity partyEntity = dataset.newEntity("PublicBody", "party-" + partySlug);
            partyEntity.addValue("name", party);
            partyEntity.addValue("country", COUNTRY);
            partyEntity.addValue("jurisdiction", COUNTRY);
            dataset.addEntity(partyEntity);

            String posSlug = (position + "-" + party).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", party);
            pos.addValue("topics", "pol.party");
            pos.addValue("sourceUrl", sourceUrl);
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId() + "-" + posSlug);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            if (startDate != null) {
                occupancy.addValue("startDate", startDate);
            }
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private static String parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw).toLocalDate().toString();
        } catch (DateTimeParseException e) {
            if (raw.length() >= 10 && raw.charAt(4) == '-' && raw.charAt(7) == '-') {
                return raw.substring(0, 10);
            }
        }
        return null;
    }

    private static String extractQid(String uri) {
        if (uri == null) return null;
        int idx = uri.lastIndexOf('/');
        return idx != -1 ? uri.substring(idx + 1) : uri;
    }

    private static String[] splitName(String fullName) {
        String[] parts = fullName.split("\\s+");
        if (parts.length == 1) {
            return new String[]{parts[0], ""};
        }
        return new String[]{parts[0], parts[parts.length - 1]};
    }
}
