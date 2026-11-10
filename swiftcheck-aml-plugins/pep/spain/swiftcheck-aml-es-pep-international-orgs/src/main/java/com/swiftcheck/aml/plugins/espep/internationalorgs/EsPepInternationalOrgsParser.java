package com.swiftcheck.aml.plugins.espep.internationalorgs;

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
 * <b>es_pep_international_orgs</b> – Dirigeants des organisations internationales établies ou accréditées en Espagne.
 *
 * <p>Couvre la catégorie <b>ES-i</b>, fonction <b>Dirigeant d'une organisation internationale établie ou accréditée en Espagne</b>
 * (Ley 10/2010, art. 14.2 et 14.3 e) ; JO C/2023/724).
 *
 * <p>ONU Tourisme (UNWTO, Madrid), SEGIB (Madrid), OEI (Madrid), OISS (Madrid), UpM (Barcelone), OICV-IOSCO (Madrid), COI (Madrid).
 */
@Extension
public class EsPepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepInternationalOrgsParser.class);

    static final String ID_PREFIX = "es-pep-international-orgs";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "es";
    static final String SEED_RESOURCE = "/seed/international-orgs.json";
    static final String JSON_FILE = "international-orgs.json";

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
                String.format(Locale.ROOT, "%d dirigeants d'organisations internationales en Espagne indexés.", count)
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
                        "https://www.unwto.org/"));
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
            log.info("Organisations internationales en Espagne : données officielles chargées depuis {}", SEED_RESOURCE);
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
            String city = l.path("city").asText("").trim();
            String role = l.path("role").asText("").trim();
            String status = l.path("status").asText("current").trim();
            String birthDate = l.path("birthDate").asText("").trim();
            String startDate = l.path("startDate").asText("").trim();
            String endDate = l.path("endDate").asText("").trim();
            String gender = l.path("gender").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role + " – " + org + " (" + city + ")");
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.unwto.org/");
            dataset.addEntity(person);

            String posSlug = (org + "-" + role).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", role + " – " + org);
            position.addValue("country", COUNTRY);
            position.addValue("organization", org);
            position.addValue("topics", "gov.igo");
            position.addValue("sourceUrl", "https://www.unwto.org/");
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
            occupancy.addValue("sourceUrl", "https://www.unwto.org/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

