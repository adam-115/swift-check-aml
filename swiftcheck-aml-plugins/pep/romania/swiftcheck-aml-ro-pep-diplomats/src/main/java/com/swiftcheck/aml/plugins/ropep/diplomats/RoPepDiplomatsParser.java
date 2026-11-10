package com.swiftcheck.aml.plugins.ropep.diplomats;

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
 * <b>ro_pep_diplomats</b> – Ambassadeurs extraordinaires et plénipotentiaires et chefs de mission diplomatique de Roumanie.
 *
 * <p>Couvre la catégorie <b>RO-f</b>, fonction <b>Ambassadeur</b> (fonction 740)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 */
@Extension
public class RoPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepDiplomatsParser.class);

    static final String ID_PREFIX = "ro-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String SEED_RESOURCE = "/seed/ro-diplomats.json";
    static final String JSON_FILE = "ro-diplomats.json";

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
                String.format(Locale.ROOT, "%d ambassadeurs et représentants diplomatiques de Roumanie indexés.", count)
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
                        "https://www.mae.ro/"));
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
            log.info("Diplomates roumains : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode diplomats = root.path("diplomats");
        if (!diplomats.isArray() || diplomats.isEmpty()) {
            throw new IllegalStateException("Aucun diplomate trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode d : diplomats) {
            String name = d.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }

            String birthDate = d.path("birthDate").asText("").trim();
            String position = d.path("position").asText("Ambasador al României").trim();
            String post = d.path("post").asText("Ministerul Afacerilor Externe").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            person.addValue("position", position);
            person.addValue("sourceUrl", "https://www.mae.ro/");
            dataset.addEntity(person);

            String posSlug = position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", post);
            pos.addValue("topics", "gov.diplomatic");
            pos.addValue("sourceUrl", "https://www.mae.ro/");
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

