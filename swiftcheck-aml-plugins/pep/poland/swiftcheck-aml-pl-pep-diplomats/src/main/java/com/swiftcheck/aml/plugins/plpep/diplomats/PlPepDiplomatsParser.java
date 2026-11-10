package com.swiftcheck.aml.plugins.plpep.diplomats;

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
import java.util.LinkedHashMap;
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
 * <b>pl_pep_diplomats</b> – Ambassadeurs, chargés d'affaires et consuls généraux de la République de Pologne.
 *
 * <p>Couvre la catégorie <b>PL-f</b>, fonction <b>Ambassadeur</b> (fonction 493)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Extraction réseau directe depuis le registre officiel des représentations diplomatiques polonaises :
 * {@code https://www.gov.pl/web/dyplomacja/polskie-przedstawicielstwa-na-swiecie}
 */
@Extension
public class PlPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepDiplomatsParser.class);

    static final String ID_PREFIX = "pl-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String SOURCE_URL = "https://www.gov.pl/web/dyplomacja/polskie-przedstawicielstwa-na-swiecie";
    static final String HTML_FILE = "pl-ambasady.html";

    private static final Pattern PRE_REGISTER_DATA_PATTERN = Pattern.compile(
            "<pre id=\"registerData\"[^>]*>([\\s\\S]*?)</pre>",
            Pattern.CASE_INSENSITIVE
    );

    private final ObjectMapper json = new ObjectMapper();

    public record DiplomatRecord(
            String country,
            String mission,
            String name,
            String role,
            String website
    ) {}

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawHtml = ctx.rawDir().resolve(HTML_FILE);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        download(client, rawHtml);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawHtml, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d ambassadeurs, chargés d'affaires et consuls généraux de Pologne indexés depuis gov.pl.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawHtml = ctx.rawDir().resolve(HTML_FILE);
        if (!Files.exists(rawHtml)) {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            download(client, rawHtml);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawHtml, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        SOURCE_URL));
            }
        }
        return records;
    }

    void download(HttpClient client, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(SOURCE_URL))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pl,en-US;q=0.7,en;q=0.3")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " lors du téléchargement de " + SOURCE_URL);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[pl_pep_diplomats] Registre des représentations diplomatiques téléchargé ({} caractères)", response.body().length());
    }

    public int convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<DiplomatRecord> diplomats = extractDiplomats(html);
        if (diplomats.isEmpty()) {
            throw new IllegalStateException("Aucun diplomate trouvé dans : " + htmlFile);
        }

        // Déduplication par nom propre du diplomate
        Map<String, DiplomatRecord> uniqueDiplomats = new LinkedHashMap<>();
        for (DiplomatRecord d : diplomats) {
            uniqueDiplomats.putIfAbsent(d.name(), d);
        }

        int count = 0;
        for (DiplomatRecord d : uniqueDiplomats.values()) {
            String name = d.name();
            String fullRole = d.role() + (!d.mission().isEmpty() ? " (" + d.mission() + ")" : "");
            String sourceUrl = !d.website().isEmpty() ? d.website() : SOURCE_URL;

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", fullRole);
            person.addValue("sourceUrl", sourceUrl);
            dataset.addEntity(person);

            String posSlug = (d.role() + "-" + d.country()).toLowerCase(Locale.ROOT)
                    .replaceAll("[ąćęłńóśźż]", "x")
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (posSlug.isEmpty()) {
                posSlug = "diplomate-polonais";
            }

            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", fullRole);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", d.mission().isEmpty() ? "Ministerstwo Spraw Zagranicznych" : d.mission());
            pos.addValue("sourceUrl", sourceUrl);
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", fullRole);
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }

    public List<DiplomatRecord> extractDiplomats(String html) throws IOException {
        Matcher preMatcher = PRE_REGISTER_DATA_PATTERN.matcher(html);
        if (!preMatcher.find()) {
            throw new IllegalStateException("Balise <pre id=\"registerData\"> introuvable dans la page");
        }

        JsonNode root = json.readTree(preMatcher.group(1));
        String csvData = root.path("data").asText("");
        if (csvData.isEmpty()) {
            throw new IllegalStateException("Aucune donnée CSV dans registerData");
        }

        List<DiplomatRecord> results = new ArrayList<>();
        String[] lines = csvData.split("\r?\n");

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }

            List<String> cols = parseCsvLine(line);
            if (cols.size() < 4) {
                continue;
            }

            String country = cols.get(0).trim();
            String mission = cols.get(1).trim();
            String leader = cols.get(2).trim();
            String role = cols.get(3).trim();
            String website = cols.size() > 10 ? cols.get(10).trim() : "";

            if (leader.length() < 3) {
                continue;
            }

            String rLower = role.toLowerCase(Locale.ROOT);
            // On exclut les consuls honoraires (non PEP de plein droit)
            if (rLower.contains("honorow")) {
                continue;
            }

            // On retient les fonctions diplomatiques officielles (ambassadeur, chargé d'affaires, représentant permanent, consul général)
            boolean isDiplomat = rLower.contains("ambasador")
                    || rLower.contains("chargé")
                    || rLower.contains("charge")
                    || rLower.contains("przedstawiciel")
                    || rLower.contains("konsul generalny");

            if (isDiplomat) {
                results.add(new DiplomatRecord(country, mission, leader, role, website));
            }
        }
        return results;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int c = 0; c < line.length(); c++) {
            char ch = line.charAt(c);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == ';' && !inQuotes) {
                cols.add(cleanCsvField(cur.toString()));
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cols.add(cleanCsvField(cur.toString()));
        return cols;
    }

    private static String cleanCsvField(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }
}
