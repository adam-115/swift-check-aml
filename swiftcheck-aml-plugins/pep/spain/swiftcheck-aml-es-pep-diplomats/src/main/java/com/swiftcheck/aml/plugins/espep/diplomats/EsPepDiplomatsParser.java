package com.swiftcheck.aml.plugins.espep.diplomats;

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
 * <b>es_pep_diplomats</b> – Ambassadeurs, chefs de mission et représentants permanents de l'Espagne.
 *
 * <p>Couvre la catégorie <b>ES-e</b>, fonction <b>Ambassadeur ou chef de mission (Embajador)</b>
 * (Ley 10/2010, art. 14.2 ; JO C/2023/724).
 */
@Extension
public class EsPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepDiplomatsParser.class);

    static final String ID_PREFIX = "es-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "es";
    static final String SEED_RESOURCE = "/seed/spanish-ambassadors.json";
    static final String JSON_FILE = "spanish-ambassadors.json";

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
                String.format(Locale.ROOT, "%d ambassadeurs et représentants permanents de l'Espagne indexés.", count)
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
                        "https://www.exteriores.gob.es/"));
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
            log.info("Diplomates espagnols : données officielles chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode ambassadors = root.path("ambassadors");
        if (!ambassadors.isArray() || ambassadors.isEmpty()) {
            throw new IllegalStateException("Aucun ambassadeur trouvé dans le fichier : " + rawFile);
        }

        int count = 0;
        for (JsonNode a : ambassadors) {
            String name = a.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String country = a.path("country").asText("").trim();
            String city = a.path("city").asText("").trim();
            String role = a.path("role").asText("Embajador").trim();
            String status = a.path("status").asText("current").trim();
            String birthDate = a.path("birthDate").asText("").trim();
            String startDate = a.path("startDate").asText("").trim();
            String endDate = a.path("endDate").asText("").trim();
            String gender = a.path("gender").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role + " (" + city + ", " + country + ")");
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.exteriores.gob.es/");
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", "embajada-" + city.toLowerCase(Locale.ROOT), role);
            position.addValue("name", role + " – " + city);
            position.addValue("country", COUNTRY);
            position.addValue("organization", "Ministerio de Asuntos Exteriores, Unión Europea y Cooperación");
            position.addValue("topics", "gov.national;gov.igo");
            position.addValue("sourceUrl", "https://www.exteriores.gob.es/");
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
            occupancy.addValue("sourceUrl", "https://www.exteriores.gob.es/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

