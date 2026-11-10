package com.swiftcheck.aml.plugins.ptpep.rca;

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
 * <b>pt_pep_rca</b> – Membres de la famille et proches associés de PEP portugais (RCA).
 *
 * <p>Couvre la catégorie <b>PT-RCA</b>, fonctions <b>Membre de la famille d'une PEP portugaise</b> (fonction 271)
 * et <b>Proche associé d'une PEP portugaise</b> (fonction 272)
 * (Lei n.º 83/2017, art. 2.º, al. dd) et ee) ; JO C/2023/724).
 */
@Extension
public class PtPepRcaParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepRcaParser.class);

    static final String ID_PREFIX = "pt-pep-rca";
    static final String TOPIC = "role.rca";
    static final String COUNTRY = "pt";
    static final String SEED_RESOURCE = "/seed/pt-rca.json";
    static final String JSON_FILE = "pt-rca.json";

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
                String.format(Locale.ROOT, "%d proches et associés (RCA) de PEP portugais indexés.", count)
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
                        "https://www.tribunalconstitucional.pt/tc/transparencia.html"));
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
            log.info("RCA Portugal : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode associates = root.path("associates");
        if (!associates.isArray() || associates.isEmpty()) {
            throw new IllegalStateException("Aucun associé trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode a : associates) {
            String name = a.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String firstName = a.path("firstName").asText("");
            String lastName = a.path("lastName").asText("");
            String relationship = a.path("relationship").asText("Proche d'une PEP");
            String targetPep = a.path("targetPep").asText("");
            String category = a.path("category").asText("FAMILY");

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
            person.addValue("summary", relationship);
            person.addValue("sourceUrl", "https://www.tribunalconstitucional.pt/tc/transparencia.html");
            dataset.addEntity(person);

            FtmEntity target = dataset.newEntity("Person", targetPep);
            target.addValue("name", targetPep);
            target.addValue("country", COUNTRY);
            dataset.addEntity(target);

            if ("FAMILY".equalsIgnoreCase(category)) {
                FtmEntity edge = dataset.newEntity("Family", "rel-" + person.getId() + "-" + target.getId());
                edge.addValue("person", target.getId());
                edge.addValue("relative", person.getId());
                edge.addValue("relationship", relationship);
                dataset.addEntity(edge);
            } else {
                FtmEntity edge = dataset.newEntity("Associate", "rel-" + person.getId() + "-" + target.getId());
                edge.addValue("person", target.getId());
                edge.addValue("associate", person.getId());
                edge.addValue("relationship", relationship);
                dataset.addEntity(edge);
            }

            count++;
        }
        return count;
    }
}
