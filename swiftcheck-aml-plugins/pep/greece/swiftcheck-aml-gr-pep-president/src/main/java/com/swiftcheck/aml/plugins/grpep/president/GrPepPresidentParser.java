package com.swiftcheck.aml.plugins.grpep.president;

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
 * <b>gr_pep_president</b> – Président de la République hellénique (Πρόεδρος της Δημοκρατίας) et hauts dirigeants de la Présidence.
 *
 * <p>Couvre la catégorie <b>GR-a</b>, fonction <b>Président de la République</b> (fonction 657)
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 */
@Extension
public class GrPepPresidentParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepPresidentParser.class);

    static final String ID_PREFIX = "gr-pep-president";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String SEED_RESOURCE = "/seed/gr-president.json";
    static final String JSON_FILE = "gr-president.json";

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
                String.format(Locale.ROOT, "%d hauts dirigeants de la Présidence hellénique indexés.", count)
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
                        "https://www.presidency.gr/"));
            }
        }
        return records;
    }

    private void loadResource(Path rawFile) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource introuvable : " + SEED_RESOURCE);
            }
            Files.copy(in, rawFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Présidence hellénique : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode officials = root.path("officials");
        if (!officials.isArray() || officials.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode o : officials) {
            String name = o.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }

            String originalName = o.path("originalName").asText("").trim();
            String birthDate = o.path("birthDate").asText("").trim();
            String position = o.path("position").asText("").trim();
            String startDate = o.path("startDate").asText("").trim();
            String endDate = o.path("endDate").asText("").trim();
            String notes = o.path("notes").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            if (!originalName.isEmpty()) {
                person.addValue("alias", originalName);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!position.isEmpty()) {
                person.addValue("position", position);
            }
            if (!notes.isEmpty()) {
                person.addValue("notes", notes);
            }
            person.addValue("sourceUrl", "https://www.presidency.gr/");
            dataset.addEntity(person);

            if (!position.isEmpty()) {
                String posSlug = position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                FtmEntity pos = dataset.newEntity("Position", posSlug);
                pos.addValue("name", position);
                pos.addValue("country", COUNTRY);
                pos.addValue("organization", "Προεδρία της Ελληνικής Δημοκρατίας");
                pos.addValue("sourceUrl", "https://www.presidency.gr/");
                dataset.addEntity(pos);

                FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", pos.getId());
                if (!startDate.isEmpty()) {
                    occupancy.addValue("startDate", startDate);
                }
                if (!endDate.isEmpty()) {
                    occupancy.addValue("endDate", endDate);
                    occupancy.addValue("status", "ended");
                } else {
                    occupancy.addValue("status", "current");
                }
                dataset.addEntity(occupancy);
            }

            count++;
        }
        return count;
    }
}

