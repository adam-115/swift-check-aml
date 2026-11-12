package com.swiftcheck.aml.plugins.plpep.supremecourt;

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
 * <b>pl_pep_supreme_court</b> – Juges de la Cour suprême (Sąd Najwyższy) et de la Cour administrative suprême (NSA).
 *
 * <p>Couvre la catégorie <b>PL-d</b>, fonction <b>Juge de la Cour suprême</b> (fonction 489)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia PL).
 */
@Extension
public class PlPepSupremeCourtParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepSupremeCourtParser.class);

    static final String ID_PREFIX = "pl-pep-supreme-court";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String JSON_FILE = "pl-supreme-court-wikipedia.json";

    static final String DEFAULT_TITLES = "S%C4%85d_Najwy%C5%BCszy_(Polska)|Naczelny_S%C4%85d_Administracyjny";

    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("\\|\\s*align=center\\|\\s*(?:'''?)?\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]");
    private static final Pattern NSA_LIST_PATTERN = Pattern.compile("\\*\\s*od[^\\n\\r–-]+[–-]\\s*\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]");
    private static final Pattern IZBA_SECTION_PATTERN = Pattern.compile("(?i)=== Lista Prezesów Sądu Najwyższego kierujących pracą ([^=]+) ===([\\s\\S]*?)(?====|\\Z)");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PlPepSupremeCourtParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PlPepSupremeCourtParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d magistrats de la Cour suprême et du NSA de Pologne indexés.", count)
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
                        "https://www.sn.pl/"));
            }
        }
        return records;
    }

    private void fetchLive(Path rawFile, ParserContext ctx) throws IOException, InterruptedException {
        String titles = ctx.parameters().getOrDefault("wikipedia_titles", DEFAULT_TITLES);
        String url = ctx.parameters().getOrDefault("wikipedia_api_url",
                "https://pl.wikipedia.org/w/api.php?action=query&prop=revisions&titles="
                        + titles + "&redirects=1&rvslots=*&rvprop=content&format=json");

        log.info("Récupération en direct des magistrats de la Cour suprême et du NSA depuis : {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Échec requête MediaWiki API (" + response.statusCode() + ") : " + response.body());
        }

        Files.writeString(rawFile, response.body(), StandardCharsets.UTF_8);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pagesNode = root.path("query").path("pages");
        if (pagesNode.isMissingNode() || !pagesNode.isObject()) {
            throw new IllegalStateException("Réponse MediaWiki invalide : query.pages manquant dans " + rawFile);
        }

        Map<String, Judge> judges = new LinkedHashMap<>();

        pagesNode.fields().forEachRemaining(entry -> {
            JsonNode page = entry.getValue();
            String title = page.path("title").asText("");
            JsonNode revisions = page.path("revisions");
            if (revisions.isArray() && !revisions.isEmpty()) {
                String wikitext = revisions.get(0).path("slots").path("main").path("*").asText("");
                extractJudges(title, wikitext, judges);
            }
        });

        if (judges.isEmpty()) {
            throw new IllegalStateException("Aucun magistrat extrait depuis " + rawFile);
        }

        int count = 0;
        for (Judge j : judges.values()) {
            FtmEntity person = dataset.newEntity("Person", j.name);
            person.addValue("name", j.name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", j.position);
            person.addValue("sourceUrl", "https://www.sn.pl/");
            dataset.addEntity(person);

            String posSlug = (j.position + "-" + j.court).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", j.position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", j.court);
            pos.addValue("sourceUrl", "https://www.sn.pl/");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId() + "-" + pos.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private void extractJudges(String title, String wikitext, Map<String, Judge> judges) {
        if (title.contains("Sąd Najwyższy")) {
            // 1. Pierwszy Prezes Sądu Najwyższego
            int idx = wikitext.indexOf("Pierwsi Prezesi Sądu Najwyższego");
            if (idx < 0) {
                idx = wikitext.indexOf("Pierwszy Prezes");
            }
            if (idx >= 0) {
                String sub = wikitext.substring(idx, Math.min(wikitext.length(), idx + 15000));
                Matcher m = TABLE_NAME_PATTERN.matcher(sub);
                while (m.find()) {
                    String name = m.group(2) != null ? m.group(2).trim() : m.group(1).trim();
                    name = sanitizeName(name);
                    if (isValidName(name)) {
                        judges.putIfAbsent(name, new Judge(name, "Pierwszy Prezes Sądu Najwyższego", "Sąd Najwyższy"));
                    }
                }
            }

            // 2. Prezesi poszczególnych Izb
            Matcher im = IZBA_SECTION_PATTERN.matcher(wikitext);
            while (im.find()) {
                String izba = im.group(1).trim();
                String izbaContent = im.group(2);
                Matcher subm = TABLE_NAME_PATTERN.matcher(izbaContent);
                while (subm.find()) {
                    String name = subm.group(2) != null ? subm.group(2).trim() : subm.group(1).trim();
                    name = sanitizeName(name);
                    if (isValidName(name)) {
                        judges.putIfAbsent(name, new Judge(name, "Prezes Sądu Najwyższego kierujący " + izba, "Sąd Najwyższy"));
                    }
                }
            }
        } else if (title.contains("Naczelny Sąd Administracyjny")) {
            int idx = wikitext.indexOf("Prezesi Naczelnego Sądu Administracyjnego");
            if (idx < 0) {
                idx = wikitext.indexOf("Prezesi NSA");
            }
            if (idx >= 0) {
                String sub = wikitext.substring(idx, Math.min(wikitext.length(), idx + 3000));
                Matcher m = NSA_LIST_PATTERN.matcher(sub);
                while (m.find()) {
                    String name = m.group(2) != null ? m.group(2).trim() : m.group(1).trim();
                    name = sanitizeName(name);
                    if (isValidName(name)) {
                        judges.putIfAbsent(name, new Judge(name, "Prezes Naczelnego Sądu Administracyjnego", "Naczelny Sąd Administracyjny (NSA)"));
                    }
                }
            }
        }
    }

    private String sanitizeName(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)<ref[^>]*>.*?</ref>|<ref[^>]*/>|<ref[^>]*>", "")
                .replaceAll("'''?", "")
                .replaceAll("''\\(.*?\\)''", "")
                .replaceAll("\\s*\\([^)]*\\)", "")
                .trim();
    }

    private boolean isValidName(String name) {
        if (name == null || name.length() < 5 || !name.contains(" ")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.contains("plik:") && !lower.contains("kategoria:") && !lower.contains("grafika:")
                && !lower.contains("prezes") && !lower.contains("sąd") && !lower.contains("de facto");
    }

    private static class Judge {
        final String name;
        final String position;
        final String court;

        Judge(String name, String position, String court) {
            this.name = name;
            this.position = position;
            this.court = court;
        }
    }
}
