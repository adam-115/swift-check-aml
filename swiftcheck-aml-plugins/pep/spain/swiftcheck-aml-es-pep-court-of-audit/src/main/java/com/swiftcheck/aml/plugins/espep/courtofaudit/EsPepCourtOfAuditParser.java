package com.swiftcheck.aml.plugins.espep.courtofaudit;

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
 * <b>es_pep_court_of_audit</b> – Membres du Tribunal de Cuentas du Royaume d'Espagne.
 *
 * <p>Couvre la catégorie <b>ES-d</b>, fonction <b>Président ou conseiller du Tribunal de Cuentas</b>
 * (Ley 10/2010, art. 14.2 ; Ley Orgánica 2/1982 del Tribunal de Cuentas ; JO C/2023/724).
 */
@Extension
public class EsPepCourtOfAuditParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepCourtOfAuditParser.class);

    static final String ID_PREFIX = "es-pep-court-of-audit";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "es";
    static final String JSON_FILE = "court-of-audit.json";

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
                String.format(Locale.ROOT, "%d membres du Tribunal de Cuentas d'Espagne indexés.", count)
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
                        "https://www.tcu.es/"));
            }
        }
        return records;
    }

    private void loadResource(Path rawFile) throws IOException {
        List<String[]> found = extract(fetch(SOURCE_URL));
        if (found.isEmpty()) {
            throw new IllegalStateException("Aucun membre du Pleno trouvé sur " + SOURCE_URL + " (structure modifiée ?)");
        }
        com.fasterxml.jackson.databind.node.ObjectNode root = json.createObjectNode();
        root.put("country", COUNTRY);
        com.fasterxml.jackson.databind.node.ArrayNode arr = root.putArray("members");
        for (String[] f : found) {
            arr.addObject().put("name", f[0]).put("role", f[1]).put("status", "current");
        }
        Files.writeString(rawFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        log.info("Tribunal de Cuentas : {} membres du Pleno lus sur {}", found.size(), SOURCE_URL);
    }

    static final String SOURCE_URL = "https://www.tcu.es/es/institucion/organizacion-y-gobernanza/";
    private static final java.util.regex.Pattern CARD = java.util.regex.Pattern.compile(
            "<div class=\"nombre\">([^<]+)</div>\\s*<div class=\"cargo\">([^<]+)</div>");

    /** Extrait [nom, fonction] des fiches « Miembros del Pleno » (dédoublonnées). */
    static List<String[]> extract(String html) {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.regex.Matcher m = CARD.matcher(html);
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
        JsonNode members = root.path("members");
        if (!members.isArray() || members.isEmpty()) {
            throw new IllegalStateException("Aucun membre trouvé dans le fichier : " + rawFile);
        }

        int count = 0;
        for (JsonNode m : members) {
            String name = m.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String role = m.path("role").asText("").trim();
            String status = m.path("status").asText("current").trim();
            String birthDate = m.path("birthDate").asText("").trim();
            String startDate = m.path("startDate").asText("").trim();
            String endDate = m.path("endDate").asText("").trim();
            String gender = m.path("gender").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.tcu.es/");
            dataset.addEntity(person);

            String posSlug = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", role);
            position.addValue("country", COUNTRY);
            position.addValue("organization", "Tribunal de Cuentas del Reino de España");
            position.addValue("topics", "gov.national;gov.financial");
            position.addValue("sourceUrl", "https://www.tcu.es/");
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
            occupancy.addValue("sourceUrl", "https://www.tcu.es/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

