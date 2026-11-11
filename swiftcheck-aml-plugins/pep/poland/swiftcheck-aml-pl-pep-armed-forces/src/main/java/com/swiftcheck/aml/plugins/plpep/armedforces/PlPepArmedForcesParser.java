package com.swiftcheck.aml.plugins.plpep.armedforces;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * <b>pl_pep_armed_forces</b> – Officiers généraux des forces armées polonaises (Wojsko Polskie).
 *
 * <p>Couvre la catégorie <b>PL-f</b>, fonction <b>Officier général des forces armées</b> (fonction 494)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 */
@Extension
public class PlPepArmedForcesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepArmedForcesParser.class);

    static final String ID_PREFIX = "pl-pep-armed-forces";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String SOURCE_URL = "https://www.gov.pl/web/obrona-narodowa/kierownictwo";
    static final String JSON_FILE = "pl-armed-forces.json";

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        loadResource(rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d officiers généraux des forces armées polonaises indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            loadResource(rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://www.gov.pl/web/obrona-narodowa"));
            }
        }
        return records;
    }

    private void loadResource(Path rawFile) throws IOException {
        String html = fetch(SOURCE_URL);
        List<String[]> found = extract(html);
        if (found.isEmpty()) {
            throw new IllegalStateException("Aucun officier général trouvé sur " + SOURCE_URL + " (structure modifiée ?)");
        }
        com.fasterxml.jackson.databind.node.ObjectNode root = json.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode arr = root.putArray("generals");
        for (String[] f : found) {
            com.fasterxml.jackson.databind.node.ObjectNode o = arr.addObject();
            o.put("name", f[0]);
            o.put("position", f[1]);
            o.put("branch", "Sztab Generalny WP");
        }
        Files.writeString(rawFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        log.info("Forces armées polonaises : {} officiers généraux lus sur {}", found.size(), SOURCE_URL);
    }

    private static final java.util.regex.Pattern ENTRY = java.util.regex.Pattern.compile(
            "class=\"title\"[^>]*>\\s*<a[^>]*>([^<]+)</a>.*?class=\"position\"[^>]*>([^<]+)<", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern RANK = java.util.regex.Pattern.compile(
            "^((?:gen|adm|wadm|kadm)\\.(?:\\s+(?:dyw|bryg|broni)\\.?)?)\\s+(.+)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Extrait les officiers généraux (grade « gen. »/« adm. ») de la page Kierownictwo ; retourne [nom, fonction]. */
    static List<String[]> extract(String html) {
        List<String[]> out = new ArrayList<>();
        java.util.regex.Matcher m = ENTRY.matcher(html);
        while (m.find()) {
            String name = clean(m.group(1));
            String position = clean(m.group(2));
            java.util.regex.Matcher r = RANK.matcher(name);
            if (r.matches()) {
                out.add(new String[] {r.group(2).trim(), name.substring(0, name.length() - r.group(2).length()).trim() + ", " + position});
            }
        }
        return out;
    }

    private static String clean(String s) {
        return s.replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String fetch(String url) throws IOException {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(20)).build();
            java.net.http.HttpResponse<String> resp = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                            .header("User-Agent", "SwiftCheck-AML/1.0 (PEP screening)")
                            .timeout(java.time.Duration.ofSeconds(60)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " sur " + url + " (source inaccessible, aucun contournement tenté)");
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu", e);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode generals = root.path("generals");
        if (!generals.isArray() || generals.isEmpty()) {
            throw new IllegalStateException("Aucun officier général trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode g : generals) {
            String name = g.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }

            String birthDate = g.path("birthDate").asText("").trim();
            String position = g.path("position").asText("").trim();
            String branch = g.path("branch").asText("Siły Zbrojne RP").trim();
            String startDate = g.path("startDate").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!position.isEmpty()) {
                person.addValue("position", position);
            }
            person.addValue("sourceUrl", "https://www.gov.pl/web/obrona-narodowa");
            dataset.addEntity(person);

            String posSlug = (position + "-" + branch).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", branch.isEmpty() ? "Siły Zbrojne Rzeczypospolitej Polskiej" : branch);
            pos.addValue("sourceUrl", "https://www.gov.pl/web/obrona-narodowa");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            if (!startDate.isEmpty()) {
                occupancy.addValue("startDate", startDate);
            }
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

