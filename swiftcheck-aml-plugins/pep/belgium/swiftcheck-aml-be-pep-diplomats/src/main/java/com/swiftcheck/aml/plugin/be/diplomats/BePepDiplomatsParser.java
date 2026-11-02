package com.swiftcheck.aml.plugin.be.diplomats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parser pour les ambassadeurs, consuls généraux, consuls, chargés d'affaires
 * et représentants permanents de la Belgique à l'étranger, publiés par le
 * SPF Affaires étrangères ({@code diplomatie.belgium.be} / {@code publicapps.diplomatie.be}).
 *
 * <p>Catégorie légale (Annexe IV de la loi belge du 18 septembre 2017) :
 * <b>BE-f</b> – <i>Ambassadeur, consul ou chargé d'affaires belge</i>.
 *
 * <p>Les consuls honoraires sont exclus conformément aux lignes directrices LBC/FT,
 * et aucune adresse postale, téléphone ou courriel des postes n'est extrait dans les entités.
 */
@Extension
public class BePepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepDiplomatsParser.class);

    static final String ID_PREFIX = "be-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";
    static final String SOURCE_URL = "https://diplomatie.belgium.be/fr/ambassades-et-consulats";
    static final String INSTITUTION = "SPF Affaires étrangères, Commerce extérieur et Coopération au Développement";

    private static final String COUNTRIES_API_URL =
            "https://publicapps.diplomatie.be/api/geography/country/institutions?language=fr";
    private static final String INSTITUTIONS_API_URL_TEMPLATE =
            "https://publicapps.diplomatie.be/api/belgianInstitutions?language=fr&countryIso=%s&isForeignInstitution=false";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record DiplomatRecord(
            String firstName,
            String lastName,
            String fullName,
            List<String> positions,
            List<String> countries
    ) {}

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String countriesJson = fetchUrl(client, COUNTRIES_API_URL);
        Files.writeString(ctx.rawDir().resolve("diplomatie-countries.json"), countriesJson, StandardCharsets.UTF_8);

        JsonNode countriesArray = MAPPER.readTree(countriesJson);
        ArrayNode consolidated = MAPPER.createArrayNode();

        Set<String> isoCodes = new LinkedHashSet<>();
        if (countriesArray != null && countriesArray.isArray()) {
            for (JsonNode country : countriesArray) {
                String iso = textOrEmpty(country, "countryISO");
                if (!iso.isBlank()) {
                    isoCodes.add(iso.trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        int fetchedCountries = 0;
        for (String iso : isoCodes) {
            try {
                String url = String.format(INSTITUTIONS_API_URL_TEMPLATE, iso);
                String body = fetchUrl(client, url);
                JsonNode posts = MAPPER.readTree(body);
                if (posts != null && posts.isArray()) {
                    for (JsonNode post : posts) {
                        consolidated.add(post);
                    }
                    fetchedCountries++;
                }
            } catch (Exception e) {
                log.debug("[be_pep_diplomats] Impossible de récupérer les postes pour {} : {}", iso, e.getMessage());
            }
        }

        Path consolidatedPath = ctx.rawDir().resolve("diplomats-posts.json");
        Files.writeString(consolidatedPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(consolidated),
                StandardCharsets.UTF_8);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String json = Files.readString(consolidatedPath, StandardCharsets.UTF_8);
        List<DiplomatRecord> diplomats = parsePostsJson(json);
        for (DiplomatRecord rec : diplomats) {
            writeRecord(rec, dataset);
        }
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                diplomats.size() + " diplomates belges extraits sur " + fetchedCountries + " pays.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Path consolidatedPath = ctx.rawDir().resolve("diplomats-posts.json");
        String json = Files.readString(consolidatedPath, StandardCharsets.UTF_8);
        for (DiplomatRecord rec : parsePostsJson(json)) {
            writeRecord(rec, dataset);
        }
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    private String fetchUrl(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - diplomatie.belgium.be)")
                .header("Accept", "application/json,*/*;q=0.8")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " sur " + url);
        }
        return resp.body();
    }

    public List<DiplomatRecord> parsePostsJson(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !root.isArray()) {
            return List.of();
        }

        Map<String, DiplomatAccumulator> byKey = new LinkedHashMap<>();

        for (JsonNode post : root) {
            String instType = cleanSpaces(textOrEmpty(post, "institutionTypeName"));
            if (isHonoraryPost(instType)) {
                continue;
            }

            String city = cleanSpaces(textOrEmpty(post, "city_Name"));
            String countryName = cleanSpaces(textOrEmpty(post, "countryName"));

            String rootFirstName = cleanSpaces(textOrEmpty(post, "firstName"));
            String rootLastName = cleanSpaces(textOrEmpty(post, "lastName"));
            String rootTitle = cleanSpaces(textOrEmpty(post, "title_name"));
            addDiplomatCandidate(byKey, rootFirstName, rootLastName, rootTitle, instType, city, countryName);

            JsonNode staffNode = post.get("staff");
            if (staffNode != null && staffNode.isArray()) {
                for (JsonNode member : staffNode) {
                    String fn = cleanSpaces(textOrEmpty(member, "firstName"));
                    String ln = cleanSpaces(textOrEmpty(member, "lastName"));
                    String title = cleanSpaces(textOrEmpty(member, "title_Name"));
                    if (title.isBlank()) {
                        title = cleanSpaces(textOrEmpty(member, "title_name"));
                    }
                    addDiplomatCandidate(byKey, fn, ln, title, instType, city, countryName);
                }
            }
        }

        List<DiplomatRecord> records = new ArrayList<>();
        for (DiplomatAccumulator acc : byKey.values()) {
            records.add(new DiplomatRecord(
                    acc.firstName,
                    acc.lastName,
                    (acc.firstName + " " + acc.lastName).trim(),
                    new ArrayList<>(acc.positions),
                    new ArrayList<>(acc.countries)
            ));
        }
        return records;
    }

    public static void writeRecord(DiplomatRecord rec, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", "diplomat", rec.fullName());
        person.addValue("name", rec.fullName());
        person.addValue("firstName", rec.firstName());
        person.addValue("lastName", rec.lastName());
        person.addValue("nationality", COUNTRY);
        person.addValue("country", COUNTRY);
        person.addValue("sourceUrl", SOURCE_URL);

        for (String pos : rec.positions()) {
            person.addValue("position", pos);
        }
        if (!rec.countries().isEmpty()) {
            person.addValue("notes", "Juridictions d'accréditation : " + String.join(", ", rec.countries())
                    + " (" + INSTITUTION + ")");
        }
        String reversed = rec.lastName() + " " + rec.firstName();
        if (!reversed.equalsIgnoreCase(rec.fullName())) {
            person.addValue("alias", reversed);
        }
        dataset.addEntity(person);

        for (String pos : rec.positions()) {
            FtmEntity position = dataset.newEntity("Position", pos);
            position.addValue("name", pos);
            position.addValue("country", COUNTRY);
            position.addValue("organization", INSTITUTION);
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.executive");
            dataset.addEntity(position);

            FtmEntity occ = dataset.newEntity("Occupancy", rec.fullName(), pos);
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(occ);
        }
    }

    private void addDiplomatCandidate(
            Map<String, DiplomatAccumulator> byKey,
            String firstName,
            String lastName,
            String title,
            String instType,
            String city,
            String countryName
    ) {
        if (lastName.isBlank() || firstName.isBlank()) {
            return;
        }
        if (isHonoraryPost(title)) {
            return;
        }
        String effectiveTitle = title.isBlank() ? "Chef de poste diplomatique" : title;
        String postDesc = buildPostDescription(effectiveTitle, instType, city, countryName);

        String key = (firstName + " " + lastName).toLowerCase(Locale.ROOT);
        DiplomatAccumulator acc = byKey.computeIfAbsent(
                key, k -> new DiplomatAccumulator(firstName, lastName));
        acc.positions.add(postDesc);
        if (!countryName.isBlank()) {
            acc.countries.add(countryName);
        }
    }

    private static String buildPostDescription(String title, String instType, String city, String countryName) {
        StringBuilder sb = new StringBuilder(title);
        if (!instType.isBlank()) {
            sb.append(" – ").append(instType).append(" de Belgique");
        }
        if (!city.isBlank()) {
            sb.append(" à ").append(city);
        }
        if (!countryName.isBlank()) {
            sb.append(" (").append(countryName).append(")");
        }
        return sb.toString();
    }

    private static boolean isHonoraryPost(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("honoraire") || lower.contains("honorary") || lower.contains("ereconsul");
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("");
    }

    private static String cleanSpaces(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static final class DiplomatAccumulator {
        final String firstName;
        final String lastName;
        final Set<String> positions = new LinkedHashSet<>();
        final Set<String> countries = new LinkedHashSet<>();

        DiplomatAccumulator(String firstName, String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
        }
    }
}
