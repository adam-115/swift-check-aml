package com.swiftcheck.aml.plugins.ptpep.enterprises;

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
 * <b>pt_pep_state_enterprises</b> – Dirigeants des entreprises publiques stratégiques de l'État portugais (Setor Empresarial do Estado).
 *
 * <p>Couvre la catégorie <b>PT-f</b>, fonction <b>Dirigeant d'un institut public, d'une fondation publique ou d'une entreprise publique</b> (fonction 265)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 */
@Extension
public class PtPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepStateEnterprisesParser.class);

    static final String ID_PREFIX = "pt-pep-state-enterprises";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String SEED_RESOURCE = "/seed/pt-state-enterprises.json";
    static final String JSON_FILE = "pt-state-enterprises.json";

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
                String.format(Locale.ROOT, "%d dirigeants des entreprises publiques d'État du Portugal indexés.", count)
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
                        "https://www.dgtf.gov.pt/"));
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
            log.info("Entreprises publiques portugaises : données chargées depuis {}", SEED_RESOURCE);
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
            String company = e.path("company").asText("Empresa Pública");
            String position = e.path("position").asText("Dirigente de Empresa Pública");
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
            person.addValue("sourceUrl", "https://www.dgtf.gov.pt/");
            dataset.addEntity(person);

            FtmEntity compEntity = dataset.newEntity("Company", "see-" + company.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
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

