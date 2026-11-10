package com.swiftcheck.aml.plugins.ropep.ccr;

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
 * <b>ro_pep_constitutional_court</b> – Juges de la Cour Constitutionnelle de Roumanie (Curtea Constituțională a României - CCR).
 *
 * <p>Couvre la catégorie <b>RO-d</b>, fonction <b>Juge de la Cour suprême ou constitutionnelle</b> (fonction 738)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 */
@Extension
public class RoPepConstitutionalCourtParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepConstitutionalCourtParser.class);

    static final String ID_PREFIX = "ro-pep-ccr";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String SEED_RESOURCE = "/seed/ro-constitutional-court.json";
    static final String JSON_FILE = "ro-constitutional-court.json";

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
                String.format(Locale.ROOT, "%d juges de la Cour Constitutionnelle de Roumanie indexés.", count)
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
                        "https://www.ccr.ro/"));
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
            log.info("Cour Constitutionnelle roumaine : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode judges = root.path("judges");
        if (!judges.isArray() || judges.isEmpty()) {
            throw new IllegalStateException("Aucun juge trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode j : judges) {
            String name = j.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }

            String birthDate = j.path("birthDate").asText("").trim();
            String position = j.path("position").asText("Judecător la Curtea Constituțională a României").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            person.addValue("position", position);
            person.addValue("sourceUrl", "https://www.ccr.ro/");
            dataset.addEntity(person);

            String posSlug = position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Curtea Constituțională a României");
            pos.addValue("topics", "gov.judiciary");
            pos.addValue("sourceUrl", "https://www.ccr.ro/");
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

