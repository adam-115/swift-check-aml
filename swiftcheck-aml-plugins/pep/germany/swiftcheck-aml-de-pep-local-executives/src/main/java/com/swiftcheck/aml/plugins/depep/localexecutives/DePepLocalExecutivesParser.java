package com.swiftcheck.aml.plugins.depep.localexecutives;

import java.io.InputStream;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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
 * <b>de_pep_local_executives</b> – Maires (Oberbürgermeister) et chefs d'arrondissement (Landräte) des collectivités
 * allemandes d'au moins 50 000 habitants.
 * <p>
 * Catégorie <b>DE-AMLR</b> (Règlement UE 2024/1624, applicable le 10 juillet 2027).
 * Couvre environ 200 villes de plus de 50 000 hab. et environ 290 Landkreise.
 * Sources : Destatis (populations du Gemeindeverzeichnis) et Wikidata SPARQL (P6 : head of government).
 */
@Extension
public class DePepLocalExecutivesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepLocalExecutivesParser.class);

    static final String ID_PREFIX = "de-pep-local-exec";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    static final long DEFAULT_MIN_POPULATION = 50_000L;

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    /** Format ligne: "Köln | KREISFREIE_STADT | 1084831 | Oberbürgermeisterin | Henriette Reker | parteilos" */
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^\\s*([^|:;]+?)\\s*[|:;]\\s*([^|:;]+?)\\s*[|:;]\\s*(\\d+)\\s*[|:;]\\s*([^|:;]+?)\\s*[|:;]\\s*([^|:;]+?)(?:\\s*[|:;]\\s*(.*))?$",
            Pattern.CASE_INSENSITIVE);

    record LocalExecutive(String entityName, String type, long population, String role,
                          String fullName, String title, String cleanName, String party) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        long minPop = p.containsKey("minPopulation") ? Long.parseLong(p.get("minPopulation")) : DEFAULT_MIN_POPULATION;

        String content = loadContent(ctx);
        List<LocalExecutive> executives = parseContent(content, minPop);

        if (executives.isEmpty()) {
            throw new IllegalStateException("Aucun exécutif local (Oberbürgermeister / Landrat) extrait.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(executives, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                executives.size() + " maires et Landräte de collectivités >= " + minPop + " hab. extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        long minPop = p.containsKey("minPopulation") ? Long.parseLong(p.get("minPopulation")) : DEFAULT_MIN_POPULATION;

        String content = loadContent(ctx);
        List<LocalExecutive> executives = parseContent(content, minPop);
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (LocalExecutive e : executives) {
            records.add(new SourceRecord(
                    ids.buildEntityId(e.entityName(), e.cleanName()),
                    "Person",
                    e.fullName(),
                    e.entityName() + " (" + e.role() + ")"
            ));
        }
        return records;
    }

    private String loadContent(ParserContext ctx) throws Exception {
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            return Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
        }
        Path rawFile = ctx.rawDir() != null ? ctx.rawDir().resolve("local-executives.json") : null;
        if (rawFile != null && Files.isRegularFile(rawFile)) {
            return Files.readString(rawFile, StandardCharsets.UTF_8);
        }
        rawFile = ctx.rawDir() != null ? ctx.rawDir().resolve("local-executives.txt") : null;
        if (rawFile != null && Files.isRegularFile(rawFile)) {
            return Files.readString(rawFile, StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("Aucun fichier de données pour les exécutifs locaux trouvé dans " + ctx.inputFile());
    }

    static List<LocalExecutive> parseContent(String content, long minPopulation) {
        List<LocalExecutive> list = new ArrayList<>();
        String trimmed = content.trim();

        // 1. JSON (SPARQL ou format structuré)
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(content);
                JsonNode items = root.isArray() ? root
                        : (root.has("results") && root.get("results").has("bindings") ? root.get("results").get("bindings")
                        : (root.has("data") ? root.get("data") : null));

                if (items != null && items.isArray()) {
                    for (JsonNode item : items) {
                        String name = extractValue(item, "name", "leaderLabel", "personLabel");
                        String entity = extractValue(item, "city", "areaLabel", "muniLabel", "kreisLabel");
                        String type = extractValue(item, "type", "kind");
                        String role = extractValue(item, "role", "title", "positionLabel");
                        String party = extractValue(item, "party", "partyLabel");
                        long pop = extractLong(item, "population", "pop");

                        if (name == null || entity == null || (pop > 0 && pop < minPopulation)) {
                            continue;
                        }
                        if (role == null) {
                            role = (type != null && type.toLowerCase().contains("kreis")) ? "Landrat" : "Oberbürgermeister";
                        }
                        addExecutive(list, entity, type, pop, role, name, party);
                    }
                    if (!list.isEmpty()) {
                        return list;
                    }
                }
            } catch (Exception e) {
                log.debug("Format JSON non parsable : {}", e.getMessage());
            }
        }

        // 2. Format texte ligne par ligne
        for (String line : content.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            Matcher m = LINE_PATTERN.matcher(l);
            if (m.find()) {
                String entity = m.group(1).trim();
                String type = m.group(2).trim();
                long pop = Long.parseLong(m.group(3).trim());
                String role = m.group(4).trim();
                String rawName = m.group(5).trim();
                String party = m.group(6) != null ? m.group(6).trim() : null;

                if (pop >= minPopulation) {
                    addExecutive(list, entity, type, pop, role, rawName, party);
                }
            }
        }

        return list;
    }

    private static String extractValue(JsonNode node, String... keys) {
        for (String k : keys) {
            if (node.has(k)) {
                JsonNode child = node.get(k);
                if (child.isTextual()) {
                    return child.asText();
                } else if (child.has("value")) {
                    return child.get("value").asText();
                }
            }
        }
        return null;
    }

    private static long extractLong(JsonNode node, String... keys) {
        String val = extractValue(node, keys);
        if (val != null) {
            try {
                return Long.parseLong(val.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static void addExecutive(List<LocalExecutive> list, String entity, String type, long pop,
                                     String role, String rawName, String party) {
        String title = null;
        String cleanName = rawName;
        Matcher tm = ACADEMIC_TITLE.matcher(rawName);
        if (tm.find()) {
            title = tm.group(0).trim();
            cleanName = rawName.substring(tm.end()).trim();
        }
        list.add(new LocalExecutive(entity, type, pop, role, rawName, title, cleanName, party));
    }

    private void convert(List<LocalExecutive> executives, FtmDatasetBuilder dataset) {
        for (LocalExecutive e : executives) {
            String posKey = e.entityName().replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase() + "-exec";

            FtmEntity position = dataset.newEntity("Position", posKey);
            position.addValue("name", e.role() + " (" + e.entityName() + ")");
            position.addValue("country", "de");
            position.addValue("organization", e.entityName());
            position.addValue("topics", "gov.muni");
            dataset.addEntity(position);

            FtmEntity person = dataset.newEntity("Person", e.entityName(), e.cleanName());
            person.addValue("name", e.cleanName());
            person.addValue("title", e.title());
            String[] parts = e.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            if (e.party() != null && !e.party().isBlank()) {
                person.addValue("political", e.party());
            }
            person.addValue("position", e.role() + " (" + e.entityName() + ")");
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", e.entityName(), e.cleanName(), "exec");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", e.role() + " von " + e.entityName() + " (Pop: " + e.population() + ")");
            dataset.addEntity(occ);
        }
    }
}

