package com.swiftcheck.aml.plugins.itpep.healthdirectors;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <b>it_pep_health_directors</b> – Postes et directions générales des Entreprises Sanitaires Locales (ASL/AUSL/ATS/ASP).
 *
 * <p>Couvre la catégorie <b>IT-1.8</b>, fonction <b>Directeur général d'une entreprise sanitaire locale (ASL)</b>
 * (D.Lgs. 231/2007, art. 1 c.2 lett. dd), point 1.8 ; JO C/2023/724).
 *
 * <p>Données extraites dynamiquement depuis l'API officielle Wikipedia MediaWiki répertoriant les Aziende Sanitarie Locali
 * et structures du Servizio Sanitario Nazionale (SSN).
 */
@Extension
public class ItPepHealthDirectorsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(ItPepHealthDirectorsParser.class);

    static final String ID_PREFIX = "it-pep-health-directors";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "it";
    static final String JSON_FILE = "asl-entities-wikipedia.json";
    static final String WIKI_API_URL =
            "https://it.wikipedia.org/w/api.php?action=parse&page=Azienda_sanitaria_locale&format=json&prop=wikitext";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Italie - directeurs generaux de la sante)";

    private static final Pattern ASL_PATTERN = Pattern.compile("\\|\\s*(?:rowspan=[^|]+\\|\\s*)?(Azienda [^|\\r\\n\\(\\)\\']+)");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        download(rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d postes de directeurs généraux d'ASL/SSN italiens indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            download(rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Position".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Position",
                        entity.getValues("name").iterator().next(),
                        "https://it.wikipedia.org/wiki/Azienda_sanitaria_locale"));
            }
        }
        return records;
    }

    void download(Path rawFile) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(WIKI_API_URL))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Wikipedia API returned HTTP " + resp.statusCode() + " : " + resp.body());
        }
        Files.writeString(rawFile, resp.body(), StandardCharsets.UTF_8);
        log.info("Entités ASL italiennes : données fraîches téléchargées depuis Wikipedia dans {}", rawFile);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        String wikitext = root.path("parse").path("wikitext").path("*").asText("");
        if (wikitext.isEmpty()) {
            throw new IllegalStateException("Aucun contenu wikitext trouvé dans : " + rawFile);
        }

        String[] rows = wikitext.split("\\|-");
        Set<String> seenEntities = new HashSet<>();
        int count = 0;

        for (String row : rows) {
            String[] lines = row.split("\\r?\\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.startsWith("|")) {
                    continue;
                }
                Matcher m = ASL_PATTERN.matcher(line);
                if (m.find()) {
                    String entityName = m.group(1).trim();
                    if (entityName.equalsIgnoreCase("Azienda sanitaria locale") || !seenEntities.add(entityName)) {
                        continue;
                    }

                    String role = "Direttore Generale - " + entityName;
                    String posSlug = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                    FtmEntity position = dataset.newEntity("Position", posSlug);
                    position.addValue("name", role);
                    position.addValue("country", COUNTRY);
                    position.addValue("organization", entityName);
                    position.addValue("topics", TOPIC);
                    position.addValue("sourceUrl", "https://it.wikipedia.org/wiki/Azienda_sanitaria_locale");
                    dataset.addEntity(position);

                    count++;
                }
            }
        }

        return count;
    }
}
