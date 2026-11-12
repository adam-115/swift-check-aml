package com.swiftcheck.aml.plugins.grpep.supremecourts;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
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
 * <b>gr_pep_supreme_courts</b> – Présidents et magistrats des cours suprêmes helléniques :
 * Cour de cassation (Άρειος Πάγος) et Conseil d'État (Συμβούλιο της Επικρατείας).
 *
 * <p>Couvre la catégorie <b>GR-d</b>, fonction <b>Juge de la Cour de cassation ou du Conseil d'État</b> (fonction 662)
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 * Source dynamique : MediaWiki API (Wikipedia - pages officielles des deux cours).
 */
@Extension
public class GrPepSupremeCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepSupremeCourtsParser.class);

    static final String ID_PREFIX = "gr-pep-supreme-courts";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String JSON_FILE = "gr-supreme-courts-wikipedia.json";

    public static final String PARAM_WIKIPEDIA_API_URL = "wikipedia.api.url";
    public static final String PARAM_PAGE_TITLES = "wikipedia.page.titles";

    private static final String DEFAULT_API_URL = "https://en.wikipedia.org/w/api.php";
    private static final String DEFAULT_TITLES = "Supreme_Civil_and_Criminal_Court_of_Greece|Council_of_State_(Greece)";

    private static final Pattern BULLET_PATTERN = Pattern.compile("^\\*\\s*(.+)$");
    private static final Pattern SECTION_HEADER = Pattern.compile("^==\\s*(?:List of presidents|Presidents)\\s*==", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_HEADER = Pattern.compile("^==[^=]");
    private static final Pattern YEARS_PATTERN = Pattern.compile("\\((\\d{4})\\s*[–\\-\\—]\\s*(\\d{4}|current|present)?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE_YEAR_PATTERN = Pattern.compile("\\((\\d{4})\\)");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);

        downloadWikipediaData(ctx, rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d hauts magistrats des cours suprêmes helléniques indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            downloadWikipediaData(ctx, rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                String name = entity.getValues("name").stream().findFirst().orElse(entity.getId());
                records.add(new SourceRecord(entity.getId(), "Person", name, "https://areiospagos.gr/"));
            }
        }
        return records;
    }

    private void downloadWikipediaData(ParserContext ctx, Path destination) throws IOException, InterruptedException {
        String apiUrl = ctx.parameters().getOrDefault(PARAM_WIKIPEDIA_API_URL, DEFAULT_API_URL);
        String titles = ctx.parameters().getOrDefault(PARAM_PAGE_TITLES, DEFAULT_TITLES);

        String fullUrl = apiUrl + "?action=query&titles=" + URLEncoder.encode(titles, StandardCharsets.UTF_8)
                + "&prop=revisions&rvslots=main&rvprop=content&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("User-Agent", "SwiftCheckAML/1.0 (compliance@swiftcheck.com; automated PEP parser)")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("Erreur HTTP " + resp.statusCode() + " lors de la requête MediaWiki: " + fullUrl);
        }

        try (InputStream in = resp.body()) {
            Files.copy(in, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("MediaWiki : pages des cours suprêmes helléniques téléchargées vers {}", destination);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pages = root.path("query").path("pages");
        if (pages.isMissingNode() || !pages.isObject() || pages.isEmpty()) {
            throw new IllegalStateException("Aucune page retournée dans l'arbre JSON : " + rawFile);
        }

        Map<String, JudgeOfficial> judges = new LinkedHashMap<>();

        for (Iterator<JsonNode> it = pages.elements(); it.hasNext(); ) {
            JsonNode page = it.next();
            String title = page.path("title").asText("");
            JsonNode revs = page.path("revisions");
            if (!revs.isArray() || revs.isEmpty()) {
                continue;
            }
            String wikitext = revs.get(0).path("slots").path("main").path("*").asText("");
            if (wikitext.isEmpty()) {
                wikitext = revs.get(0).path("*").asText("");
            }

            parseCourtPage(title, wikitext, judges);
        }

        int count = 0;
        for (JudgeOfficial j : judges.values()) {
            FtmEntity person = dataset.newEntity("Person", j.name);
            person.addValue("name", j.name);
            if (j.greekAlias != null && !j.greekAlias.isBlank()) {
                person.addValue("alias", j.greekAlias);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", j.role);
            person.addValue("sourceUrl", j.sourceUrl);
            dataset.addEntity(person);

            String posSlug = j.role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", j.role);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", j.court);
            pos.addValue("sourceUrl", j.sourceUrl);
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            if (j.startDate != null && !j.startDate.isBlank()) {
                occupancy.addValue("startDate", j.startDate);
            }
            if (j.endDate != null && !j.endDate.isBlank()) {
                occupancy.addValue("endDate", j.endDate);
                occupancy.addValue("status", "ended");
            } else {
                occupancy.addValue("status", "current");
            }
            dataset.addEntity(occupancy);
            count++;
        }

        return count;
    }

    private void parseCourtPage(String title, String wikitext, Map<String, JudgeOfficial> judges) {
        boolean isAreios = title.contains("Supreme Civil") || title.contains("Cassation") || title.contains("Areios");
        String court = isAreios ? "Άρειος Πάγος (Cour de cassation)" : "Συμβούλιο της Επικρατείας (Conseil d'État)";
        String role = isAreios ? "Président de la Cour de cassation (Άρειος Πάγος)" : "Président du Conseil d'État (Συμβούλιο της Επικρατείας)";
        String sourceUrl = isAreios ? "https://areiospagos.gr/" : "https://www.adjustice.gr/";

        String[] lines = wikitext.split("\r?\n");
        boolean inPresidentsSection = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (SECTION_HEADER.matcher(trimmed).find()) {
                inPresidentsSection = true;
                continue;
            }
            if (inPresidentsSection && ANY_HEADER.matcher(trimmed).find()) {
                inPresidentsSection = false;
            }

            if (inPresidentsSection) {
                Matcher mb = BULLET_PATTERN.matcher(trimmed);
                if (mb.matches()) {
                    parsePresidentBullet(mb.group(1), role, court, sourceUrl, judges);
                }
            }
        }
    }

    private void parsePresidentBullet(String bulletText, String role, String court, String sourceUrl, Map<String, JudgeOfficial> judges) {
        String startYear = null;
        String endYear = null;

        Matcher my = YEARS_PATTERN.matcher(bulletText);
        if (my.find()) {
            startYear = my.group(1);
            String rawEnd = my.group(2);
            if (rawEnd != null && !rawEnd.equalsIgnoreCase("current") && !rawEnd.equalsIgnoreCase("present")) {
                endYear = rawEnd;
            }
        } else {
            Matcher msy = SINGLE_YEAR_PATTERN.matcher(bulletText);
            if (msy.find()) {
                startYear = msy.group(1);
                endYear = msy.group(1);
            }
        }

        // Only retain modern 3rd Hellenic Republic judges (1974 onwards) to avoid 19th-century history
        if (startYear != null) {
            try {
                int sy = Integer.parseInt(startYear);
                if (sy < 1974) {
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        String rawName = bulletText;
        if (rawName.contains("(")) {
            rawName = rawName.substring(0, rawName.indexOf('('));
        }

        String name = cleanWikitext(rawName);
        if (name.isEmpty() || name.length() < 3) {
            return;
        }

        JudgeOfficial judge = judges.computeIfAbsent(name, k -> new JudgeOfficial(k, role, court, sourceUrl));
        judge.role = role;
        judge.court = court;
        judge.sourceUrl = sourceUrl;
        if (startYear != null) judge.startDate = startYear;
        if (endYear != null) judge.endDate = endYear;
        judge.greekAlias = transliterateGreek(name);
    }

    private String cleanWikitext(String text) {
        String s = text.replaceAll("<ref[^>]*>.*?</ref>", "")
                .replaceAll("<ref[^>]*/>", "")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\{\\{[^}]+\\}\\}", "")
                .trim();

        Pattern linkPattern = Pattern.compile("\\[\\[([^|\\]]+)(?:\\|([^|\\]]+))?\\]\\]");
        Matcher m = linkPattern.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String label = m.group(2) != null ? m.group(2) : m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(label));
        }
        m.appendTail(sb);

        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private String transliterateGreek(String latinName) {
        return switch (latinName) {
            case "Ioanna Klapa-Christodoulea" -> "Ιωάννα Κλάπα-Χριστοδουλέα";
            case "Maria Georgiou" -> "Μαρία Γεωργίου";
            case "Anastasia Papadopoulou" -> "Αναστασία Παπαδοπούλου";
            case "Panagiotis Lymperopoulos" -> "Παναγιώτης Λυμπερόπουλος";
            case "Aggeliki Aliferopoulou" -> "Αγγελική Αλειφεροπούλου";
            case "Vassiliki Thanou-Christophilou" -> "Βασιλική Θάνου-Χριστοφίλου";
            case "Evangelia Nika" -> "Ευαγγελία Νίκα";
            case "Demetrius Skaltsounes" -> "Δημήτριος Σκαλτσούνης";
            case "Mary Sarp" -> "Μαίρη Σαρπ";
            case "Athanasios Rantos" -> "Αθανάσιος Ράντος";
            case "Katerina Sakellaropoulou" -> "Κατερίνα Σακελλαροπούλου";
            case "Nikolaos Sakellariou" -> "Νικόλαος Σακελλάριος";
            case "Panayiotis Pikrammenos" -> "Παναγιώτης Πικραμμένος";
            default -> null;
        };
    }

    private static class JudgeOfficial {
        final String name;
        String role;
        String court;
        String sourceUrl;
        String startDate;
        String endDate;
        String greekAlias;

        JudgeOfficial(String name, String role, String court, String sourceUrl) {
            this.name = name;
            this.role = role;
            this.court = court;
            this.sourceUrl = sourceUrl;
        }
    }
}
