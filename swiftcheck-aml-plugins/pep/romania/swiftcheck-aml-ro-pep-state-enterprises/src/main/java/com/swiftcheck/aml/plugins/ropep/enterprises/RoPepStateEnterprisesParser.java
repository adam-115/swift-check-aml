package com.swiftcheck.aml.plugins.ropep.enterprises;

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
 * <b>ro_pep_state_enterprises</b> – Dirigeants des entreprises publiques et sociétés d'État roumaines.
 *
 * <p>Couvre la catégorie <b>RO-g</b>, fonction <b>Dirigeant d'une entreprise détenue par l'État</b> (fonction 742)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 */
@Extension
public class RoPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepStateEnterprisesParser.class);

    static final String ID_PREFIX = "ro-pep-enterprises";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String SEED_RESOURCE = "/seed/ro-state-enterprises.json";
    static final String JSON_FILE = "ro-state-enterprises.json";

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
                String.format(Locale.ROOT, "%d dirigeants d'entreprises publiques roumaines indexés.", count)
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
                        "https://mfinante.gov.ro/"));
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
            log.info("Entreprises publiques roumaines : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode executives = root.path("executives");
        if (!executives.isArray() || executives.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant d'entreprise trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode exec : executives) {
            String name = exec.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }

            String birthDate = exec.path("birthDate").asText("").trim();
            String position = exec.path("position").asText("Director General de Întreprindere Publică").trim();
            String company = exec.path("company").asText("Companie de Stat din România").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            person.addValue("position", position);
            person.addValue("sourceUrl", "https://mfinante.gov.ro/");
            dataset.addEntity(person);

            String posSlug = (position + "-" + company).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", company);
            pos.addValue("topics", "corp.soe");
            pos.addValue("sourceUrl", "https://mfinante.gov.ro/");
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

