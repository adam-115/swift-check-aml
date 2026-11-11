package com.swiftcheck.aml.plugins.itpep.diplomats;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <b>it_pep_diplomats</b> – Ambassadeurs, chefs de mission et consuls généraux de la République italienne.
 *
 * <p>Couvre la catégorie <b>IT-1.7</b>, fonction <b>Ambassadeur, chargé d'affaires ou chef de mission</b>
 * (D.Lgs. 231/2007, art. 1 c.2 lett. dd), point 1.7 ; JO C/2023/724).
 *
 * <p>Données extraites dynamiquement depuis l'API officielle Wikipedia MediaWiki répertoriant les ambassadeurs
 * et ministres plénipotentiaires en fonction représentant l'Italie dans le monde.
 */
@Extension
public class ItPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(ItPepDiplomatsParser.class);

    static final String ID_PREFIX = "it-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "it";
    static final String JSON_FILE = "italian-ambassadors-wikipedia.json";
    static final String WIKI_API_URL =
            "https://it.wikipedia.org/w/api.php?action=parse&page=Ambasciatori_italiani&format=json&prop=wikitext";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Italie - diplomates)";

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");
    private static final Pattern DATE_ITALIAN_PATTERN = Pattern.compile("(\\d{1,2})\\s+([a-záéíóú]+)\\s+(\\d{4})");

    private static final Map<String, String> ITALIAN_MONTHS = Map.ofEntries(
            Map.entry("gennaio", "01"),
            Map.entry("febbraio", "02"),
            Map.entry("marzo", "03"),
            Map.entry("aprile", "04"),
            Map.entry("maggio", "05"),
            Map.entry("giugno", "06"),
            Map.entry("luglio", "07"),
            Map.entry("agosto", "08"),
            Map.entry("settembre", "09"),
            Map.entry("ottobre", "10"),
            Map.entry("novembre", "11"),
            Map.entry("dicembre", "12")
    );

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
                String.format(Locale.ROOT, "%d ambassadeurs et chefs de mission italiens indexés.", count)
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
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://it.wikipedia.org/wiki/Ambasciatori_italiani"));
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
        log.info("Ambassadeurs d'Italie : données fraîches téléchargées depuis Wikipedia dans {}", rawFile);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        String wikitext = root.path("parse").path("wikitext").path("*").asText("");
        if (wikitext.isEmpty()) {
            throw new IllegalStateException("Aucun contenu wikitext trouvé dans : " + rawFile);
        }

        String[] rows = wikitext.split("\\|-");
        int count = 0;

        for (String row : rows) {
            String[] lines = row.split("\\r?\\n");
            List<String> cells = new ArrayList<>();
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("|")) {
                    cells.add(line);
                }
            }

            if (cells.size() < 4) {
                continue;
            }

            String nameLine = cells.get(0);
            String gradeLine = cells.get(1);
            String missionLine = cells.get(2);
            String countryLine = cells.get(3);
            String dateLine = cells.size() >= 5 ? cells.get(4) : "";

            String name = extractCleanText(nameLine);
            if (name.isEmpty() || name.equalsIgnoreCase("nome") || name.startsWith("align")) {
                continue;
            }

            String mission = extractCleanText(missionLine);
            String countryTarget = extractCleanText(countryLine);
            String startDate = extractIsoDate(dateLine);

            String role;
            if (!mission.isEmpty()) {
                role = "Ambasciatore d'Italia - " + mission;
            } else if (!countryTarget.isEmpty()) {
                role = "Ambasciatore d'Italia in " + countryTarget;
            } else {
                role = "Ambasciatore d'Italia";
            }

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role);
            person.addValue("sourceUrl", "https://it.wikipedia.org/wiki/Ambasciatori_italiani");
            dataset.addEntity(person);

            String posSlug = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", role);
            position.addValue("country", COUNTRY);
            position.addValue("organization", "Ministero degli Affari Esteri e della Cooperazione Internazionale");
            position.addValue("topics", "gov.national;diplomatic");
            position.addValue("sourceUrl", "https://it.wikipedia.org/wiki/Ambasciatori_italiani");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", person.getId(), position.getId(), startDate);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            if (!startDate.isEmpty()) {
                occupancy.addValue("startDate", startDate);
            }
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", "https://it.wikipedia.org/wiki/Ambasciatori_italiani");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private String extractCleanText(String line) {
        Matcher m = WIKI_LINK_PATTERN.matcher(line);
        if (m.find()) {
            return m.group(1).trim();
        }
        String clean = line.replaceFirst("^\\|\\s*", "").trim();
        clean = clean.replaceAll("<ref.*?>.*?</ref>", "").replaceAll("<ref.*", "").trim();
        clean = clean.replaceAll("\\{\\{[^\\}]+\\}\\}", "").trim();
        return clean;
    }

    private String extractIsoDate(String dateLine) {
        Matcher m = DATE_ITALIAN_PATTERN.matcher(dateLine.toLowerCase(Locale.ROOT));
        if (m.find()) {
            String day = m.group(1);
            if (day.length() == 1) {
                day = "0" + day;
            }
            String monthName = m.group(2);
            String month = ITALIAN_MONTHS.getOrDefault(monthName, "01");
            String year = m.group(3);
            return String.format(Locale.ROOT, "%s-%s-%s", year, month, day);
        }
        return "";
    }
}
