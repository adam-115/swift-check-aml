package com.swiftcheck.aml.plugins.depep.defence;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
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

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_defence</b> - Ministre fédéral de la Défense (Bundesminister der Verteidigung),
 * Chef d'état-major des armées (Generalinspekteur der Bundeswehr) et historique des ministres.
 *
 * <p>Sources officielles sur bmvg.de :
 * <ul>
 *   <li>19-001 / 19-002 : Ministre Boris Pistorius (A.1) et Lebenslauf (vita)</li>
 *   <li>19-003 : Generalinspekteur der Bundeswehr General Carsten Breuer (F.1 - officier supérieur)</li>
 *   <li>19-004 : Historique des ministres de la Défense depuis 1955 (A.2 - statut ended)</li>
 * </ul>
 *
 * <p>Fonctions en allemand non traduites selon la nomenclature GwG (§ 1 Abs. 12 a & f).
 */
@Extension
public class DePepDefenceParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepDefenceParser.class);

    public static final String ID_PREFIX = "de-pep-defence";
    public static final String TOPIC = "role.pep";

    public static final String DEFAULT_MINISTER_URL = "https://www.bmvg.de/de/ministerium/verteidigungsminister-boris-pistorius";
    public static final String DEFAULT_VITA_URL = "https://www.bmvg.de/de/ministerium/verteidigungsminister-boris-pistorius/vita";
    public static final String DEFAULT_GENERAL_URL = "https://www.bmvg.de/de/ministerium/der-generalinspekteur-der-bundeswehr";
    public static final String DEFAULT_EHEMALIGE_URL = "https://www.bmvg.de/de/ministerium/geschichte-des-verteidigungsministeriums/ehemalige-verteidigungsminister";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - BMVg Defence)";

    private static final Map<String, String> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
            Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
            Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
            Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
            Map.entry("dezember", "12")
    );

    // Regex patterns
    private static final Pattern OG_TITLE = Pattern.compile("(?si)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']");
    private static final Pattern OG_IMAGE = Pattern.compile("(?si)<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']");
    private static final Pattern OG_DESC = Pattern.compile("(?si)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']");
    private static final Pattern VITA_LINK = Pattern.compile("(?si)<a[^>]+href=[\"']([^\"']*(?:/vita)[^\"']*)[\"']");
    private static final Pattern TABLE_BLOCK = Pattern.compile("(?si)<table[^>]*>(.*?)</table>");
    private static final Pattern ROW_BLOCK = Pattern.compile("(?si)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL_BLOCK = Pattern.compile("(?si)<t[dh][^>]*>(.*?)</t[dh]>");

    public record MinisterData(
            String name,
            String firstName,
            String lastName,
            String birthDate,
            String birthPlace,
            String positionName,
            String startDate,
            String endDate,
            String status,
            String party,
            String sourceUrl,
            String photoUrl,
            String notes,
            String gwgClass
    ) {}

    public record ScrapeResult(
            MinisterData currentMinister,
            MinisterData generalinspekteur,
            List<MinisterData> formerMinisters,
            List<String> brokenSources,
            List<String> warnings
    ) {}

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> params = ctx.parameters() == null ? Map.of() : ctx.parameters();
        ScrapeResult result = scrape(ctx, params);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);

        // Position A.1 : Bundesminister der Verteidigung
        FtmEntity ministerPosition = dataset.newEntity("Position", "bundesminister-der-verteidigung");
        ministerPosition.addValue("name", "Bundesminister der Verteidigung");
        ministerPosition.addValue("country", "de");
        ministerPosition.addValue("organization", "Bundesministerium der Verteidigung");
        ministerPosition.addValue("topics", "gov.national");
        ministerPosition.addValue("topics", "gov.executive");
        dataset.addEntity(ministerPosition);

        // Position F.1 : Generalinspekteur der Bundeswehr
        FtmEntity generalPosition = dataset.newEntity("Position", "generalinspekteur-der-bundeswehr");
        generalPosition.addValue("name", "Generalinspekteur der Bundeswehr");
        generalPosition.addValue("country", "de");
        generalPosition.addValue("organization", "Bundeswehr");
        generalPosition.addValue("topics", "military");
        generalPosition.addValue("topics", "gov.national");
        dataset.addEntity(generalPosition);

        // 1. Current Minister (Class A.1)
        if (result.currentMinister != null) {
            writeMinister(result.currentMinister, ministerPosition.getId(), dataset);
        }

        // 2. Generalinspekteur (Class F.1)
        if (result.generalinspekteur != null) {
            writeGeneral(result.generalinspekteur, generalPosition.getId(), dataset);
        }

        // 3. Former Ministers (Class A.2)
        for (MinisterData former : result.formerMinisters) {
            writeMinister(former, ministerPosition.getId(), dataset);
        }

        dataset.writeNdjsonFile(ctx.outputFile());

        int totalCount = (result.currentMinister != null ? 1 : 0)
                + (result.generalinspekteur != null ? 1 : 0)
                + result.formerMinisters.size();

        StringBuilder msg = new StringBuilder();
        msg.append(totalCount).append(" profils de la Défense (BMVg / Bundeswehr) extraits.");
        if (!result.brokenSources.isEmpty()) {
            msg.append(" [ALERT SOURCE_BROKEN: ").append(String.join(", ", result.brokenSources)).append("]");
        }
        if (!result.warnings.isEmpty()) {
            msg.append(" [ALERT STRUCTURE_CHANGED: ").append(String.join(", ", result.warnings)).append("]");
        }

        return dataset.toConversionResult().withMessage(msg.toString());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> params = ctx.parameters() == null ? Map.of() : ctx.parameters();
        ScrapeResult result = scrape(ctx, params);

        List<SourceRecord> records = new ArrayList<>();
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);

        if (result.currentMinister != null) {
            records.add(new SourceRecord(
                    ids.buildEntityId("person", slugify(result.currentMinister.name())),
                    "Person",
                    result.currentMinister.name(),
                    result.currentMinister.sourceUrl()
            ));
        }

        if (result.generalinspekteur != null) {
            records.add(new SourceRecord(
                    ids.buildEntityId("person", slugify(result.generalinspekteur.name())),
                    "Person",
                    result.generalinspekteur.name(),
                    result.generalinspekteur.sourceUrl()
            ));
        }

        for (MinisterData f : result.formerMinisters) {
            records.add(new SourceRecord(
                    ids.buildEntityId("person", slugify(f.name())),
                    "Person",
                    f.name(),
                    f.sourceUrl()
            ));
        }

        return records;
    }

    public ScrapeResult scrape(ParserContext ctx, Map<String, String> params) {
        List<String> broken = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (ctx.rawDir() != null) {
            try {
                Files.createDirectories(ctx.rawDir());
            } catch (IOException e) {
                log.warn("Impossible de créer rawDir {}: {}", ctx.rawDir(), e.getMessage());
            }
        }

        // Test mode / Local input files detection
        Path input = ctx.inputFile();
        Path testDir = null;
        if (input != null) {
            if (Files.isDirectory(input)) {
                testDir = input;
            } else if (Files.isRegularFile(input)) {
                testDir = input.getParent();
            }
        }

        String vitaHtml = null;
        String generalHtml = null;
        String ehemHtml = null;

        if (testDir != null && Files.exists(testDir.resolve("vita.html"))) {
            // Offline / resource test execution
            try {
                vitaHtml = Files.readString(testDir.resolve("vita.html"), StandardCharsets.UTF_8);
                if (ctx.rawDir() != null) {
                    Files.writeString(ctx.rawDir().resolve("vita.html"), vitaHtml, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                broken.add("vita.html: " + e.getMessage());
            }
            try {
                generalHtml = Files.readString(testDir.resolve("generalinspekteur.html"), StandardCharsets.UTF_8);
                if (ctx.rawDir() != null) {
                    Files.writeString(ctx.rawDir().resolve("generalinspekteur.html"), generalHtml, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                broken.add("generalinspekteur.html: " + e.getMessage());
            }
            try {
                ehemHtml = Files.readString(testDir.resolve("ehemalige.html"), StandardCharsets.UTF_8);
                if (ctx.rawDir() != null) {
                    Files.writeString(ctx.rawDir().resolve("ehemalige.html"), ehemHtml, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                broken.add("ehemalige.html: " + e.getMessage());
            }
        } else {
            // Live network fetch
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            String ministerUrl = params.getOrDefault("ministerUrl", DEFAULT_MINISTER_URL);
            String vitaUrl = params.getOrDefault("vitaUrl", DEFAULT_VITA_URL);
            String generalUrl = params.getOrDefault("generalUrl", DEFAULT_GENERAL_URL);
            String ehemaligeUrl = params.getOrDefault("ehemaligeUrl", DEFAULT_EHEMALIGE_URL);

            // Auto-heal vita URL if minister page discovers new minister slug
            try {
                String minHtml = fetchSafe(client, ministerUrl);
                if (minHtml != null) {
                    if (ctx.rawDir() != null) {
                        Files.writeString(ctx.rawDir().resolve("minister.html"), minHtml, StandardCharsets.UTF_8);
                    }
                    Matcher m = VITA_LINK.matcher(minHtml);
                    if (m.find()) {
                        String discoveredVita = m.group(1);
                        if (!discoveredVita.startsWith("http")) {
                            discoveredVita = "https://www.bmvg.de" + discoveredVita;
                        }
                        vitaUrl = discoveredVita;
                    }
                }
            } catch (Exception e) {
                log.warn("Auto-healing minister link failed: {}", e.getMessage());
            }

            try {
                vitaHtml = fetchSafe(client, vitaUrl);
                if (vitaHtml == null) {
                    broken.add("SOURCE_BROKEN: " + vitaUrl);
                } else if (ctx.rawDir() != null) {
                    Files.writeString(ctx.rawDir().resolve("vita.html"), vitaHtml, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                broken.add("SOURCE_BROKEN: " + vitaUrl + " (" + e.getMessage() + ")");
            }

            try {
                generalHtml = fetchSafe(client, generalUrl);
                if (generalHtml == null) {
                    broken.add("SOURCE_BROKEN: " + generalUrl);
                } else if (ctx.rawDir() != null) {
                    Files.writeString(ctx.rawDir().resolve("generalinspekteur.html"), generalHtml, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                broken.add("SOURCE_BROKEN: " + generalUrl + " (" + e.getMessage() + ")");
            }

            try {
                ehemHtml = fetchSafe(client, ehemaligeUrl);
                if (ehemHtml == null) {
                    broken.add("SOURCE_BROKEN: " + ehemaligeUrl);
                } else if (ctx.rawDir() != null) {
                    Files.writeString(ctx.rawDir().resolve("ehemalige.html"), ehemHtml, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                broken.add("SOURCE_BROKEN: " + ehemaligeUrl + " (" + e.getMessage() + ")");
            }
        }

        MinisterData currentMinister = null;
        if (vitaHtml != null) {
            currentMinister = extractCurrentMinister(vitaHtml, params.getOrDefault("vitaUrl", DEFAULT_VITA_URL), warnings);
        }

        MinisterData generalinspekteur = null;
        if (generalHtml != null) {
            generalinspekteur = extractGeneralinspekteur(generalHtml, params.getOrDefault("generalUrl", DEFAULT_GENERAL_URL), warnings);
        }

        List<MinisterData> formerMinisters = new ArrayList<>();
        if (ehemHtml != null) {
            formerMinisters = extractFormerMinisters(ehemHtml, params.getOrDefault("ehemaligeUrl", DEFAULT_EHEMALIGE_URL), warnings);
        }

        return new ScrapeResult(currentMinister, generalinspekteur, formerMinisters, broken, warnings);
    }

    private static String fetchSafe(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            log.warn("HTTP {} on {}", resp.statusCode(), url);
            return null;
        }
        return resp.body();
    }

    public static MinisterData extractCurrentMinister(String html, String pageUrl, List<String> warnings) {
        String name = null;
        Matcher mTitle = OG_TITLE.matcher(html);
        if (mTitle.find()) {
            name = cleanText(mTitle.group(1));
        }

        if (name == null || name.isBlank() || name.equalsIgnoreCase("Werdegang des Verteidigungsministers")) {
            Matcher mH1 = Pattern.compile("(?si)<h1[^>]*>(.*?)</h1>").matcher(html);
            if (mH1.find()) {
                name = cleanText(mH1.group(1));
            }
        }

        if (name == null || name.isBlank()) {
            name = "Boris Pistorius";
            warnings.add("STRUCTURE_CHANGED: Nom du ministre non détecté dans og:title / h1");
        }

        String photoUrl = null;
        Matcher mImg = OG_IMAGE.matcher(html);
        if (mImg.find()) {
            photoUrl = mImg.group(1);
        }

        String startDate = null;
        Matcher mDesc = OG_DESC.matcher(html);
        if (mDesc.find()) {
            String desc = mDesc.group(1);
            Matcher mDate = Pattern.compile("(?i)seit\\s+dem\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4})").matcher(desc);
            if (mDate.find()) {
                startDate = parseGermanDate(mDate.group(1));
            }
        }

        if (startDate == null) {
            startDate = "2023-01-19";
        }

        String birthDate = null;
        String birthPlace = null;
        Matcher mBirth = Pattern.compile("(?i)Geboren\\s+am\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4})(?:\\s*\\((?:in\\s+)?([^)]+)\\))?").matcher(html);
        if (mBirth.find()) {
            birthDate = parseGermanDate(mBirth.group(1));
            if (mBirth.group(2) != null) {
                birthPlace = cleanText(mBirth.group(2));
            }
        }

        // Vita table entries
        StringBuilder notes = new StringBuilder();
        notes.append("Position: Bundesminister der Verteidigung (GwG § 1 Abs. 12 a - Class A.1)");
        Matcher mTable = TABLE_BLOCK.matcher(html);
        if (mTable.find()) {
            Matcher mRow = ROW_BLOCK.matcher(mTable.group(1));
            while (mRow.find()) {
                List<String> cells = getCells(mRow.group(1));
                if (cells.size() >= 2 && !cells.get(0).equalsIgnoreCase("Zeitraum")) {
                    notes.append("\n- ").append(cells.get(0)).append(" : ").append(cells.get(1));
                }
            }
        }

        String[] parts = name.split("\\s+", 2);
        String firstName = parts[0];
        String lastName = parts.length > 1 ? parts[1] : "";

        return new MinisterData(
                name,
                firstName,
                lastName,
                birthDate,
                birthPlace,
                "Bundesminister der Verteidigung",
                startDate,
                null,
                "current",
                "SPD",
                pageUrl,
                photoUrl,
                notes.toString(),
                "A.1"
        );
    }

    public static MinisterData extractGeneralinspekteur(String html, String pageUrl, List<String> warnings) {
        String name = "Carsten Breuer";
        Matcher mName = Pattern.compile("(?i)Generalinspekteur\\s+der\\s+Bundeswehr,\\s+General\\s+([A-ZÄÖÜ][a-zäöüß]+(?:\\s+[A-ZÄÖÜ][a-zäöüß]+)+)").matcher(html);
        if (mName.find()) {
            name = cleanText(mName.group(1));
        }

        String photoUrl = null;
        Matcher mImg = OG_IMAGE.matcher(html);
        if (mImg.find()) {
            photoUrl = mImg.group(1);
        }

        String startDate = "2023-03-17";
        Matcher mTable = TABLE_BLOCK.matcher(html);
        StringBuilder notes = new StringBuilder();
        notes.append("Position: Generalinspekteur der Bundeswehr (GwG § 1 Abs. 12 f - Class F.1 - Officier supérieur des forces armées)");

        if (mTable.find()) {
            Matcher mRow = ROW_BLOCK.matcher(mTable.group(1));
            while (mRow.find()) {
                List<String> cells = getCells(mRow.group(1));
                if (cells.size() >= 2 && !cells.get(0).equalsIgnoreCase("Zeitraum")) {
                    String time = cells.get(0);
                    String task = cells.get(1);
                    if (time.toLowerCase(Locale.GERMAN).contains("seit") && task.contains("Generalinspekteur")) {
                        Matcher mD = Pattern.compile("(\\d{1,2}\\.\\d{1,2}\\.\\d{4})").matcher(time);
                        if (mD.find()) {
                            startDate = parseDottedDate(mD.group(1));
                        }
                    }
                    notes.append("\n- ").append(time).append(" : ").append(task);
                }
            }
        }

        String[] parts = name.split("\\s+", 2);
        String firstName = parts[0];
        String lastName = parts.length > 1 ? parts[1] : "";

        return new MinisterData(
                name,
                firstName,
                lastName,
                null,
                null,
                "Generalinspekteur der Bundeswehr",
                startDate,
                null,
                "current",
                null,
                pageUrl,
                photoUrl,
                notes.toString(),
                "F.1"
        );
    }

    public static List<MinisterData> extractFormerMinisters(String html, String pageUrl, List<String> warnings) {
        List<MinisterData> list = new ArrayList<>();

        int tableIdx = html.indexOf("Liste ehemaliger Verteidigungsministerinnen");
        String tablePart = tableIdx >= 0 ? html.substring(tableIdx) : html;

        Matcher mTable = TABLE_BLOCK.matcher(tablePart);
        if (!mTable.find()) {
            warnings.add("STRUCTURE_CHANGED: Table 'Liste ehemaliger Verteidigungsministerinnen' non trouvée");
            return list;
        }

        Matcher mRow = ROW_BLOCK.matcher(mTable.group(1));
        while (mRow.find()) {
            List<String> cells = getCells(mRow.group(1));
            if (cells.size() >= 3 && !cells.get(0).equalsIgnoreCase("Name")) {
                String rawName = cells.get(0);
                String party = cells.size() > 1 ? cells.get(1) : null;
                String tenure = cells.size() > 2 ? cells.get(2) : null;
                String chancellor = cells.size() > 3 ? cells.get(3) : "";
                String themes = cells.size() > 4 ? cells.get(4) : "";

                String startYear = null;
                String endYear = null;
                if (tenure != null) {
                    Matcher mTen = Pattern.compile("(\\d{4})\\s*[–\\-]\\s*(\\d{4})").matcher(tenure);
                    if (mTen.find()) {
                        startYear = mTen.group(1);
                        endYear = mTen.group(2);
                    }
                }

                String cleanName = cleanText(rawName);
                String[] parts = cleanName.split("\\s+", 2);
                String firstName = parts[0];
                String lastName = parts.length > 1 ? parts[1] : "";

                String notes = "Ehemalige(r) Verteidigungsminister(in) der Bundesrepublik Deutschland (Amtszeit: "
                        + tenure + ", Bundeskanzler/in: " + chancellor + ", Themen: " + themes + ")";

                list.add(new MinisterData(
                        cleanName,
                        firstName,
                        lastName,
                        null,
                        null,
                        "Bundesminister der Verteidigung",
                        startYear != null ? startYear + "-01-01" : null,
                        endYear != null ? endYear + "-12-31" : null,
                        "ended",
                        party,
                        pageUrl,
                        null,
                        notes,
                        "A.2"
                ));
            }
        }

        return list;
    }

    private static void writeMinister(MinisterData m, String positionId, FtmDatasetBuilder dataset) {
        String slug = slugify(m.name());
        FtmEntity person = dataset.newEntity("Person", slug);
        person.addValue("name", m.name());
        if (m.firstName() != null && !m.firstName().isBlank()) person.addValue("firstName", m.firstName());
        if (m.lastName() != null && !m.lastName().isBlank()) person.addValue("lastName", m.lastName());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", m.positionName());
        person.addValue("sourceUrl", m.sourceUrl());

        if (m.birthDate() != null) person.addValue("birthDate", m.birthDate());
        if (m.birthPlace() != null) person.addValue("birthPlace", m.birthPlace());
        if (m.party() != null && !m.party().isBlank()) person.addValue("political", m.party());
        if (m.notes() != null) {
            person.addValue("summary", m.notes());
            person.addValue("notes", m.notes());
        }

        dataset.addEntity(person);

        if (m.photoUrl() != null && !m.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), m.photoUrl(), m.sourceUrl(), "Amtliches Foto (bmvg.de)");
        }

        FtmEntity occupancy = dataset.newEntity("Occupancy", slug, "defence-" + m.gwgClass().toLowerCase());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", positionId);
        occupancy.addValue("status", m.status());
        occupancy.addValue("description", m.positionName() + " (" + m.gwgClass() + ")");
        occupancy.addValue("sourceUrl", m.sourceUrl());
        if (m.startDate() != null) occupancy.addValue("startDate", m.startDate());
        if (m.endDate() != null) occupancy.addValue("endDate", m.endDate());

        dataset.addEntity(occupancy);
    }

    private static void writeGeneral(MinisterData g, String positionId, FtmDatasetBuilder dataset) {
        String slug = slugify(g.name());
        FtmEntity person = dataset.newEntity("Person", slug);
        person.addValue("name", g.name());
        person.addValue("title", "General");
        if (g.firstName() != null && !g.firstName().isBlank()) person.addValue("firstName", g.firstName());
        if (g.lastName() != null && !g.lastName().isBlank()) person.addValue("lastName", g.lastName());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", g.positionName());
        person.addValue("sourceUrl", g.sourceUrl());

        if (g.notes() != null) {
            person.addValue("summary", g.notes());
            person.addValue("notes", g.notes());
        }

        dataset.addEntity(person);

        if (g.photoUrl() != null && !g.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), g.photoUrl(), g.sourceUrl(), "Offizielles Portrait (bmvg.de)");
        }

        FtmEntity occupancy = dataset.newEntity("Occupancy", slug, "generalinspekteur");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", positionId);
        occupancy.addValue("status", g.status());
        occupancy.addValue("description", g.positionName() + " (GwG § 1 Abs. 12 f - Class F.1)");
        occupancy.addValue("sourceUrl", g.sourceUrl());
        if (g.startDate() != null) occupancy.addValue("startDate", g.startDate());

        dataset.addEntity(occupancy);
    }

    public static String slugify(String text) {
        if (text == null || text.isBlank()) return "unknown";
        String s = text.trim().toLowerCase(Locale.GERMAN).replace("ß", "ss");
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = s.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "item" : s;
    }

    public static String cleanText(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<String> getCells(String rowHtml) {
        List<String> cells = new ArrayList<>();
        Matcher m = CELL_BLOCK.matcher(rowHtml);
        while (m.find()) {
            cells.add(cleanText(m.group(1)));
        }
        return cells;
    }

    private static String parseGermanDate(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("(\\d{1,2})\\.\\s*([A-Za-zäöüÄÖÜ]+)\\s*(\\d{4})").matcher(raw.trim());
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            String monthName = m.group(2).toLowerCase(Locale.GERMAN);
            String year = m.group(3);
            String month = GERMAN_MONTHS.getOrDefault(monthName, "01");
            return String.format("%s-%s-%02d", year, month, day);
        }
        return null;
    }

    private static String parseDottedDate(String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().split("\\.");
        if (parts.length == 3) {
            try {
                int day = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                int year = Integer.parseInt(parts[2]);
                return String.format("%04d-%02d-%02d", year, month, day);
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
