package com.swiftcheck.aml.plugins.plpep.president;

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
 * <b>pl_pep_president</b> – Président de la République de Pologne et hauts responsables de la Présidence (KPRP & BBN).
 *
 * <p>Couvre la catégorie <b>PL-a</b>, fonction <b>Président de la République</b> (fonction 481)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia PL).
 */
@Extension
public class PlPepPresidentParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepPresidentParser.class);

    static final String ID_PREFIX = "pl-pep-president";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String JSON_FILE = "pl-president-wikipedia.json";

    static final String DEFAULT_TITLES = "Prezydent_Rzeczypospolitej_Polskiej|Kancelaria_Prezydenta_Rzeczypospolitej_Polskiej|Biuro_Bezpiecze%C5%84stwa_Narodowego|Lista_prezydent%C3%B3w_Polski";

    private static final Pattern LINK_PATTERN = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PlPepPresidentParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PlPepPresidentParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d hauts dirigeants de la Présidence de la République de Pologne indexés.", count)
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
                        "https://www.prezydent.pl/"));
            }
        }
        return records;
    }

    private void fetchLive(Path rawFile, ParserContext ctx) throws IOException, InterruptedException {
        String titles = ctx.parameters().getOrDefault("wikipedia_titles", DEFAULT_TITLES);
        String url = ctx.parameters().getOrDefault("wikipedia_api_url",
                "https://pl.wikipedia.org/w/api.php?action=query&prop=revisions&titles="
                        + titles + "&rvslots=*&rvprop=content&format=json");

        log.info("Récupération en direct des dirigeants présidentiels polonais depuis : {}", url);
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

        Map<String, Official> officials = new LinkedHashMap<>();

        pagesNode.fields().forEachRemaining(entry -> {
            JsonNode page = entry.getValue();
            String title = page.path("title").asText("");
            JsonNode revisions = page.path("revisions");
            if (revisions.isArray() && !revisions.isEmpty()) {
                String wikitext = revisions.get(0).path("slots").path("main").path("*").asText("");
                parsePage(title, wikitext, officials);
            }
        });

        if (officials.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant présidentiel extrait depuis " + rawFile);
        }

        int count = 0;
        for (Official off : officials.values()) {
            FtmEntity person = dataset.newEntity("Person", off.name);
            person.addValue("name", off.name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", off.position);
            person.addValue("sourceUrl", "https://www.prezydent.pl/");
            dataset.addEntity(person);

            String posSlug = (off.position + "-" + off.organization).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", off.position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", off.organization);
            pos.addValue("sourceUrl", "https://www.prezydent.pl/");
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

    private void parsePage(String title, String wikitext, Map<String, Official> officials) {
        if ("Lista prezydentów Polski".equalsIgnoreCase(title)) {
            int idx = wikitext.indexOf("III Rzeczpospolita");
            String scope = idx >= 0 ? wikitext.substring(idx) : wikitext;
            Matcher m = Pattern.compile("\\|\\s*'''?\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]'''?").matcher(scope);
            while (m.find()) {
                String candidate = m.group(2) != null ? m.group(2).trim() : m.group(1).trim();
                candidate = sanitizeName(candidate);
                if (isValidName(candidate)) {
                    officials.putIfAbsent(candidate, new Official(candidate, "Prezydent Rzeczypospolitej Polskiej", "Prezydent Rzeczypospolitej Polskiej"));
                }
            }
        } else if ("Kancelaria Prezydenta Rzeczypospolitej Polskiej".equalsIgnoreCase(title)) {
            extractInfoboxValues(wikitext, officials, "Kancelaria Prezydenta Rzeczypospolitej Polskiej", "Szef Kancelarii Prezydenta RP");
            int idx = wikitext.indexOf("Szefowie Kancelarii");
            if (idx >= 0) {
                String scope = wikitext.substring(idx);
                Matcher m = Pattern.compile("\\|\\s*\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]").matcher(scope);
                while (m.find()) {
                    String candidate = m.group(2) != null ? m.group(2).trim() : m.group(1).trim();
                    candidate = sanitizeName(candidate);
                    if (isValidName(candidate)) {
                        officials.putIfAbsent(candidate, new Official(candidate, "Szef Kancelarii Prezydenta RP", "Kancelaria Prezydenta Rzeczypospolitej Polskiej"));
                    }
                }
            }
        } else if ("Biuro Bezpieczeństwa Narodowego".equalsIgnoreCase(title)) {
            extractInfoboxValues(wikitext, officials, "Biuro Bezpieczeństwa Narodowego", "Szef Biura Bezpieczeństwa Narodowego");
            int idx = wikitext.indexOf("Szefowie Biura");
            if (idx < 0) {
                idx = wikitext.indexOf("Szefowie BBN");
            }
            if (idx >= 0) {
                String scope = wikitext.substring(idx);
                Matcher m = Pattern.compile("\\|\\s*\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]").matcher(scope);
                while (m.find()) {
                    String candidate = m.group(2) != null ? m.group(2).trim() : m.group(1).trim();
                    candidate = sanitizeName(candidate);
                    if (isValidName(candidate)) {
                        officials.putIfAbsent(candidate, new Official(candidate, "Szef Biura Bezpieczeństwa Narodowego", "Biuro Bezpieczeństwa Narodowego"));
                    }
                }
            }
        } else if ("Prezydent Rzeczypospolitej Polskiej".equalsIgnoreCase(title)) {
            extractInfoboxValues(wikitext, officials, "Prezydent Rzeczypospolitej Polskiej", "Prezydent Rzeczypospolitej Polskiej");
        }
    }

    private void extractInfoboxValues(String wikitext, Map<String, Official> officials, String org, String defaultPos) {
        Matcher infoboxMatcher = Pattern.compile("(?i)\\|\\s*(?:szef|zastępca|obecna osoba)\\s*=\\s*([^\n\r|]+)").matcher(wikitext);
        while (infoboxMatcher.find()) {
            String val = infoboxMatcher.group(1);
            Matcher linkM = LINK_PATTERN.matcher(val);
            while (linkM.find()) {
                String name = sanitizeName(linkM.group(1));
                if (isValidName(name)) {
                    officials.putIfAbsent(name, new Official(name, defaultPos, org));
                }
            }
        }
    }

    private String sanitizeName(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)<ref[^>]*>.*?</ref>|<ref[^>]*/>|<ref[^>]*>", "")
                .replaceAll("'''?", "")
                .replaceAll("\\(.*?\\)", "")
                .trim();
    }

    private boolean isValidName(String name) {
        if (name == null || name.length() < 5 || !name.contains(" ")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.contains("plik:") && !lower.contains("kategoria:") && !lower.contains("grafika:")
                && !lower.contains("prezydent") && !lower.contains("wybory") && !lower.contains("generał")
                && !lower.contains("admirał") && !lower.contains("polska") && !lower.contains("p.o.")
                && !lower.contains("90px") && !lower.contains("px");
    }

    private static class Official {
        final String name;
        final String position;
        final String organization;

        Official(String name, String position, String organization) {
            this.name = name;
            this.position = position;
            this.organization = organization;
        }
    }
}
