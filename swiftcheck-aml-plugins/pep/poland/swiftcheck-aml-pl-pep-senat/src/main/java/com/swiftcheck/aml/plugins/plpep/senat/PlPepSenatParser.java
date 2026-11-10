package com.swiftcheck.aml.plugins.plpep.senat;

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
 * <b>pl_pep_senat</b> – Sénateurs de la République de Pologne (Senat RP).
 *
 * <p>Couvre la catégorie <b>PL-b</b>, fonction <b>Sénateur</b> (fonction 486)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 */
@Extension
public class PlPepSenatParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepSenatParser.class);

    static final String ID_PREFIX = "pl-pep-senat";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String SEED_RESOURCE = "/seed/pl-senat.json";
    static final String JSON_FILE = "pl-senat.json";

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
                String.format(Locale.ROOT, "%d sénateurs de la République de Pologne indexés.", count)
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
                        "https://www.senat.gov.pl/"));
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
            log.info("Sénat polonais : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode senators = root.path("senators");
        if (!senators.isArray() || senators.isEmpty()) {
            throw new IllegalStateException("Aucun sénateur trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode s : senators) {
            String name = s.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }

            String birthDate = s.path("birthDate").asText("").trim();
            String position = s.path("position").asText("").trim();
            String party = s.path("party").asText("").trim();
            String startDate = s.path("startDate").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!position.isEmpty()) {
                person.addValue("position", position);
            }
            person.addValue("sourceUrl", "https://www.senat.gov.pl/");
            dataset.addEntity(person);

            String posName = position.isEmpty() ? "Senator Rzeczypospolitej Polskiej" : position;
            String posSlug = (posName + (party.isEmpty() ? "" : "-" + party)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", posName);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Senat Rzeczypospolitej Polskiej" + (party.isEmpty() ? "" : " (" + party + ")"));
            pos.addValue("sourceUrl", "https://www.senat.gov.pl/");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            if (!startDate.isEmpty()) {
                occupancy.addValue("startDate", startDate);
            }
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}
