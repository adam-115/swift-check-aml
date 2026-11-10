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
    static final String SEED_RESOURCE = "/seed/court-of-audit.json";
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
        try (InputStream in = getClass().getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource de secours introuvable : " + SEED_RESOURCE);
            }
            Files.copy(in, rawFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Tribunal de Cuentas espagnol : données officielles chargées depuis {}", SEED_RESOURCE);
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

