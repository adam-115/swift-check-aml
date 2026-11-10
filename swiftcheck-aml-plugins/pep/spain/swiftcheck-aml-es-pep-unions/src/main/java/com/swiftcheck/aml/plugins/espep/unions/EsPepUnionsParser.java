package com.swiftcheck.aml.plugins.espep.unions;

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
 * <b>es_pep_unions</b> – Haute direction des syndicats et des organisations patronales d'Espagne.
 *
 * <p>Couvre la catégorie <b>ES-SIND</b>, fonction <b>Membre de la haute direction d'un syndicat ou d'une organisation patronale</b>
 * (Ley 10/2010, art. 14.3 d) ; JO C/2023/724).
 *
 * <p>CCOO, UGT, CEOE, CEPYME, CSIF, USO, ATA, ELA, CIG.
 */
@Extension
public class EsPepUnionsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepUnionsParser.class);

    static final String ID_PREFIX = "es-pep-unions";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "es";
    static final String SEED_RESOURCE = "/seed/spanish-unions-leaders.json";
    static final String JSON_FILE = "spanish-unions-leaders.json";

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
                String.format(Locale.ROOT, "%d dirigeants des syndicats et organisations patronales d'Espagne indexés.", count)
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
                        "https://www.ceoe.es/"));
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
            log.info("Syndicats et patronat espagnols : données officielles chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode leaders = root.path("leaders");
        if (!leaders.isArray() || leaders.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant trouvé dans le fichier : " + rawFile);
        }

        int count = 0;
        for (JsonNode l : leaders) {
            String name = l.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String org = l.path("org").asText("").trim();
            String role = l.path("role").asText("").trim();
            String type = l.path("type").asText("union").trim();
            String status = l.path("status").asText("current").trim();
            String birthDate = l.path("birthDate").asText("").trim();
            String startDate = l.path("startDate").asText("").trim();
            String endDate = l.path("endDate").asText("").trim();
            String gender = l.path("gender").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role + " – " + org);
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.ceoe.es/");
            dataset.addEntity(person);

            String posSlug = (org + "-" + role).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", role + " – " + org);
            position.addValue("country", COUNTRY);
            position.addValue("organization", org);
            position.addValue("topics", "business_org".equals(type) ? "corp.business" : "org.union");
            position.addValue("sourceUrl", "https://www.ceoe.es/");
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
            occupancy.addValue("sourceUrl", "https://www.ceoe.es/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

