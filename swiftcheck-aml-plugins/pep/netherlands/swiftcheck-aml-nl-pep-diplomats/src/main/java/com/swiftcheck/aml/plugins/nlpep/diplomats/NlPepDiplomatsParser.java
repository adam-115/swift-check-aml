package com.swiftcheck.aml.plugins.nlpep.diplomats;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * <b>nl_pep_diplomats</b> – Ambassadeurs et chefs de mission diplomatique des Pays-Bas.
 *
 * <p>Couvre la catégorie <b>NL-f</b>, fonction <b>Ambassadeur ou chargé d'affaires</b> (fonction 387)
 * (Wwft art. 1 ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki de Wikipedia (Lijst van Nederlandse ambassades).
 */
@Extension
public class NlPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(NlPepDiplomatsParser.class);

    static final String ID_PREFIX = "nl-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "nl";
    static final String JSON_FILE = "nl-diplomats.json";

    static final String WIKIPEDIA_API_URL =
            "https://nl.wikipedia.org/w/api.php?action=parse&page=Lijst_van_Nederlandse_ambassades&prop=wikitext&format=json";

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\|\\]]+)(?:\\|([^\\]]+))?\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public NlPepDiplomatsParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    NlPepDiplomatsParser(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        fetchLive(rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d ambassadeurs et représentants permanents des Pays-Bas indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            fetchLive(rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://www.rijksoverheid.nl/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WIKIPEDIA_API_URL))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        log.info("Interrogation de Wikipedia MediaWiki API pour les ambassadeurs néerlandais : {}", WIKIPEDIA_API_URL);
        HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() != 200) {
            throw new IOException("Échec requête MediaWiki API (HTTP " + resp.statusCode() + ")");
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        String wikitext = root.path("parse").path("wikitext").path("*").asText("");
        if (wikitext.isEmpty()) {
            throw new IllegalStateException("Aucun wikitext trouvé dans : " + rawFile);
        }

        Set<String> seen = new HashSet<>();
        int count = 0;

        String[] rows = wikitext.split("(?m)^\\|-\\s*$");
        for (String row : rows) {
            String[] lines = row.split("\r?\n");
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                String trimmed = l.trim();
                if (trimmed.startsWith("|")) {
                    sb.append(" ").append(trimmed);
                }
            }
            String combined = sb.toString();
            String[] cols = combined.split("\\|\\|");
            if (cols.length < 5) {
                continue;
            }

            String repType = cols[2].trim();
            if (!repType.contains("Ambassade") && !repType.contains("vertegenwoordigingen")) {
                continue;
            }

            String countryCol = cleanWikiMarkup(cols[0]);
            String locationCol = cleanWikiMarkup(cols[1]);
            String personCol = cols[4].trim();

            List<String> names = extractPersonNames(personCol);
            for (String name : names) {
                name = name.trim();
                if (name.isEmpty() || name.length() < 3 || !seen.add(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                String postName = "Ambassadeur van Nederland in " + (!locationCol.isEmpty() ? locationCol : countryCol);

                FtmEntity person = dataset.newEntity("Person", name);
                person.addValue("name", name);
                person.addValue("country", COUNTRY);
                person.addValue("nationality", COUNTRY);
                person.addValue("position", postName);
                person.addValue("sourceUrl", "https://nl.wikipedia.org/wiki/Lijst_van_Nederlandse_ambassades");
                dataset.addEntity(person);

                FtmEntity posEntity = dataset.newEntity("Position", "amb-" + person.getId());
                posEntity.addValue("name", postName);
                posEntity.addValue("country", COUNTRY);
                posEntity.addValue("organization", "Ministerie van Buitenlandse Zaken");
                posEntity.addValue("topics", TOPIC);
                dataset.addEntity(posEntity);

                FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", posEntity.getId());
                occupancy.addValue("status", "current");
                dataset.addEntity(occupancy);

                count++;
            }
        }
        return count;
    }

    private List<String> extractPersonNames(String raw) {
        List<String> names = new ArrayList<>();
        Matcher m = WIKI_LINK_PATTERN.matcher(raw);
        while (m.find()) {
            String target = m.group(1).trim();
            String label = m.group(2) != null ? m.group(2).trim() : target;
            if (!target.toLowerCase(Locale.ROOT).contains("vlag")
                    && !target.toLowerCase(Locale.ROOT).contains("zaakgelastigde")
                    && !label.toLowerCase(Locale.ROOT).contains("zaakgelastigde")
                    && !label.startsWith("Link")
                    && !label.isEmpty()) {
                names.add(label);
            }
        }
        if (names.isEmpty()) {
            String cleaned = raw.replaceAll("<[^>]+>", " ")
                    .replaceAll("\\[[^\\]]+\\]", "")
                    .replaceAll("\\([^\\)]+\\)", "")
                    .replaceAll("\\{\\{[^\\}]+\\}\\}", "")
                    .trim();
            if (!cleaned.isEmpty() && cleaned.length() > 3 && !cleaned.equalsIgnoreCase("tijdelijk gesloten")) {
                names.add(cleaned);
            }
        }
        return names;
    }

    private String cleanWikiMarkup(String text) {
        return text.replaceAll("<[^>]+>", "")
                .replaceAll("\\[\\[(?:[^\\|\\]]+\\|)?([^\\]]+)\\]\\]", "$1")
                .replaceAll("\\{\\{[^\\}]+\\}\\}", "")
                .replaceAll("\\|", "")
                .trim();
    }
}
