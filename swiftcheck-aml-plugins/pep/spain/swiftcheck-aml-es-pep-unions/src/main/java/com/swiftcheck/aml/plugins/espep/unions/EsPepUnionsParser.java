package com.swiftcheck.aml.plugins.espep.unions;

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
 * <b>es_pep_unions</b> – Haute direction des syndicats et des organisations patronales d'Espagne.
 *
 * <p>Couvre la catégorie <b>ES-SIND</b>, fonction <b>Membre de la haute direction d'un syndicat ou d'une organisation patronale</b>
 * (Ley 10/2010, art. 14.3 d) ; JO C/2023/724).
 *
 * <p>CCOO, UGT, CEOE, CEPYME, CSIF, USO, ATA, ELA, CIG.
 */
@Extension
public class EsPepUnionsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepUnionsParser.class);

    static final String ID_PREFIX = "es-pep-unions";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "es";
    static final String JSON_FILE = "spanish-unions-leaders.json";

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
                String.format(Locale.ROOT, "%d dirigeants des syndicats et organisations patronales d'Espagne indexés.", count)
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
                        "https://www.ceoe.es/"));
            }
        }
        return records;
    }

    private void loadResource(Path rawFile) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root = json.createObjectNode();
        root.put("country", COUNTRY);
        com.fasterxml.jackson.databind.node.ArrayNode arr = root.putArray("leaders");
        int ok = 0;
        for (String[] src : SOURCES) {
            try {
                List<String[]> found = "ceoe".equals(src[0]) ? extractCeoe(fetch(src[1])) : extractUso(fetch(src[1]));
                for (String[] f : found) {
                    arr.addObject().put("name", f[0]).put("role", f[1]).put("org", src[2])
                            .put("type", "ceoe".equals(src[0]) ? "employer" : "union").put("status", "current");
                }
                log.info("{} : {} dirigeants lus sur {}", src[2], found.size(), src[1]);
                ok += found.isEmpty() ? 0 : 1;
            } catch (IOException e) {
                log.warn("Source {} indisponible : {}", src[1], e.getMessage());
            }
        }
        if (ok == 0) {
            throw new IllegalStateException("Aucune source syndicale/patronale lisible (CEOE, USO)");
        }
        Files.writeString(rawFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
    }

    static final String[][] SOURCES = {
        {"ceoe", "https://www.ceoe.es/es/conocenos/organizacion/conoce-nuestro-equipo", "Confederación Española de Organizaciones Empresariales (CEOE)"},
        {"uso", "https://www.uso.es/comision-ejecutiva-confederal/", "Unión Sindical Obrera (USO)"}
    };
    private static final java.util.regex.Pattern CEOE_CARD = java.util.regex.Pattern.compile(
            "<p class=\"name\">([^<]+)</p>\\s*<p>([^<]+?)\\s*<br>");
    private static final java.util.regex.Pattern USO_CARD = java.util.regex.Pattern.compile(
            "<div class=\"member-name\">([^<]+)</div>\\s*<div class=\"member-role\">([^<]+)</div>");

    static List<String[]> extractCeoe(String html) {
        return cards(CEOE_CARD, html, "CEOE");
    }

    static List<String[]> extractUso(String html) {
        return cards(USO_CARD, html, "USO");
    }

    private static List<String[]> cards(java.util.regex.Pattern p, String html, String org) {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.regex.Matcher m = p.matcher(html);
        while (m.find()) {
            String name = m.group(1).replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
            String role = m.group(2).replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
            if (!name.isEmpty() && seen.add(name)) {
                out.add(new String[] {name, role});
            }
        }
        return out;
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
                throw new IOException("HTTP " + resp.statusCode() + " sur " + url + " (aucun contournement tenté)");
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu", e);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode leaders = root.path("leaders");
        if (!leaders.isArray() || leaders.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant trouvé dans le fichier : " + rawFile);
        }

        int count = 0;
        for (JsonNode l : leaders) {
            String name = l.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String org = l.path("org").asText("").trim();
            String role = l.path("role").asText("").trim();
            String type = l.path("type").asText("union").trim();
            String status = l.path("status").asText("current").trim();
            String birthDate = l.path("birthDate").asText("").trim();
            String startDate = l.path("startDate").asText("").trim();
            String endDate = l.path("endDate").asText("").trim();
            String gender = l.path("gender").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role + " – " + org);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.ceoe.es/");
            dataset.addEntity(person);

            String posSlug = (org + "-" + role).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", role + " – " + org);
            position.addValue("country", COUNTRY);
            position.addValue("organization", org);
            position.addValue("topics", "business_org".equals(type) ? "corp.business" : "org.union");
            position.addValue("sourceUrl", "https://www.ceoe.es/");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", person.getId(), position.getId(), startDate);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            if (!startDate.isEmpty()) {
                occupancy.addValue("startDate", startDate);
            }
            if (!endDate.isEmpty()) {
                occupancy.addValue("endDate", endDate);
            }
            occupancy.addValue("status", status);
            occupancy.addValue("sourceUrl", "https://www.ceoe.es/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

