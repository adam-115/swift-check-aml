package com.swiftcheck.aml.plugins.grpep.rca;

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
 * <b>gr_pep_rca</b> – Membres de la famille et proches associés de PEP grecques (RCA).
 *
 * <p>Couvre la catégorie <b>GR-RCA</b>, fonctions:
 * <ul>
 *   <li><b>Membre de la famille d'une PEP grecque</b> (fonction 669)</li>
 *   <li><b>Proche associé d'une PEP grecque</b> (fonction 670)</li>
 * </ul>
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 */
@Extension
public class GrPepRcaParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepRcaParser.class);

    static final String ID_PREFIX = "gr-pep-rca";
    static final String TOPIC = "role.rca";
    static final String COUNTRY = "gr";
    static final String SEED_RESOURCE = "/seed/gr-rca.json";
    static final String JSON_FILE = "gr-rca.json";

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
                String.format(Locale.ROOT, "%d proches et membres de la famille de PEP helléniques indexés.", count)
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
                        "https://www.hellenicparliament.gr/"));
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
            log.info("RCA helléniques : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode officials = root.path("officials");
        if (!officials.isArray() || officials.isEmpty()) {
            throw new IllegalStateException("Aucun RCA trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode o : officials) {
            String name = o.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }

            String originalName = o.path("originalName").asText("").trim();
            String birthDate = o.path("birthDate").asText("").trim();
            String relationship = o.path("relationship").asText("").trim();
            String targetPep = o.path("targetPep").asText("").trim();
            String rcaType = o.path("rcaType").asText("FAMILY").trim();

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
            if (!relationship.isEmpty()) {
                person.addValue("notes", relationship);
            }
            person.addValue("sourceUrl", "https://www.hellenicparliament.gr/");
            dataset.addEntity(person);

            // Add relationship to target PEP
            if (!targetPep.isEmpty()) {
                FtmEntity target = dataset.newEntity("Person", targetPep);
                target.addValue("name", targetPep);
                target.addValue("country", COUNTRY);
                target.addValue("topics", "role.pep");
                dataset.addEntity(target);

                if ("FAMILY".equalsIgnoreCase(rcaType)) {
                    FtmEntity family = dataset.newEntity("Family", person.getId() + "-rel-" + target.getId());
                    family.addValue("person", person.getId());
                    family.addValue("relative", target.getId());
                    family.addValue("relationship", relationship);
                    dataset.addEntity(family);
                } else {
                    FtmEntity assoc = dataset.newEntity("Associate", person.getId() + "-assoc-" + target.getId());
                    assoc.addValue("person", person.getId());
                    assoc.addValue("associate", target.getId());
                    assoc.addValue("relationship", relationship);
                    dataset.addEntity(assoc);
                }
            }

            count++;
        }
        return count;
    }
}

