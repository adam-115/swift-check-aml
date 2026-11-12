package com.swiftcheck.aml.plugins.plpep.orgs;

import java.io.IOException;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <b>pl_pep_international_orgs</b> – Hauts dirigeants des organisations internationales ayant leur siège ou bureau principal en Pologne (Frontex, OSCE/ODIHR, CBSS).
 *
 * <p>Couvre la catégorie <b>PL-h</b>, fonction <b>Dirigeant d'une organisation internationale</b> (fonction 496)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia organisations internationales).
 */
@Extension
public class PlPepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepInternationalOrgsParser.class);

    static final String ID_PREFIX = "pl-pep-international-orgs";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String JSON_FILE = "pl-international-orgs-wikipedia.json";

    static final String DEFAULT_TITLES = "Frontex|"
            + "Office_for_Democratic_Institutions_and_Human_Rights|"
            + "Council_of_the_Baltic_Sea_States";

    private static final Pattern REF_PATTERN = Pattern.compile("(?i)<ref[^>]*>.*?</ref>|<ref[^>]*/>|<ref[^>]*>");
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{[^{}]*\\}\\}");
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PlPepInternationalOrgsParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PlPepInternationalOrgsParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d hauts dirigeants des organisations internationales \u00e0 Varsovie index\u00e9s.", count)
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
                        "https://en.wikipedia.org/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target, ParserContext ctx) throws IOException, InterruptedException {
        String titlesParam = null;
        if (ctx != null && ctx.parameters() != null) {
            titlesParam = ctx.parameters().get("titles");
        }
        String titles = (titlesParam != null && !titlesParam.isBlank()) ? titlesParam : DEFAULT_TITLES;

        String encodedTitles = URLEncoder.encode(titles, StandardCharsets.UTF_8).replace("%7C", "|");
        String url = "https://en.wikipedia.org/w/api.php?action=query&prop=revisions&rvprop=content&rvslots=main&titles="
                + encodedTitles + "&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        log.info("Interrogation MediaWiki pour les organisations internationales en Pologne : {}", url);
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 200) {
            Files.writeString(target, resp.body(), StandardCharsets.UTF_8);
            log.info("Donn\u00e9es MediaWiki enregistr\u00e9es dans {}", target);
        } else {
            throw new IOException("MediaWiki API a r\u00e9pondu avec le statut " + resp.statusCode());
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pagesNode = root.path("query").path("pages");
        if (pagesNode.isMissingNode() || !pagesNode.isObject()) {
            throw new IllegalStateException("Format de r\u00e9ponse MediaWiki inattendu dans : " + rawFile);
        }

        int count = 0;
        Set<String> seenPersons = new HashSet<>();

        for (JsonNode page : pagesNode) {
            String title = page.path("title").asText("");
            JsonNode revisions = page.path("revisions");
            if (!revisions.isArray() || revisions.isEmpty()) {
                continue;
            }
            String wikitext = revisions.get(0).path("slots").path("main").path("*").asText("");
            if (wikitext.isEmpty()) {
                continue;
            }

            String orgName = cleanOrgName(title);
            String pageUrl = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8);

            Map<String, String> infobox = parseInfobox(wikitext);
            List<RawOfficial> officials = extractOfficials(infobox, orgName);

            for (RawOfficial off : officials) {
                String name = cleanName(off.name);
                if (name.isEmpty() || name.length() < 3 || seenPersons.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                seenPersons.add(name.toLowerCase(Locale.ROOT));

                String role = off.position.isEmpty() ? "Official of " + orgName : off.position;

                FtmEntity person = dataset.newEntity("Person", name);
                person.addValue("name", name);
                String[] nameParts = splitName(name);
                if (!nameParts[0].isEmpty()) {
                    person.addValue("firstName", nameParts[0]);
                }
                if (!nameParts[1].isEmpty()) {
                    person.addValue("lastName", nameParts[1]);
                }
                person.addValue("country", COUNTRY);
                person.addValue("position", role + " \u2013 " + orgName);
                person.addValue("sourceUrl", pageUrl);
                dataset.addEntity(person);

                String orgSlug = "org-" + orgName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                FtmEntity orgEntity = dataset.newEntity("PublicBody", orgSlug);
                orgEntity.addValue("name", orgName);
                orgEntity.addValue("country", COUNTRY);
                orgEntity.addValue("jurisdiction", COUNTRY);
                dataset.addEntity(orgEntity);

                String posSlug = (role + "-" + orgName).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                FtmEntity pos = dataset.newEntity("Position", posSlug);
                pos.addValue("name", role + " \u2013 " + orgName);
                pos.addValue("country", COUNTRY);
                pos.addValue("organization", orgName);
                pos.addValue("sourceUrl", pageUrl);
                pos.addValue("topics", "role.pep");
                dataset.addEntity(pos);

                FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", pos.getId());
                occupancy.addValue("status", "current");
                dataset.addEntity(occupancy);

                count++;
            }
        }

        return count;
    }

    private static String cleanOrgName(String title) {
        if (title.contains("Frontex")) {
            return "Frontex";
        }
        if (title.contains("Democratic Institutions")) {
            return "OSCE/ODIHR";
        }
        if (title.contains("Baltic Sea")) {
            return "Council of the Baltic Sea States (CBSS)";
        }
        return title;
    }

    record RawOfficial(String name, String position) {}

    private static List<RawOfficial> extractOfficials(Map<String, String> infobox, String orgName) {
        List<RawOfficial> list = new ArrayList<>();

        // Check chief1_name ... chief8_name
        for (int i = 1; i <= 8; i++) {
            String nameKey = "chief" + i + "_name";
            String posKey = "chief" + i + "_position";
            if (infobox.containsKey(nameKey)) {
                String rawName = infobox.get(nameKey);
                String pos = infobox.getOrDefault(posKey, "Leader of " + orgName);
                String name = cleanName(rawName);
                if (!name.isEmpty() && !name.equalsIgnoreCase("vacant")) {
                    list.add(new RawOfficial(name, cleanName(pos)));
                }
            }
        }

        // Check leader_name, leader_name2, etc.
        for (int i = 1; i <= 5; i++) {
            String suffix = i == 1 ? "" : String.valueOf(i);
            String nameKey = "leader_name" + suffix;
            String posKey = "leader_title" + suffix;
            if (infobox.containsKey(nameKey)) {
                String rawName = infobox.get(nameKey);
                String pos = infobox.getOrDefault(posKey, "Leader of " + orgName);
                String name = cleanName(rawName);
                // Exclude country names like [[Estonia]]
                if (!name.isEmpty() && !name.equalsIgnoreCase("vacant") && !name.equalsIgnoreCase("Estonia") && !name.equalsIgnoreCase("Poland")) {
                    list.add(new RawOfficial(name, cleanName(pos)));
                }
            }
        }

        return list;
    }

    private static Map<String, String> parseInfobox(String wikitext) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] lines = wikitext.split("[\\r\\n]+");
        for (String line : lines) {
            String l = line.trim();
            if (l.startsWith("|") && l.contains("=")) {
                int eq = l.indexOf('=');
                String key = l.substring(1, eq).trim().toLowerCase(Locale.ROOT);
                String val = l.substring(eq + 1).trim();
                map.put(key, val);
            }
        }
        return map;
    }

    private static String cleanName(String text) {
        String n = REF_PATTERN.matcher(text).replaceAll("");
        while (TEMPLATE_PATTERN.matcher(n).find()) {
            n = TEMPLATE_PATTERN.matcher(n).replaceAll("");
        }
        n = WIKI_LINK_PATTERN.matcher(n).replaceAll("$1");
        n = n.replaceAll("\\s*\\([^)]*\\)", "");
        n = n.replaceAll("[\\(\\)\\[\\]\\{\\}\\\"']", "");
        return n.replaceAll("\\s+", " ").trim();
    }

    private static String[] splitName(String fullName) {
        String[] parts = fullName.split("\\s+");
        if (parts.length == 1) {
            return new String[]{parts[0], ""};
        }
        return new String[]{parts[0], parts[parts.length - 1]};
    }
}
