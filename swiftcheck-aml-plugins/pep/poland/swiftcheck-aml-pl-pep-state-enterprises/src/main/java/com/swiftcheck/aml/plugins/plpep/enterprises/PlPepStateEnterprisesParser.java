package com.swiftcheck.aml.plugins.plpep.enterprises;

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
 * <b>pl_pep_state_enterprises</b> – Dirigeants des entreprises publiques d'État polonaises (Spółki Skarbu Państwa).
 *
 * <p>Couvre la catégorie <b>PL-g</b>, fonction <b>Dirigeant d'une entreprise détenue par l'État</b> (fonction 495)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia PL - Spółki Skarbu Państwa).
 */
@Extension
public class PlPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepStateEnterprisesParser.class);

    static final String ID_PREFIX = "pl-pep-state-enterprises";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String JSON_FILE = "pl-state-enterprises-wikipedia.json";

    static final String DEFAULT_TITLES = "Orlen|PGE_Polska_Grupa_Energetyczna|Powszechny_Zak%C5%82ad_Ubezpiecze%C5%84|KGHM_Polska_Mied%C5%BA|PKO_Bank_Polski|Polskie_Koleje_Pa%C5%84stwowe|Tauron_Polska_Energia|Enea|Totalizator_Sportowy|Jastrz%C4%99bska_Sp%C3%B3%C5%82ka_W%C4%99glowa|Bank_Gospodarstwa_Krajowego|Poczta_Polska|Polska_Grupa_Zbrojeniowa|Polskie_Linie_Lotnicze_LOT";

    private static final Pattern PREZES_PATTERN = Pattern.compile("(?im)^\\s*\\|\\s*(?:prezes|dyrektor|kluczowi ludzie)\\s*=\\s*([^\\n\\r|]+)");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PlPepStateEnterprisesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PlPepStateEnterprisesParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants des entreprises publiques d'État polonaises indexés.", count)
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
                        "https://www.gov.pl/web/aktywa-panstwowe"));
            }
        }
        return records;
    }

    private void fetchLive(Path rawFile, ParserContext ctx) throws IOException, InterruptedException {
        String titles = ctx.parameters().getOrDefault("wikipedia_titles", DEFAULT_TITLES);
        String url = ctx.parameters().getOrDefault("wikipedia_api_url",
                "https://pl.wikipedia.org/w/api.php?action=query&prop=revisions&titles="
                        + titles + "&redirects=1&rvslots=*&rvprop=content&format=json");

        log.info("Récupération en direct des dirigeants d'entreprises publiques polonaises depuis : {}", url);
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

        Map<String, EnterpriseLeader> leaders = new LinkedHashMap<>();

        pagesNode.fields().forEachRemaining(entry -> {
            JsonNode page = entry.getValue();
            String title = page.path("title").asText("");
            JsonNode revisions = page.path("revisions");
            if (revisions.isArray() && !revisions.isEmpty()) {
                String wikitext = revisions.get(0).path("slots").path("main").path("*").asText("");
                extractLeaders(title, wikitext, leaders);
            }
        });

        if (leaders.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant d'entreprise extrait depuis " + rawFile);
        }

        int count = 0;
        for (EnterpriseLeader leader : leaders.values()) {
            FtmEntity person = dataset.newEntity("Person", leader.name);
            person.addValue("name", leader.name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", leader.position + " – " + leader.enterprise);
            person.addValue("sourceUrl", "https://www.gov.pl/web/aktywa-panstwowe");
            dataset.addEntity(person);

            String posSlug = (leader.position + "-" + leader.enterprise).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", leader.position + " – " + leader.enterprise);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", leader.enterprise);
            pos.addValue("sourceUrl", "https://www.gov.pl/web/aktywa-panstwowe");
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

    private void extractLeaders(String title, String wikitext, Map<String, EnterpriseLeader> leaders) {
        Matcher m = PREZES_PATTERN.matcher(wikitext);
        while (m.find()) {
            String rawVal = m.group(1);
            Matcher linkM = LINK_PATTERN.matcher(rawVal);
            if (linkM.find()) {
                String name = sanitizeName(linkM.group(1));
                if (isValidName(name)) {
                    leaders.putIfAbsent(name, new EnterpriseLeader(name, "Prezes Zarządu", title));
                }
            } else {
                String name = sanitizeName(rawVal);
                if (isValidName(name)) {
                    leaders.putIfAbsent(name, new EnterpriseLeader(name, "Prezes Zarządu", title));
                }
            }
        }
    }

    private String sanitizeName(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)<ref[^>]*>.*?</ref>|<ref[^>]*/>|<ref[^>]*>", "")
                .replaceAll("'''?", "")
                .replaceAll("\\s*\\([^)]*\\)", "")
                .trim();
    }

    private boolean isValidName(String name) {
        if (name == null || name.length() < 5 || !name.contains(" ")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.contains("plik:") && !lower.contains("kategoria:") && !lower.contains("grafika:")
                && !lower.contains("brak") && !lower.contains("vacat") && !lower.contains("spółka");
    }

    private static class EnterpriseLeader {
        final String name;
        final String position;
        final String enterprise;

        EnterpriseLeader(String name, String position, String enterprise) {
            this.name = name;
            this.position = position;
            this.enterprise = enterprise;
        }
    }
}
