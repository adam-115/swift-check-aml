package com.swiftcheck.aml.plugins.grpep.government;

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
 * <b>gr_pep_government</b> – Premier ministre, ministres et vice-ministres de la République hellénique.
 *
 * <p>Couvre la catégorie <b>GR-a</b>, fonction <b>Premier ministre, ministres et vice-ministres</b> (fonction 658)
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Second cabinet of Kyriakos Mitsotakis).
 */
@Extension
public class GrPepGovernmentParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepGovernmentParser.class);

    static final String ID_PREFIX = "gr-pep-government";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String JSON_FILE = "gr-government-wikipedia.json";

    private static final String DEFAULT_PAGE = "Second_cabinet_of_Kyriakos_Mitsotakis";
    private static final String DEFAULT_SECTION = "1";

    private static final Pattern ROW_PATTERN = Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL_PATTERN = Pattern.compile("(?s)<td[^>]*>(.*?)</td>");
    private static final Pattern LINK_PATTERN = Pattern.compile("<a\\s+[^>]*href=\"([^\"]*)\"[^>]*>([^<]+)</a>");
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public GrPepGovernmentParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GrPepGovernmentParser(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        fetchLive(rawFile, ctx);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d membres du gouvernement hellénique indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            fetchLive(rawFile, ctx);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://primeminister.gr/"));
            }
        }
        return records;
    }

    private void fetchLive(Path rawFile, ParserContext ctx) throws IOException, InterruptedException {
        String endpoint = ctx.parameters().getOrDefault("endpoint", "https://en.wikipedia.org/w/api.php");
        String page = ctx.parameters().getOrDefault("page", DEFAULT_PAGE);
        String section = ctx.parameters().getOrDefault("section", DEFAULT_SECTION);

        URI uri = URI.create(endpoint + "?action=parse&page=" + page + "&section=" + section + "&prop=text&format=json");

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SwiftCheckAmlBot/1.0 (gr_pep_government; contact@swiftcheck.com)")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IOException("Erreur HTTP " + res.statusCode() + " lors de la requête MediaWiki: "
                    + new String(res.body(), StandardCharsets.UTF_8));
        }

        Files.write(rawFile, res.body());
        log.info("Données MediaWiki gouvernement grec téléchargées : {} octets", res.body().length);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        String html = root.path("parse").path("text").path("*").asText("");
        if (html.isEmpty()) {
            throw new IllegalStateException("Aucun contenu HTML dans : " + rawFile);
        }

        Map<String, MinisterData> officials = new LinkedHashMap<>();
        String currentMinistry = "Κυβέρνηση της Ελληνικής Δημοκρατίας (Gouvernement de la République hellénique)";
        String currentRole = "Minister";

        Matcher rowMatcher = ROW_PATTERN.matcher(html);
        while (rowMatcher.find()) {
            String rowHtml = rowMatcher.group(1);
            Matcher cellMatcher = CELL_PATTERN.matcher(rowHtml);
            List<String> rawCells = new ArrayList<>();
            while (cellMatcher.find()) {
                rawCells.add(cellMatcher.group(1));
            }
            if (rawCells.isEmpty()) {
                continue;
            }

            // PM or Deputy PM row with photo
            if (rawCells.size() >= 5 && rawCells.get(0).contains("mw:File")) {
                String role = stripTags(rawCells.get(1));
                String name = extractLinkTextOrStrip(rawCells.get(2));
                String dateStr = stripTags(rawCells.get(rawCells.size() - 1));
                if (!name.isEmpty() && !role.isEmpty()) {
                    addOfficial(officials, name, role, "Κυβέρνηση της Ελληνικής Δημοκρατίας", dateStr);
                }
                continue;
            }

            // Row with Ministry heading
            if (rawCells.size() >= 4 && rawCells.get(0).contains("Ministry of")) {
                currentMinistry = stripTags(rawCells.get(0)).replaceAll("^\\d+\\.\\s*", "");
                currentRole = stripTags(rawCells.get(1));
                String name = extractLinkTextOrStrip(rawCells.get(2));
                String dateStr = stripTags(rawCells.get(rawCells.size() - 1));
                if (!name.isEmpty() && !currentRole.isEmpty()) {
                    addOfficial(officials, name, currentRole, currentMinistry, dateStr);
                }
                continue;
            }

            // Row with Role heading
            if (rawCells.size() >= 3 && (rawCells.get(0).contains("Minister") || rawCells.get(0).contains("Deputy"))) {
                currentRole = stripTags(rawCells.get(0));
                String name = extractLinkTextOrStrip(rawCells.get(1));
                String dateStr = stripTags(rawCells.get(rawCells.size() - 1));
                if (!name.isEmpty() && !currentRole.isEmpty()) {
                    addOfficial(officials, name, currentRole, currentMinistry, dateStr);
                }
                continue;
            }

            // Continuation row with Person in first cell
            if (rawCells.size() >= 2 && rawCells.get(0).contains("<a href=\"/wiki/")) {
                String name = extractLinkTextOrStrip(rawCells.get(0));
                String dateStr = stripTags(rawCells.get(rawCells.size() - 1));
                if (!name.isEmpty() && !currentRole.isEmpty() && !name.contains("Democracy") && !name.contains("Party")) {
                    addOfficial(officials, name, currentRole, currentMinistry, dateStr);
                }
            }
        }

        if (officials.isEmpty()) {
            throw new IllegalStateException("Aucun ministre extrait du document : " + rawFile);
        }

        int count = 0;
        for (MinisterData m : officials.values()) {
            FtmEntity person = dataset.newEntity("Person", m.name);
            person.addValue("name", m.name);
            if (!m.greekAlias.isEmpty() && !m.greekAlias.equalsIgnoreCase(m.name)) {
                person.addValue("alias", m.greekAlias);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", m.roles.get(0).role);
            person.addValue("sourceUrl", "https://primeminister.gr/");
            dataset.addEntity(person);

            for (RoleEntry re : m.roles) {
                String posSlug = (re.role + "-" + re.ministry).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                FtmEntity pos = dataset.newEntity("Position", posSlug);
                pos.addValue("name", re.role);
                pos.addValue("country", COUNTRY);
                pos.addValue("organization", re.ministry);
                pos.addValue("sourceUrl", "https://primeminister.gr/");
                dataset.addEntity(pos);

                String occId = "occ-" + person.getId() + "-" + Math.abs((re.role + re.ministry).hashCode());
                FtmEntity occupancy = dataset.newEntity("Occupancy", occId);
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", pos.getId());
                if (!re.startDate.isEmpty()) {
                    occupancy.addValue("startDate", re.startDate);
                }
                if (!re.endDate.isEmpty()) {
                    occupancy.addValue("endDate", re.endDate);
                    occupancy.addValue("status", "ended");
                } else {
                    occupancy.addValue("status", "current");
                }
                dataset.addEntity(occupancy);
            }
            count++;
        }

        return count;
    }

    private void addOfficial(Map<String, MinisterData> officials, String rawName, String role, String ministry, String dateStr) {
        String cleanName = rawName.replaceAll("\\[.*?\\]", "").replaceAll("\\(.*?\\)", "").trim();
        if (cleanName.isEmpty() || cleanName.length() < 3) {
            return;
        }

        DateRange dr = parseDateRange(dateStr);
        MinisterData md = officials.computeIfAbsent(cleanName, k -> {
            MinisterData d = new MinisterData(cleanName);
            d.greekAlias = transliterateGreek(cleanName);
            return d;
        });

        md.roles.add(new RoleEntry(role, ministry, dr.startDate, dr.endDate));
    }

    private String extractLinkTextOrStrip(String html) {
        Matcher m = LINK_PATTERN.matcher(html);
        if (m.find()) {
            return m.group(2).trim();
        }
        return stripTags(html);
    }

    private String stripTags(String html) {
        return TAG_PATTERN.matcher(html).replaceAll(" ")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replaceAll("\\[.*?\\]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private DateRange parseDateRange(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return new DateRange("", "");
        }
        String clean = dateStr.replaceAll("\\[.*?\\]", "").trim();
        String[] parts = clean.split("[–—]");
        String start = parseDate(parts[0]);
        String end = parts.length > 1 && !parts[1].toLowerCase(Locale.ROOT).contains("present")
                ? parseDate(parts[1])
                : "";
        return new DateRange(start, end);
    }

    private String parseDate(String raw) {
        if (raw == null) {
            return "";
        }
        Pattern p = Pattern.compile("(\\d{1,2})?\\s*([A-Za-z]+)?\\s*(\\d{4})");
        Matcher m = p.matcher(raw.trim());
        if (m.find()) {
            String day = m.group(1);
            String monthName = m.group(2);
            String year = m.group(3);
            if (monthName != null) {
                int month = parseMonth(monthName);
                if (month > 0) {
                    if (day != null) {
                        return String.format(Locale.ROOT, "%s-%02d-%02d", year, month, Integer.parseInt(day));
                    }
                    return String.format(Locale.ROOT, "%s-%02d", year, month);
                }
            }
            return year;
        }
        return "";
    }

    private int parseMonth(String month) {
        return switch (month.toLowerCase(Locale.ROOT).substring(0, Math.min(3, month.length()))) {
            case "jan" -> 1;
            case "feb" -> 2;
            case "mar" -> 3;
            case "apr" -> 4;
            case "may" -> 5;
            case "jun" -> 6;
            case "jul" -> 7;
            case "aug" -> 8;
            case "sep" -> 9;
            case "oct" -> 10;
            case "nov" -> 11;
            case "dec" -> 12;
            default -> 0;
        };
    }

    private String transliterateGreek(String latin) {
        // Basic transliteration for matching
        StringBuilder sb = new StringBuilder();
        for (char c : latin.toCharArray()) {
            switch (Character.toLowerCase(c)) {
                case 'a' -> sb.append('α');
                case 'b' -> sb.append('β');
                case 'g' -> sb.append('γ');
                case 'd' -> sb.append('δ');
                case 'e' -> sb.append('ε');
                case 'z' -> sb.append('ζ');
                case 'h' -> sb.append('η');
                case 't' -> sb.append('τ');
                case 'i' -> sb.append('ι');
                case 'k' -> sb.append('κ');
                case 'l' -> sb.append('λ');
                case 'm' -> sb.append('μ');
                case 'n' -> sb.append('ν');
                case 'x' -> sb.append('ξ');
                case 'o' -> sb.append('ο');
                case 'p' -> sb.append('π');
                case 'r' -> sb.append('ρ');
                case 's' -> sb.append('σ');
                case 'u', 'y' -> sb.append('υ');
                case 'f' -> sb.append('φ');
                case ' ' -> sb.append(' ');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static class MinisterData {
        final String name;
        String greekAlias = "";
        final List<RoleEntry> roles = new ArrayList<>();

        MinisterData(String name) {
            this.name = name;
        }
    }

    private static class RoleEntry {
        final String role;
        final String ministry;
        final String startDate;
        final String endDate;

        RoleEntry(String role, String ministry, String startDate, String endDate) {
            this.role = role;
            this.ministry = ministry;
            this.startDate = startDate;
            this.endDate = endDate;
        }
    }

    private record DateRange(String startDate, String endDate) {
    }
}
