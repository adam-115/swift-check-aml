package com.swiftcheck.aml.plugins.ropep.army;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <b>ro_pep_armed_forces</b> – Officiers généraux des forces armées roumaines (Statul Major al Apărării).
 *
 * <p>Couvre la catégorie <b>RO-f</b>, fonction <b>Officier général des forces armées</b> (fonction 741)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 */
@Extension
public class RoPepArmedForcesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepArmedForcesParser.class);

    static final String ID_PREFIX = "ro-pep-army";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String JSON_FILE = "ro-armed-forces.json";

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
                String.format(Locale.ROOT, "%d officiers généraux des forces armées roumaines indexés.", count)
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
                        "https://www.defense.ro/"));
            }
        }
        return records;
    }

    private void loadResource(Path rawFile) throws IOException {
        List<String[]> found = extract(fetch(SOURCE_URL));
        if (found.isEmpty()) {
            throw new IllegalStateException("Aucun officier général trouvé sur " + SOURCE_URL + " (structure modifiée ?)");
        }
        com.fasterxml.jackson.databind.node.ObjectNode root = json.createObjectNode();
        root.put("country", "ro");
        com.fasterxml.jackson.databind.node.ArrayNode arr = root.putArray("officers");
        for (String[] f : found) {
            arr.addObject().put("name", f[0]).put("position", f[1]).put("branch", "Statul Major al Apărării");
        }
        Files.writeString(rawFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        log.info("Forces armées roumaines : {} officiers généraux lus sur {}", found.size(), SOURCE_URL);
    }

    static final String SOURCE_URL = "https://www.defense.ro/conducere";
    private static final java.util.regex.Pattern ENTRY = java.util.regex.Pattern.compile(
            "<strong>(?:<a[^>]*>)?\\s*((?:General|Contraamiral|Viceamiral|Amiral)[^<]*)</(?:(?!<strong>).)*?<a href=\"[^\"]*\">((?!General|Contraamiral|Viceamiral|Amiral)[^<]+)</a>",
            java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern RANK = java.util.regex.Pattern.compile(
            "^((?:General|Contraamiral|Viceamiral|Amiral)(?:-[a-zăâîșț]+)?)\\s+(.+)$");

    /** Extrait [nom, « grade, fonction »] de la page « Conducere » (dédoublonnés). */
    static List<String[]> extract(String html) {
        String h = decode(html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\""));
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.regex.Matcher m = ENTRY.matcher(h);
        while (m.find()) {
            String full = m.group(1).replaceAll("\\s+", " ").trim();
            java.util.regex.Matcher r = RANK.matcher(full);
            if (!r.matches()) {
                continue;
            }
            String name = titleCase(r.group(2));
            String position = r.group(1) + ", " + m.group(2).replaceAll("\\s+", " ").trim();
            if (seen.add(name)) {
                out.add(new String[] {name, position});
            }
        }
        return out;
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.split(" ")) {
            StringBuilder p = new StringBuilder();
            boolean start = true;
            for (char c : part.toCharArray()) {
                p.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
                start = c == '-';
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String decode(String s) {
        String t = s.replace("&amp;", "&").replace("&amp;", "&").replace("&nbsp;", " ").replace(" ", " ");
        String[][] map = {{"&Icirc;", "Î"}, {"&icirc;", "î"}, {"&Acirc;", "Â"}, {"&acirc;", "â"}, {"&Abreve;", "Ă"}, {"&abreve;", "ă"}};
        for (String[] e : map) {
            t = t.replace(e[0], e[1]);
        }
        return t;
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
        JsonNode officers = root.path("officers");
        if (!officers.isArray() || officers.isEmpty()) {
            throw new IllegalStateException("Aucun officier trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode o : officers) {
            String name = o.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }

            String birthDate = o.path("birthDate").asText("").trim();
            String position = o.path("position").asText("Ofițer general al Forțelor Armate Române").trim();
            String branch = o.path("branch").asText("Statul Major al Apărării").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            person.addValue("position", position);
            person.addValue("sourceUrl", "https://www.defense.ro/");
            dataset.addEntity(person);

            String posSlug = position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", branch);
            pos.addValue("topics", "gov.military");
            pos.addValue("sourceUrl", "https://www.defense.ro/");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

