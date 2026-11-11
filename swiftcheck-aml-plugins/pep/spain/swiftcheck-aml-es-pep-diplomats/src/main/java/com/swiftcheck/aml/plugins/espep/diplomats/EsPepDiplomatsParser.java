package com.swiftcheck.aml.plugins.espep.diplomats;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
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
 * <b>es_pep_diplomats</b> – Ambassadeurs, chefs de mission et représentants permanents de l'Espagne.
 *
 * <p>Couvre la catégorie <b>ES-e</b>, fonction <b>Ambassadeur ou chef de mission (Embajador)</b>
 * (Ley 10/2010, art. 14.2 ; JO C/2023/724).
 *
 * <p>Données extraites dynamiquement depuis l'API officielle Wikipedia/Wikidata MediaWiki (CC-BY-SA)
 * répertoriant les ambassadeurs espagnols en fonction avec décrets BOE associés.
 */
@Extension
public class EsPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepDiplomatsParser.class);

    static final String ID_PREFIX = "es-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "es";
    static final String JSON_FILE = "spanish-ambassadors-wikipedia.json";
    static final String WIKI_API_URL =
            "https://es.wikipedia.org/w/api.php?action=parse&page=Anexo:Embajadores_de_Espa%C3%B1a&format=json&prop=wikitext";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Espagne - diplomates)";

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");
    private static final Pattern BOE_URL_PATTERN = Pattern.compile("url=(https://www\\.boe\\.es/[^\\s|>]+)");
    private static final Pattern DATE_SPANISH_PATTERN = Pattern.compile("(\\d{1,2})\\s+de\\s+([a-záéíóúñ]+)\\s+de\\s+(\\d{4})");

    private static final Map<String, String> SPANISH_MONTHS = Map.ofEntries(
            Map.entry("enero", "01"),
            Map.entry("febrero", "02"),
            Map.entry("marzo", "03"),
            Map.entry("abril", "04"),
            Map.entry("mayo", "05"),
            Map.entry("junio", "06"),
            Map.entry("julio", "07"),
            Map.entry("agosto", "08"),
            Map.entry("septiembre", "09"),
            Map.entry("octubre", "10"),
            Map.entry("noviembre", "11"),
            Map.entry("diciembre", "12")
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
                String.format(Locale.ROOT, "%d ambassadeurs et représentants permanents de l'Espagne indexés.", count)
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
                        "https://es.wikipedia.org/wiki/Anexo:Embajadores_de_Espa%C3%B1a"));
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
        log.info("Ambassadeurs d'Espagne : données fraîches téléchargées depuis Wikipedia dans {}", rawFile);
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

            String countryLine = cells.get(0);
            String ambLine = cells.get(3);
            String dateLine = cells.size() >= 5 ? cells.get(4) : "";

            String name = extractAmbassadorName(ambLine);
            if (name == null || name.isEmpty() || isInvalidName(name)) {
                continue;
            }

            String countryTarget = extractTargetCountry(countryLine);
            String startDate = extractIsoDate(dateLine);
            String boeUrl = extractBoeUrl(dateLine);
            String sourceUrl = !boeUrl.isEmpty() ? boeUrl : "https://es.wikipedia.org/wiki/Anexo:Embajadores_de_Espa%C3%B1a";

            String role = countryTarget.isEmpty()
                    ? "Embajador de España"
                    : String.format(Locale.ROOT, "Embajador de España en %s", countryTarget);

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("position", role);
            person.addValue("sourceUrl", sourceUrl);
            dataset.addEntity(person);

            String posSlug = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity position = dataset.newEntity("Position", posSlug);
            position.addValue("name", role);
            position.addValue("country", COUNTRY);
            position.addValue("organization", "Ministerio de Asuntos Exteriores, Unión Europea y Cooperación");
            position.addValue("topics", "gov.national;diplomatic");
            position.addValue("sourceUrl", sourceUrl);
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", person.getId(), position.getId(), startDate);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            if (!startDate.isEmpty()) {
                occupancy.addValue("startDate", startDate);
            }
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", sourceUrl);
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private String extractAmbassadorName(String ambLine) {
        Matcher m = WIKI_LINK_PATTERN.matcher(ambLine);
        if (m.find()) {
            return m.group(1).trim();
        }
        String clean = ambLine.replaceFirst("^\\|\\s*", "").trim();
        clean = clean.replaceAll("<ref.*?>.*?</ref>", "").replaceAll("<ref.*", "").trim();
        return clean;
    }

    private String extractTargetCountry(String countryLine) {
        Matcher m = WIKI_LINK_PATTERN.matcher(countryLine);
        String last = "";
        while (m.find()) {
            String val = m.group(1).trim();
            if (!val.toLowerCase(Locale.ROOT).endsWith(".svg") && !val.contains("px")) {
                last = val;
            }
        }
        return last;
    }

    private String extractIsoDate(String dateLine) {
        Matcher m = DATE_SPANISH_PATTERN.matcher(dateLine.toLowerCase(Locale.ROOT));
        if (m.find()) {
            String day = m.group(1);
            if (day.length() == 1) {
                day = "0" + day;
            }
            String monthName = m.group(2);
            String month = SPANISH_MONTHS.getOrDefault(monthName, "01");
            String year = m.group(3);
            return String.format(Locale.ROOT, "%s-%s-%s", year, month, day);
        }
        return "";
    }

    private String extractBoeUrl(String dateLine) {
        Matcher m = BOE_URL_PATTERN.matcher(dateLine);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private boolean isInvalidName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("vacante") || lower.equals("-") || lower.startsWith("desconocido")
                || lower.contains("px") || lower.endsWith(".svg") || lower.startsWith("anexo:");
    }
}
