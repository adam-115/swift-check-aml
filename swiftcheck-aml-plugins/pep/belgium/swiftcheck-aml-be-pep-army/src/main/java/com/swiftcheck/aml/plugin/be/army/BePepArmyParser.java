package com.swiftcheck.aml.plugin.be.army;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
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

/**
 * Parser pour le Chef de la Défense (CHOD), le Vice-Chef de la Défense (VCHOD) et les
 * officiers généraux en fonction publiés sur le portail officiel de la Défense belge ({@code mil.be}).
 *
 * <p>Catégorie légale (Annexe IV de la loi belge du 18 septembre 2017) :
 * <b>BE-f</b> – <i>Officier général désigné par le Roi ou le ministre de la Défense</i>.
 */
@Extension
public class BePepArmyParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepArmyParser.class);

    static final String ID_PREFIX = "be-pep-army";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";
    static final String SOURCE_URL = "https://www.mil.be/fr/";
    static final String INSTITUTION = "La Défense (Forces armées belges)";
    static final String CONFIG_RESOURCE = "/army-pages.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile(
            "<p[^>]*>([\\s\\S]*?)</p>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern GENERAL_OFFICER_PATTERN = Pattern.compile(
            "(?:(?<preRole>Chief\\s+of\\s+Defence\\s*\\(CHOD\\))\\s*,\\s*le\\s+)?"
                    + "(?<rank>[Ll]ieutenant[- ][Gg]énéral(?:\\s+aviateur)?|[Gg]énéral[- ]major(?:\\s+aviateur)?|[Gg]énéral\\s+aviateur|[Gg]énéral\\s+de\\s+brigade(?:\\s+aviateur)?|[Vv]ice[- ]amiral|[Aa]miral\\s+de\\s+division|[Aa]miral\\s+de\\s+flottille)"
                    + "\\s+(?<name>[A-ZÀ-ÖØ-Þ][a-zà-öø-ÿ]+(?:\\s+(?:(?:[Vv]an|[Dd]e|[Dd]en|[Dd]er|[Tt]en|[Tt]er)\\s+)?[A-ZÀ-ÖØ-Þ][a-zà-öø-ÿ]+){0,3})"
                    + "(?:\\s*,\\s*(?<postRole>(?:commandant|directeur\\s+général|chef\\s+de\\s+la\\s+Défense|vice[- ]\\s*Chief\\s+Of\\s+Defence)[^.;\\n]{3,120}))?",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArmyPageConfig(String code, String url) {}

    public record OfficerRecord(
            String fullName,
            String firstName,
            String lastName,
            String rank,
            String position,
            String sourceUrl
    ) {}

    public List<ArmyPageConfig> loadConfigs() throws IOException {
        return loadConfigs(null);
    }

    public List<ArmyPageConfig> loadConfigs(String pagesParam) throws IOException {
        if (pagesParam != null && !pagesParam.isBlank()) {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(pagesParam);
            com.fasterxml.jackson.databind.JsonNode pagesNode = root.has("pages") ? root.path("pages") : root;
            return MAPPER.readValue(pagesNode.traverse(), new TypeReference<List<ArmyPageConfig>>() {});
        }
        try (InputStream is = getClass().getResourceAsStream(CONFIG_RESOURCE)) {
            if (is == null) {
                throw new IOException("Ressource introuvable : " + CONFIG_RESOURCE);
            }
            return MAPPER.readValue(is, new TypeReference<List<ArmyPageConfig>>() {});
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<ArmyPageConfig> configs = loadConfigs(p.get("armyPages"));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (ArmyPageConfig cfg : configs) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.url()))
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - mil.be)")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "fr-BE,fr;q=0.9")
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200) {
                    Files.writeString(ctx.rawDir().resolve(cfg.code() + ".html"), resp.body(), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.warn("[be_pep_army] Impossible de télécharger {} ({}) : {}",
                        cfg.code(), cfg.url(), e.getMessage());
            }
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<OfficerRecord> officers = convert(ctx.rawDir(), dataset, configs);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(
                officers.size() + " officiers généraux extraits depuis mil.be.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<ArmyPageConfig> configs = loadConfigs(p.get("armyPages"));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset, configs);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    List<OfficerRecord> convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        return convert(rawDir, dataset, loadConfigs());
    }

    List<OfficerRecord> convert(Path rawDir, FtmDatasetBuilder dataset, List<ArmyPageConfig> configs) throws IOException {
        Map<String, OfficerRecord> byKey = new LinkedHashMap<>();

        for (ArmyPageConfig cfg : configs) {
            Path file = rawDir.resolve(cfg.code() + ".html");
            if (!Files.isRegularFile(file) || Files.size(file) < 50) {
                try (InputStream is = getClass().getResourceAsStream("/" + cfg.code() + ".html")) {
                    if (is != null) {
                        Files.copy(is, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception ignored) {
                }
            }
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String html = Files.readString(file, StandardCharsets.UTF_8);
            for (OfficerRecord rec : parsePageHtml(html, cfg.url())) {
                byKey.putIfAbsent(rec.fullName().toLowerCase(Locale.ROOT), rec);
            }
        }

        if (byKey.isEmpty()) {
            throw new IllegalStateException("Aucun officier général extrait depuis " + rawDir);
        }

        List<OfficerRecord> list = new ArrayList<>(byKey.values());
        for (OfficerRecord rec : list) {
            writeRecord(rec, dataset);
        }
        return list;
    }

    public List<OfficerRecord> parsePageHtml(String html, String pageUrl) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Map<String, OfficerRecord> byKey = new LinkedHashMap<>();
        Matcher pm = PARAGRAPH_PATTERN.matcher(html);

        while (pm.find()) {
            String text = cleanText(pm.group(1));
            if (text.length() < 15) {
                continue;
            }
            Matcher matcher = GENERAL_OFFICER_PATTERN.matcher(text);
            while (matcher.find()) {
                String rank = capitalizeFirst(cleanText(matcher.group("rank")));
                String rawName = cleanText(matcher.group("name"));
                String preRole = cleanText(matcher.group("preRole"));
                String postRole = cleanText(matcher.group("postRole"));

                String cleanedName = stripTrailingStopwords(rawName);
                if (!isLikelyOfficerName(cleanedName)) {
                    continue;
                }

                String role = !postRole.isBlank() ? postRole : preRole;
                if (role.isBlank() && text.contains("VCHOD")) {
                    role = "Vice-Chief of Defence (VCHOD)";
                }
                String position = !role.isBlank()
                        ? rank + " – " + capitalizeFirst(role)
                        : rank + " de la Défense belge";

                String[] fl = splitFirstLast(cleanedName);
                String key = cleanedName.toLowerCase(Locale.ROOT);
                byKey.putIfAbsent(key, new OfficerRecord(
                        cleanedName,
                        fl[0],
                        fl[1],
                        rank,
                        position,
                        pageUrl != null && !pageUrl.isBlank() ? pageUrl : SOURCE_URL
                ));
            }
        }

        return new ArrayList<>(byKey.values());
    }

    public static void writeRecord(OfficerRecord rec, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", "army", rec.fullName());
        person.addValue("name", rec.fullName());
        if (!rec.firstName().isBlank()) {
            person.addValue("firstName", rec.firstName());
        }
        person.addValue("lastName", rec.lastName().isBlank() ? rec.fullName() : rec.lastName());
        person.addValue("nationality", COUNTRY);
        person.addValue("country", COUNTRY);
        person.addValue("position", rec.position());
        person.addValue("sourceUrl", rec.sourceUrl());
        person.addValue("notes", "Grade : " + rec.rank() + " (" + INSTITUTION + ")");
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", rec.position());
        position.addValue("name", rec.position());
        position.addValue("country", COUNTRY);
        position.addValue("organization", INSTITUTION);
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.security");
        dataset.addEntity(position);

        FtmEntity occ = dataset.newEntity("Occupancy", rec.fullName(), rec.position());
        occ.addValue("holder", person.getId());
        occ.addValue("post", position.getId());
        occ.addValue("status", "current");
        occ.addValue("sourceUrl", rec.sourceUrl());
        dataset.addEntity(occ);
    }

    private static String stripTrailingStopwords(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("\\s+(?:a|et|ou|en|dans|pour|sur|avec|lors|qui|dont)\\b.*$", "").trim();
    }

    private static boolean isLikelyOfficerName(String name) {
        if (name == null || name.length() < 4 || name.length() > 55) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.contains("quartier") && !lower.contains("défense") && !lower.contains("état-major");
    }

    static String[] splitFirstLast(String fullName) {
        String cleaned = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        String[] tokens = cleaned.split(" ");
        if (tokens.length <= 1) {
            return new String[]{"", cleaned};
        }
        String firstLower = tokens[0].toLowerCase(Locale.ROOT);
        if (firstLower.equals("van") || firstLower.equals("de") || firstLower.equals("den") || firstLower.equals("der")) {
            return new String[]{"", cleaned};
        }
        return new String[]{tokens[0], String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length))};
    }

    private static String capitalizeFirst(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&#8217;", "'")
                .replace("&rsquo;", "'")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
