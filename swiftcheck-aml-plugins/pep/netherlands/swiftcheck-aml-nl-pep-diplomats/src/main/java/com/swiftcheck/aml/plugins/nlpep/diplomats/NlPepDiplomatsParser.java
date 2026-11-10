package com.swiftcheck.aml.plugins.nlpep.diplomats;

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
 * <b>nl_pep_diplomats</b> – Ambassadeurs et chefs de mission diplomatique des Pays-Bas.
 *
 * <p>Couvre la catégorie <b>NL-f</b>, fonction <b>Ambassadeur ou chargé d'affaires</b> (fonction 387)
 * (Wwft art. 1 ; JO C/2023/724).
 */
@Extension
public class NlPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(NlPepDiplomatsParser.class);

    static final String ID_PREFIX = "nl-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "nl";
    static final String SEED_RESOURCE = "/seed/nl-diplomats.json";
    static final String JSON_FILE = "nl-diplomats.json";

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
                String.format(Locale.ROOT, "%d ambassadeurs et représentants permanents des Pays-Bas indexés.", count)
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
                        "https://www.rijksoverheid.nl/"));
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
            log.info("Diplomates néerlandais : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode ambassadors = root.path("ambassadors");
        if (!ambassadors.isArray() || ambassadors.isEmpty()) {
            throw new IllegalStateException("Aucun ambassadeur trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode a : ambassadors) {
            String name = a.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String firstName = a.path("firstName").asText("");
            String lastName = a.path("lastName").asText("");
            String post = a.path("post").asText("Ambassade van Nederland");
            String position = a.path("position").asText("Ambassadeur");
            String hostCountry = a.path("country").asText("");
            String since = a.path("since").asText("");

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
            person.addValue("position", position);
            person.addValue("sourceUrl", "https://www.rijksoverheid.nl/");
            dataset.addEntity(person);

            FtmEntity posEntity = dataset.newEntity("Position", "amb-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
            posEntity.addValue("name", position);
            posEntity.addValue("country", COUNTRY);
            if (!hostCountry.isEmpty()) {
                posEntity.addValue("country", hostCountry);
            }
            posEntity.addValue("organization", post);
            posEntity.addValue("topics", "gov.diplomatic");
            posEntity.addValue("topics", "gov.national");
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

