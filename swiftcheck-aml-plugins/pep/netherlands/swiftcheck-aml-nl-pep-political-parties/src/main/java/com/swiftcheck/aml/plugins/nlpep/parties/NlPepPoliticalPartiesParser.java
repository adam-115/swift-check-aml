package com.swiftcheck.aml.plugins.nlpep.parties;

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
 * <b>nl_pep_political_parties</b> – Dirigeants des partis politiques siégeant aux États généraux des Pays-Bas.
 *
 * <p>Couvre la catégorie <b>NL-c</b>, fonction <b>Membre d'un organe dirigeant d'un parti</b> (fonction 382)
 * (Wwft art. 1 ; JO C/2023/724).
 */
@Extension
public class NlPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(NlPepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "nl-pep-political-parties";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "nl";
    static final String SEED_RESOURCE = "/seed/nl-political-parties.json";
    static final String JSON_FILE = "nl-political-parties.json";

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
                String.format(Locale.ROOT, "%d dirigeants des partis politiques des Pays-Bas indexés.", count)
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
                        "https://www.tweedekamer.nl/"));
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
            log.info("Partis politiques néerlandais : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode leaders = root.path("leaders");
        if (!leaders.isArray() || leaders.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode l : leaders) {
            String name = l.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String firstName = l.path("firstName").asText("");
            String lastName = l.path("lastName").asText("");
            String party = l.path("party").asText("Politieke Partij");
            String position = l.path("position").asText("Partijleider");
            String since = l.path("since").asText("");

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            if (!firstName.isEmpty()) {
                person.addValue("firstName", firstName);
            }
            if (!lastName.isEmpty()) {
                person.addValue("lastName", lastName);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", position + " – " + party);
            person.addValue("sourceUrl", "https://www.tweedekamer.nl/");
            dataset.addEntity(person);

            String posSlug = (party + "-" + position).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity posEntity = dataset.newEntity("Position", posSlug);
            posEntity.addValue("name", position + " – " + party);
            posEntity.addValue("country", COUNTRY);
            posEntity.addValue("organization", party);
            posEntity.addValue("topics", "pol.party");
            posEntity.addValue("sourceUrl", "https://www.tweedekamer.nl/");
            dataset.addEntity(posEntity);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", posEntity.getId());
            occupancy.addValue("status", "current");
            if (!since.isEmpty()) {
                occupancy.addValue("startDate", since);
            }
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

