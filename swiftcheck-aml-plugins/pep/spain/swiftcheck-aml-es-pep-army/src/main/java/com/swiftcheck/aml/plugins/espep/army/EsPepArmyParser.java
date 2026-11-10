package com.swiftcheck.aml.plugins.espep.army;

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
 * <b>es_pep_army</b> – Haut commandement des Forces armées espagnoles (Fuerzas Armadas).
 *
 * <p>Couvre la catégorie <b>ES-e</b>, fonction <b>Chef d'état-major ou officier général</b>
 * (Ley 10/2010, art. 14.2 ; JO C/2023/724).
 *
 * <p>JEMAD, JEME (Terre), AJEMA (Armada), JEMA (Air et Espace), Guardia Civil, MOPS, JEMACON.
 */
@Extension
public class EsPepArmyParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepArmyParser.class);

    static final String ID_PREFIX = "es-pep-army";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "es";
    static final String SEED_RESOURCE = "/seed/spanish-military-leaders.json";
    static final String JSON_FILE = "spanish-military-leaders.json";

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
                String.format(Locale.ROOT, "%d hauts commandants des Forces armées espagnoles indexés.", count)
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
                        "https://www.defensa.gob.es/"));
            }
        }
        return records;
    }

    private void loadResource(Path rawFile) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource de secours introuvable : " + SEED_RESOURCE);
            }
            Files.copy(in, rawFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Haut commandement militaire espagnol : données officielles chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode commanders = root.path("commanders");
        if (!commanders.isArray() || commanders.isEmpty()) {
            throw new IllegalStateException("Aucun officier trouvé dans le fichier : " + rawFile);
        }

        int count = 0;
        for (JsonNode c : commanders) {
            String name = c.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String role = c.path("role").asText("").trim();
            String rank = c.path("rank").asText("").trim();
            String branch = c.path("branch").asText("Fuerzas Armadas").trim();
            String status = c.path("status").asText("current").trim();
            String birthDate = c.path("birthDate").asText("").trim();
            String startDate = c.path("startDate").asText("").trim();
            String endDate = c.path("endDate").asText("").trim();
            String gender = c.path("gender").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", rank + " – " + role + " (" + branch + ")");
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.defensa.gob.es/");
            dataset.addEntity(person);

            String posSlug = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", role);
            position.addValue("country", COUNTRY);
            position.addValue("organization", branch + " (Ministerio de Defensa)");
            position.addValue("topics", "gov.national;mil");
            position.addValue("sourceUrl", "https://www.defensa.gob.es/");
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
            occupancy.addValue("sourceUrl", "https://www.defensa.gob.es/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

