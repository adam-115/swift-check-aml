package com.swiftcheck.aml.plugins.itpep.healthdirectors;

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
 * <b>it_pep_health_directors</b> – Directeurs généraux des ASL, des hôpitaux et des entités du service de santé national.
 *
 * <p>Couvre la catégorie <b>IT-1.8</b>, fonction <b>Directeur général d'ASL, d'hôpital ou d'entité du service de santé national</b>
 * (D.Lgs. 231/2007, art. 1 c.2 lett. dd), point 1.8 ; liste notifiée, JO C/2023/724).
 *
 * <p>Directeurs généraux d'Aziende Sanitarie Locali (ASL), Aziende Ospedaliere (AO), AOU et IRCCS publics à travers l'Italie.
 */
@Extension
public class ItPepHealthDirectorsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(ItPepHealthDirectorsParser.class);

    static final String ID_PREFIX = "it-pep-health-directors";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "it";
    static final String SEED_RESOURCE = "/seed/health-directors.json";
    static final String JSON_FILE = "health-directors.json";

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
                String.format(Locale.ROOT, "%d directeurs généraux du service sanitaire national (ASL et hôpitaux) indexés.", count)
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
                        "https://www.salute.gov.it/"));
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
            log.info("Directeurs de la santé italiens : données officielles chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode directors = root.path("directors");
        if (!directors.isArray() || directors.isEmpty()) {
            throw new IllegalStateException("Aucun directeur de santé trouvé dans le fichier : " + rawFile);
        }

        int count = 0;
        for (JsonNode d : directors) {
            String name = d.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String entity = d.path("entity").asText("").trim();
            String region = d.path("region").asText("").trim();
            String role = d.path("role").asText("Direttore Generale").trim();
            String status = d.path("status").asText("current").trim();
            String birthDate = d.path("birthDate").asText("").trim();
            String startDate = d.path("startDate").asText("").trim();
            String endDate = d.path("endDate").asText("").trim();
            String gender = d.path("gender").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role + " – " + entity + " (" + region + ")");
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.salute.gov.it/");
            dataset.addEntity(person);

            String posSlug = entity.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug, role);
            position.addValue("name", role + " – " + entity);
            position.addValue("country", COUNTRY);
            position.addValue("organization", entity + " (Servizio Sanitario Nazionale)");
            position.addValue("topics", "gov.national;corp.public");
            position.addValue("sourceUrl", "https://www.salute.gov.it/");
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
            occupancy.addValue("sourceUrl", "https://www.salute.gov.it/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

