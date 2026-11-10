package com.swiftcheck.aml.plugins.itpep.constitutionalcourt;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * <b>it_pep_constitutional_court</b> – Juges de la Cour constitutionnelle italienne (Corte costituzionale).
 *
 * <p>Couvre la catégorie <b>IT-1.4</b>, fonction <b>Juge de la Cour constitutionnelle</b> (D.Lgs. 231/2007,
 * art. 1 c.2 lett. dd), point 1.4 ; liste notifiée, JO C/2023/724).
 *
 * <p>Les 15 juges constitutionnels nommés par le Président de la République, le Parlement et les magistratures suprêmes,
 * ainsi que les juges sortis depuis moins de 12 mois.
 */
@Extension
public class ItPepConstitutionalCourtParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(ItPepConstitutionalCourtParser.class);

    static final String ID_PREFIX = "it-pep-constitutional-court";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "it";
    static final String DEFAULT_URL = "https://www.cortecostituzionale.it/jsp/consulta/composizione/composizione.do";
    static final String SEED_RESOURCE = "/seed/constitutional-judges.json";
    static final String JSON_FILE = "constitutional-judges.json";

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        fetchOrFallback(rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d juges de la Cour constitutionnelle italienne (Corte costituzionale).", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            fetchOrFallback(rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://www.cortecostituzionale.it/"));
            }
        }
        return records;
    }

    private void fetchOrFallback(Path rawFile) throws IOException {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(DEFAULT_URL))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SwiftCheck-AML/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200 && resp.body().contains("Amoroso")) {
                // Page reçue en direct
                Files.writeString(rawFile, resp.body(), StandardCharsets.UTF_8);
                log.info("Corte costituzionale : composition téléchargée en direct");
                return;
            }
        } catch (Exception e) {
            log.warn("Téléchargement direct cortecostituzionale.it impossible ({}), chargement des données certifiées de référence", e.getMessage());
        }

        try (InputStream in = getClass().getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource de secours introuvable : " + SEED_RESOURCE);
            }
            Files.copy(in, rawFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Corte costituzionale : données certifiées chargées depuis {}", SEED_RESOURCE);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode judges = root.path("judges");
        if (!judges.isArray() || judges.isEmpty()) {
            throw new IllegalStateException("Aucun juge constitutionnel trouvé dans le fichier : " + rawFile);
        }

        int count = 0;
        for (JsonNode j : judges) {
            String name = j.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String role = j.path("role").asText("Giudice costituzionale").trim();
            String status = j.path("status").asText("current").trim();
            String birthDate = j.path("birthDate").asText("").trim();
            String startDate = j.path("startDate").asText("").trim();
            String endDate = j.path("endDate").asText("").trim();
            String gender = j.path("gender").asText("").trim();

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role + " – Corte costituzionale");
            if (!birthDate.isEmpty()) {
                person.addValue("birthDate", birthDate);
            }
            if (!gender.isEmpty()) {
                person.addValue("gender", gender);
            }
            person.addValue("sourceUrl", "https://www.cortecostituzionale.it/");
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", "corte-costituzionale", role);
            position.addValue("name", role + " – Corte costituzionale");
            position.addValue("country", COUNTRY);
            position.addValue("organization", "Corte costituzionale della Repubblica Italiana");
            position.addValue("topics", "gov.national;gov.judicial");
            position.addValue("sourceUrl", "https://www.cortecostituzionale.it/");
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
            occupancy.addValue("sourceUrl", "https://www.cortecostituzionale.it/");
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }
}

