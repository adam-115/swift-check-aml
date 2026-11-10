package com.swiftcheck.aml.plugins.ropep.orgs;

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
 * <b>ro_pep_international_orgs</b> – Dirigeants et représentants roumains d'organisations internationales et européennes (ECCC, SELEC, EPPO).
 *
 * <p>Couvre la catégorie <b>RO-h</b>, fonction <b>Dirigeant d'une organisation internationale</b> (fonction 743)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 */
@Extension
public class RoPepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepInternationalOrgsParser.class);

    static final String ID_PREFIX = "ro-pep-orgs";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String SEED_RESOURCE = "/seed/ro-international-orgs.json";
    static final String JSON_FILE = "ro-international-orgs.json";

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
                String.format(Locale.ROOT, "%d dirigeants d'organisations internationales indexés pour la Roumanie.", count)
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
                        "https://cybersecurity-centre.europa.eu/"));
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
            log.info("Organisations internationales roumaines : données chargées depuis {}", SEED_RESOURCE);
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

            String birthDate = o.path("birthDate").asText("").trim();
            String position = o.path("position").asText("Dirigeant d'organisation internationale").trim();
            String org = o.path("organization").asText("Organisation Internationale").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            person.addValue("position", position);
            person.addValue("sourceUrl", "https://cybersecurity-centre.europa.eu/");
            dataset.addEntity(person);

            String posSlug = position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", org);
            pos.addValue("topics", "role.pep");
            pos.addValue("sourceUrl", "https://cybersecurity-centre.europa.eu/");
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

