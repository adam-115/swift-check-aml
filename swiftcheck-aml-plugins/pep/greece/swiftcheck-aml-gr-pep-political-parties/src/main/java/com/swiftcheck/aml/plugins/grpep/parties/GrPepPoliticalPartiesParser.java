package com.swiftcheck.aml.plugins.grpep.parties;

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
 * <b>gr_pep_political_parties</b> – Membres des organes dirigeants des partis politiques représentés au Parlement hellénique.
 *
 * <p>Couvre la catégorie <b>GR-c</b>, fonction <b>Membre d'un organe dirigeant d'un parti</b> (fonction 661)
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 */
@Extension
public class GrPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "gr-pep-political-parties";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String SEED_RESOURCE = "/seed/gr-political-parties.json";
    static final String JSON_FILE = "gr-political-parties.json";

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
                String.format(Locale.ROOT, "%d dirigeants de partis politiques helléniques indexés.", count)
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
            log.info("Partis politiques helléniques : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode officials = root.path("officials");
        if (!officials.isArray() || officials.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant de parti trouvé dans : " + rawFile);
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
            String party = o.path("party").asText("").trim();
            String startDate = o.path("startDate").asText("").trim();

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
            if (!party.isEmpty()) {
                person.addValue("political", party);
            }
            person.addValue("sourceUrl", "https://www.hellenicparliament.gr/");
            dataset.addEntity(person);

            if (!position.isEmpty()) {
                String posSlug = position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                FtmEntity pos = dataset.newEntity("Position", posSlug);
                pos.addValue("name", position);
                pos.addValue("country", COUNTRY);
                pos.addValue("organization", party.isEmpty() ? "Partis politiques helléniques" : party);
                pos.addValue("sourceUrl", "https://www.hellenicparliament.gr/");
                dataset.addEntity(pos);

                FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", pos.getId());
                if (!startDate.isEmpty()) {
                    occupancy.addValue("startDate", startDate);
                }
                occupancy.addValue("status", "current");
                dataset.addEntity(occupancy);
            }

            count++;
        }
        return count;
    }
}

