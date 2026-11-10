package com.swiftcheck.aml.plugins.plpep.rca;

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
 * <b>pl_pep_rca</b> – Membres de la famille et proches associés des personnalités politiques polonaises (RCA).
 *
 * <p>Couvre la catégorie <b>PL-RCA</b>, fonctions <b>Membre de la famille</b> (497) et <b>Proche associé</b> (498)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 */
@Extension
public class PlPepRcaParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepRcaParser.class);

    static final String ID_PREFIX = "pl-pep-rca";
    static final String TOPIC = "role.rca";
    static final String COUNTRY = "pl";
    static final String SEED_RESOURCE = "/seed/pl-rca.json";
    static final String JSON_FILE = "pl-rca.json";

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
                String.format(Locale.ROOT, "%d membres de la famille et proches associés de PPE polonaises indexés.", count)
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
                        "https://www.prezydent.pl/"));
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
            log.info("RCA Pologne : données chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode rca = root.path("rca");
        if (!rca.isArray() || rca.isEmpty()) {
            throw new IllegalStateException("Aucun membre RCA trouvé dans : " + rawFile);
        }

        int count = 0;
        for (JsonNode r : rca) {
            String name = r.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }

            String birthDate = r.path("birthDate").asText("").trim();
            String category = r.path("category").asText("family").trim().toLowerCase(Locale.ROOT);
            String relationship = r.path("relationship").asText("relative").trim();
            String targetPep = r.path("targetPep").asText("").trim();
            String position = r.path("position").asText("").trim();

            // Création de l'entité de la personne RCA
            FtmEntity rcaPerson = dataset.newEntity("Person", name);
            rcaPerson.addValue("name", name);
            rcaPerson.addValue("country", COUNTRY);
            rcaPerson.addValue("nationality", COUNTRY);
            if (!birthDate.isEmpty()) {
                rcaPerson.addValue("birthDate", birthDate);
            }
            if (!position.isEmpty()) {
                rcaPerson.addValue("position", position);
            }
            rcaPerson.addValue("topics", TOPIC);
            rcaPerson.addValue("sourceUrl", "https://www.prezydent.pl/");
            dataset.addEntity(rcaPerson);

            // Création de la relation avec la PEP cible si spécifiée
            if (!targetPep.isEmpty()) {
                FtmEntity pepPerson = dataset.newEntity("Person", targetPep);
                pepPerson.addValue("name", targetPep);
                pepPerson.addValue("country", COUNTRY);
                pepPerson.addValue("topics", "role.pep");
                dataset.addEntity(pepPerson);

                if ("associate".equals(category)) {
                    FtmEntity edge = dataset.newEntity("Associate", "rel-" + rcaPerson.getId() + "-" + pepPerson.getId());
                    edge.addValue("person", pepPerson.getId());
                    edge.addValue("associate", rcaPerson.getId());
                    edge.addValue("relationship", relationship);
                    dataset.addEntity(edge);
                } else {
                    FtmEntity edge = dataset.newEntity("Family", "rel-" + rcaPerson.getId() + "-" + pepPerson.getId());
                    edge.addValue("person", pepPerson.getId());
                    edge.addValue("relative", rcaPerson.getId());
                    edge.addValue("relationship", relationship);
                    dataset.addEntity(edge);
                }
            }

            count++;
        }
        return count;
    }
}

