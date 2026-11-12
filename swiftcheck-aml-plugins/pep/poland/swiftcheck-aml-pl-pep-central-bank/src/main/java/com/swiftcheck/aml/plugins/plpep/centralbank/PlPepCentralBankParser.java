package com.swiftcheck.aml.plugins.plpep.centralbank;

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
 * <b>pl_pep_central_bank</b> – Membres du directoire et du Conseil de politique monétaire de la Banque nationale de Pologne (NBP & RPP).
 *
 * <p>Couvre la catégorie <b>PL-e</b>, fonction <b>Membre du directoire de la Banque nationale de Pologne</b> (fonction 492)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia NBP & RPP).
 */
@Extension
public class PlPepCentralBankParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepCentralBankParser.class);

    static final String ID_PREFIX = "pl-pep-central-bank";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String JSON_FILE = "pl-central-bank-wikipedia.json";

    static final String DEFAULT_TITLES = "Rada_Polityki_Pieni\u0119\u017cnej|"
            + "Narodowy_Bank_Polski|"
            + "Marta_Kightley|"
            + "Adam_Lipi\u0144ski|"
            + "Marta_Gaj\u0119cka|"
            + "Pawe\u0142_Sza\u0142amacha|"
            + "Piotr_Pogonowski|"
            + "Artur_Sobo\u0144|"
            + "Rafa\u0142_Sura";

    private static final Pattern REF_PATTERN = Pattern.compile("(?i)<ref[^>]*>.*?</ref>|<ref[^>]*/>|<ref[^>]*>");
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");

    private static final Map<String, String> PL_MONTHS = Map.ofEntries(
            Map.entry("stycznia", "01"),
            Map.entry("lutego", "02"),
            Map.entry("marca", "03"),
            Map.entry("kwietnia", "04"),
            Map.entry("maja", "05"),
            Map.entry("czerwca", "06"),
            Map.entry("lipca", "07"),
            Map.entry("sierpnia", "08"),
            Map.entry("wrze\u015bnia", "09"),
            Map.entry("pa\u017adziernika", "10"),
            Map.entry("listopada", "11"),
            Map.entry("grudnia", "12")
    );

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PlPepCentralBankParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PlPepCentralBankParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants de la Banque nationale de Pologne (NBP & RPP) index\u00e9s.", count)
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
                        "https://pl.wikipedia.org/"));
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
        String url = "https://pl.wikipedia.org/w/api.php?action=query&prop=revisions&rvprop=content&rvslots=main&titles="
                + encodedTitles + "&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        log.info("Interrogation MediaWiki pour la Banque nationale de Pologne (NBP & RPP) : {}", url);
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

        // 1. Parser Rada Polityki Pieniężnej (RPP)
        for (JsonNode page : pagesNode) {
            String title = page.path("title").asText("");
            if (!title.toLowerCase(Locale.ROOT).contains("rada polityki pieni")) {
                continue;
            }
            JsonNode revisions = page.path("revisions");
            if (!revisions.isArray() || revisions.isEmpty()) {
                continue;
            }
            String wikitext = revisions.get(0).path("slots").path("main").path("*").asText("");
            count += parseRppCouncil(wikitext, dataset, seenPersons);
        }

        // 2. Parser individual Zarząd pages
        for (JsonNode page : pagesNode) {
            String title = page.path("title").asText("");
            if (title.toLowerCase(Locale.ROOT).contains("polski") || title.toLowerCase(Locale.ROOT).contains("polityki")) {
                continue;
            }
            JsonNode revisions = page.path("revisions");
            if (!revisions.isArray() || revisions.isEmpty()) {
                continue;
            }
            String wikitext = revisions.get(0).path("slots").path("main").path("*").asText("");
            count += parseZarzadMember(title, wikitext, dataset, seenPersons);
        }

        return count;
    }

    private int parseRppCouncil(String wikitext, FtmDatasetBuilder dataset, Set<String> seenPersons) {
        int count = 0;
        // Locate current term, typically V kadencja (2022–2028)
        int termIdx = wikitext.lastIndexOf("kadencja");
        if (termIdx == -1) {
            return 0;
        }
        int sectionStart = wikitext.lastIndexOf("==", termIdx);
        String termSection = sectionStart != -1 ? wikitext.substring(sectionStart) : wikitext.substring(termIdx);

        String[] lines = termSection.split("[\\r\\n]+");
        boolean inCouncilMembers = false;

        for (String line : lines) {
            String l = line.trim();
            if (l.startsWith("==") && !l.toLowerCase(Locale.ROOT).contains("kadencja")) {
                break; // End of kadencja section
            }

            if (l.toLowerCase(Locale.ROOT).contains("prezes nbp") || l.toLowerCase(Locale.ROOT).contains("przewodnicz\u0105cy rady")) {
                inCouncilMembers = false;
            } else if (l.toLowerCase(Locale.ROOT).contains("cz\u0142onkowie rady")) {
                inCouncilMembers = true;
            }

            if (l.startsWith("*")) {
                String cleanLine = REF_PATTERN.matcher(l).replaceAll("");
                cleanLine = cleanLine.replaceAll("\\(do [^)]*\\)", ""); // Exclude former members marked "(do ...)"

                Matcher linkMatcher = WIKI_LINK_PATTERN.matcher(cleanLine);
                if (linkMatcher.find()) {
                    String name = cleanName(linkMatcher.group(1));
                    if (name.isEmpty() || name.length() < 3 || seenPersons.contains(name.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    seenPersons.add(name.toLowerCase(Locale.ROOT));

                    String position;
                    String org;
                    if (!inCouncilMembers) {
                        position = "Prezes Narodowego Banku Polskiego, Przewodnicz\u0105cy RPP";
                        org = "Narodowy Bank Polski (Zarz\u0105d & RPP)";
                    } else {
                        position = "Cz\u0142onek Rady Polityki Pieni\u0119\u017cnej (RPP)";
                        org = "Rada Polityki Pieni\u0119\u017cnej (RPP)";
                    }

                    addOfficial(name, position, org, "", "https://pl.wikipedia.org/wiki/Rada_Polityki_Pieni%C4%99%C5%BCnej", dataset);
                    count++;
                }
            }
        }
        return count;
    }

    private int parseZarzadMember(String title, String wikitext, FtmDatasetBuilder dataset, Set<String> seenPersons) {
        String name = cleanName(title);
        if (name.isEmpty() || seenPersons.contains(name.toLowerCase(Locale.ROOT))) {
            return 0;
        }

        Map<String, String> infobox = parseInfobox(wikitext);
        String birthDate = parseBirthDate(infobox.getOrDefault("data urodzenia", ""));

        String funkcja = infobox.getOrDefault("funkcja", "");
        String position;
        if (funkcja.toLowerCase(Locale.ROOT).contains("pierwszy zast\u0119pca")) {
            position = "Wiceprezes NBP - Pierwszy Zast\u0119pca Prezesa NBP";
        } else if (funkcja.toLowerCase(Locale.ROOT).contains("wiceprezes")) {
            position = "Wiceprezes Narodowego Banku Polskiego";
        } else {
            position = "Cz\u0142onek Zarz\u0105du NBP";
        }

        seenPersons.add(name.toLowerCase(Locale.ROOT));
        String pageUrl = "https://pl.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8);
        addOfficial(name, position, "Narodowy Bank Polski (Zarz\u0105d)", birthDate, pageUrl, dataset);
        return 1;
    }

    private void addOfficial(String name, String position, String org, String birthDate, String sourceUrl, FtmDatasetBuilder dataset) {
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
        person.addValue("nationality", COUNTRY);
        if (!birthDate.isEmpty()) {
            person.addValue("birthDate", birthDate);
        }
        person.addValue("position", position);
        person.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(person);

        String orgSlug = "org-" + org.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        FtmEntity orgEntity = dataset.newEntity("PublicBody", orgSlug);
        orgEntity.addValue("name", org);
        orgEntity.addValue("country", COUNTRY);
        orgEntity.addValue("jurisdiction", COUNTRY);
        dataset.addEntity(orgEntity);

        String posSlug = (position + "-" + org).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        FtmEntity pos = dataset.newEntity("Position", posSlug);
        pos.addValue("name", position);
        pos.addValue("country", COUNTRY);
        pos.addValue("organization", org);
        pos.addValue("sourceUrl", sourceUrl);
        pos.addValue("topics", "gov.financial");
        dataset.addEntity(pos);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", pos.getId());
        occupancy.addValue("status", "current");
        dataset.addEntity(occupancy);
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

    static String parseBirthDate(String raw) {
        String clean = REF_PATTERN.matcher(raw).replaceAll("").trim();
        clean = WIKI_LINK_PATTERN.matcher(clean).replaceAll("$1").trim();

        // Match format: 11 września 1956
        Pattern p = Pattern.compile("(\\d{1,2})\\s+([a-zA-Z\u0105\u0119\u015b\u0107\u017a\u017c\u00f3\u0142\u0144]+)\\s+(\\d{4})");
        Matcher m = p.matcher(clean);
        if (m.find()) {
            String day = m.group(1);
            if (day.length() == 1) {
                day = "0" + day;
            }
            String monthName = m.group(2).toLowerCase(Locale.ROOT);
            String month = PL_MONTHS.get(monthName);
            String year = m.group(3);
            if (month != null) {
                return year + "-" + month + "-" + day;
            }
        }
        return "";
    }

    private static String cleanName(String name) {
        String n = name.replaceAll("\\s*\\([^)]*\\)", "");
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
