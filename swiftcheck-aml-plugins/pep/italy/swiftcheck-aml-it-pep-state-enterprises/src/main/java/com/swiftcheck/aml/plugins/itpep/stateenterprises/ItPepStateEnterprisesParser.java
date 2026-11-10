package com.swiftcheck.aml.plugins.itpep.stateenterprises;

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
 * <b>it_pep_state_enterprises</b> – Dirigeants et administrateurs des sociétés contrôlées par l'État italien.
 *
 * <p>Couvre la catégorie <b>IT-1.7</b>, fonction <b>Organe d'une société contrôlée par l'État, une Région ou une commune concernée</b>
 * (D.Lgs. 231/2007, art. 1 c.2 lett. dd), point 1.7 ; liste notifiée, JO C/2023/724).
 *
 * <p>Administrateurs délégués et présidents des conseils d'administration des grandes participations publiques sous tutelle du MEF
 * (ENI, Enel, Leonardo, Poste Italiane, FS, CDP, RAI, Terna, Snam, Invitalia, etc.).
 */
@Extension
public class ItPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(ItPepStateEnterprisesParser.class);

    static final String ID_PREFIX = "it-pep-state-enterprises";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "it";
    static final String SEED_RESOURCE = "/seed/state-enterprises-leaders.json";
    static final String JSON_FILE = "state-enterprises-leaders.json";

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
                String.format(Locale.ROOT, "%d dirigeants des sociétés publiques de l'État italien (MEF).", count)
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
                        "https://www.mef.gov.it/"));
            }
        }
        return records;
    }

    private void loadResource(Path rawFile) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource de secours introuvable : " + SEED_RESOURCE);
            }
            Files.copy(in, rawFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Sociétés publiques italiennes : données certifiées chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode executives = root.path("executives");
        if (!executives.isArray() || executives.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant d'entreprise publique trouvé dans le fichier : " + rawFile);
        }

        int count = 0;
        for (JsonNode e : executives) {
            String name = e.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String company = e.path("company").asText("").trim();
            String role = e.path("role").asText("Dirigente societario").trim();
            String status = e.path("status").asText("current").trim();
            String birthDate = e.path("birthDate").asText("").trim();
            String startDate = e.path("startDate").asText("").trim();
            String endDate = e.path("endDate").asText("").trim();
            String gender = e.path("gender").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role + " – " + company);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.mef.gov.it/");
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", company.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"), role);
            position.addValue("name", role + " – " + company);
            position.addValue("country", COUNTRY);
            position.addValue("organization", company);
            position.addValue("topics", "gov.soe");
            position.addValue("sourceUrl", "https://www.mef.gov.it/");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", person.getId(), position.getId(), startDate);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            if (!startDate.isEmpty()) {
                occupancy.addValue("startDate", startDate);
            }
            if (!endDate.isEmpty()) {
                occupancy.addValue("endDate", endDate);
            }
            occupancy.addValue("status", status);
            occupancy.addValue("sourceUrl", "https://www.mef.gov.it/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

