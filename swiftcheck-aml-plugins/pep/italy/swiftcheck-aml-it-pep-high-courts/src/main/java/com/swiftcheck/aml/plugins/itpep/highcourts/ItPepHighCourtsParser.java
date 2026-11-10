package com.swiftcheck.aml.plugins.itpep.highcourts;

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
 * <b>it_pep_high_courts</b> – Magistrats de la Cour de cassation, de la Cour des comptes et conseillers d'État italiens.
 *
 * <p>Couvre la catégorie <b>IT-1.4</b>, fonction <b>Magistrat de la Cour de cassation, de la Cour des comptes ; conseiller d'État</b>
 * (D.Lgs. 231/2007, art. 1 c.2 lett. dd), point 1.4 ; liste notifiée, JO C/2023/724).
 *
 * <p>Premiers présidents, procureurs généraux, présidents adjoints et présidents de section de la Corte Suprema di Cassazione,
 * du Consiglio di Stato et de la Corte dei conti.
 */
@Extension
public class ItPepHighCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(ItPepHighCourtsParser.class);

    static final String ID_PREFIX = "it-pep-high-courts";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "it";
    static final String SEED_RESOURCE = "/seed/high-courts-magistrates.json";
    static final String JSON_FILE = "high-courts-magistrates.json";

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
                String.format(Locale.ROOT, "%d hauts magistrats italiens (Cassazione, Consiglio di Stato, Corte dei conti).", count)
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
                        "https://www.giustizia.it/"));
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
            log.info("Alte magistrature : données certifiées chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode magistrates = root.path("magistrates");
        if (!magistrates.isArray() || magistrates.isEmpty()) {
            throw new IllegalStateException("Aucun magistrat trouvé dans le fichier : " + rawFile);
        }

        int count = 0;
        for (JsonNode m : magistrates) {
            String name = m.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String court = m.path("court").asText("").trim();
            String role = m.path("role").asText("Magistrato delle giurisdizioni superiori").trim();
            String status = m.path("status").asText("current").trim();
            String birthDate = m.path("birthDate").asText("").trim();
            String startDate = m.path("startDate").asText("").trim();
            String endDate = m.path("endDate").asText("").trim();
            String gender = m.path("gender").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role + " – " + court);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.giustizia.it/");
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", court.toLowerCase(Locale.ROOT).replaceAll("\\s+", "-"), role);
            position.addValue("name", role + " – " + court);
            position.addValue("country", COUNTRY);
            position.addValue("organization", court);
            position.addValue("topics", "gov.national;gov.judicial");
            position.addValue("sourceUrl", "https://www.giustizia.it/");
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
            occupancy.addValue("sourceUrl", "https://www.giustizia.it/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

