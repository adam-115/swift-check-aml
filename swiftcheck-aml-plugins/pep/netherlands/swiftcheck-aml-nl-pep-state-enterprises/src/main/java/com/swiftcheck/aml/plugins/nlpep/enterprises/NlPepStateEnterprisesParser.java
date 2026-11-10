package com.swiftcheck.aml.plugins.nlpep.enterprises;

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
 * <b>nl_pep_state_enterprises</b> – Dirigeants des entreprises publiques et participations de l'État néerlandais (Staatsdeelnemingen).
 *
 * <p>Couvre la catégorie <b>NL-g</b>, fonction <b>Organe dirigeant d'une entreprise publique</b> (fonction 389)
 * (Wwft art. 1 ; JO C/2023/724).
 */
@Extension
public class NlPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(NlPepStateEnterprisesParser.class);

    static final String ID_PREFIX = "nl-pep-state-enterprises";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "nl";
    static final String SEED_RESOURCE = "/seed/nl-state-enterprises.json";
    static final String JSON_FILE = "nl-state-enterprises.json";

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
                String.format(Locale.ROOT, "%d dirigeants des entreprises publiques d'État des Pays-Bas indexés.", count)
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
            log.info("Entreprises publiques néerlandaises : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode executives = root.path("executives");
        if (!executives.isArray() || executives.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode e : executives) {
            String name = e.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String firstName = e.path("firstName").asText("");
            String lastName = e.path("lastName").asText("");
            String company = e.path("company").asText("Staatsdeelneming");
            String position = e.path("position").asText("Bestuurder van een staatsdeelneming");
            String since = e.path("since").asText("");

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
            person.addValue("sourceUrl", "https://www.rijksoverheid.nl/onderwerpen/staatsdeelnemingen");
            dataset.addEntity(person);

            FtmEntity compEntity = dataset.newEntity("Company", "co-" + company.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
            compEntity.addValue("name", company);
            compEntity.addValue("country", COUNTRY);
            compEntity.addValue("jurisdiction", COUNTRY);
            dataset.addEntity(compEntity);

            FtmEntity posEntity = dataset.newEntity("Position", "pos-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
            posEntity.addValue("name", position);
            posEntity.addValue("country", COUNTRY);
            posEntity.addValue("organization", company);
            posEntity.addValue("topics", "gov.soe");
            posEntity.addValue("topics", "corp.governance");
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

