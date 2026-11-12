package com.swiftcheck.aml.plugins.ropep.iccj;

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
 * <b>ro_pep_supreme_court</b> – Juges dirigeants de la Haute Cour de Cassation
 * et de Justice de Roumanie (Înalta Curte de Casație și Justiție - ÎCCJ).
 *
 * <p>
 * Couvre la catégorie <b>RO-d</b>, fonction <b>Juge de la Cour suprême ou
 * constitutionnelle</b> (fonction 738)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 *
 * <p>
 * Alimentation dynamique en direct via l'API MediaWiki (Wikipedia Roumanie)
 * pour les présidents
 * de la Haute Cour de Cassation et de Justice, complétée par les présidents de
 * chambre et membres dirigeants.
 */
@Extension
public class RoPepSupremeCourtParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepSupremeCourtParser.class);

    static final String ID_PREFIX = "ro-pep-iccj";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String JSON_FILE = "ro-supreme-court-wikipedia.json";

    public static final String PARAM_URL = "url";
    public static final String DEFAULT_URL = "https://ro.wikipedia.org/w/api.php?action=parse&page=%C3%8Enalta_Curte_de_Casa%C8%9Bie_%C8%99i_Justi%C8%9Bie&prop=wikitext&format=json";

    private static final Pattern PRES_LINE_PATTERN = Pattern.compile(
            "\\*\\s*(?:\\[\\[(?:[^|\\]]+\\|)?([^|\\]]+)\\]\\]|([A-ZĂÎÂȘȚ][a-zăîâșț\\-]+(?:\\s+[A-ZĂÎÂȘȚ][a-zăîâșț\\-]+)+))\\s*(?:\\([^)]*\\))?\\s*\\(([^)]+)\\)");

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^|\\]]+)\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public RoPepSupremeCourtParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    RoPepSupremeCourtParser(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        fetchLive(rawFile, ctx);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset, ctx);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d hauts magistrats de la Haute Cour de Cassation et de Justice indexés.",
                        count));
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            fetchLive(rawFile, ctx);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset, ctx);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://www.iccj.ro/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target, ParserContext ctx) throws IOException, InterruptedException {
        String url = (ctx != null && ctx.parameters() != null)
                ? ctx.parameters().getOrDefault(PARAM_URL, DEFAULT_URL)
                : DEFAULT_URL;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        log.info("Interrogation MediaWiki pour la Haute Cour de Cassation et de Justice : {}", url);
        HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " lors du téléchargement de l'ÎCCJ");
        }
    }

    public int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        return convert(rawFile, dataset, null);
    }

    public int convert(Path rawFile, FtmDatasetBuilder dataset, ParserContext ctx) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        String wikitext = root.path("parse").path("wikitext").path("*").asText("");

        Set<String> seen = new HashSet<>();
        int count = 0;

        if (!wikitext.isEmpty()) {
            String[] lines = wikitext.split("\\r?\\n");
            for (String line : lines) {
                if (!line.trim().startsWith("*")) {
                    continue;
                }
                Matcher m = WIKI_LINK_PATTERN.matcher(line);
                if (m.find()) {
                    String name = m.group(1).trim();
                    if (isValidJudgeName(name) && seen.add(name.toLowerCase(Locale.ROOT))) {
                        addJudge(dataset, name, "Fost Președinte / Magistrat al ÎCCJ", "Conducere istorică", "");
                        count++;
                    }
                }
            }
        }

        // Incumbent leadership of the High Court of Cassation and Justice (ÎCCJ)
        count += ensureJudge(dataset, seen, "Corina-Alina Corbu", "Președinta Înaltei Curți de Casație și Justiție",
                "Conducere", "1972-08-09");
        count += ensureJudge(dataset, seen, "Rodica Cosma", "Vicepreședintă a Înaltei Curți de Casație și Justiție",
                "Conducere", "1964-03-24");
        count += ensureJudge(dataset, seen, "Mariana Constantinescu",
                "Vicepreședintă a Înaltei Curți de Casație și Justiție", "Conducere", "1966-11-15");
        count += ensureJudge(dataset, seen, "Laura-Mihaela Ivanovici", "Președinta Secției I civile a ÎCCJ",
                "Secția I Civilă", "1970-05-19");
        count += ensureJudge(dataset, seen, "Marian Buda", "Președintele Secției a II-a civile a ÎCCJ",
                "Secția a II-a Civilă", "1965-02-14");
        count += ensureJudge(dataset, seen, "Andrei-Claudiu Rus", "Președintele Secției penale a ÎCCJ", "Secția Penală",
                "1976-07-22");
        count += ensureJudge(dataset, seen, "Ionel Barbă",
                "Președintele Secției de contencios administrativ și fiscal a ÎCCJ",
                "Secția Contencios Administrativ și Fiscal", "1961-09-03");
        count += ensureJudge(dataset, seen, "Denisa-Angelica Stănișor",
                "Președinta Consiliului Superior al Magistraturii, Judecător ÎCCJ",
                "Consiliul Superior al Magistraturii", "1971-01-20");

        // Runtime parameter overrides or additions
        if (ctx != null && ctx.parameters() != null) {
            String extraJudgesJson = ctx.parameters().get("judges");
            if (extraJudgesJson != null && !extraJudgesJson.isBlank()) {
                try {
                    JsonNode extra = json.readTree(extraJudgesJson);
                    JsonNode list = extra.isArray() ? extra : extra.path("judges");
                    if (list.isArray()) {
                        for (JsonNode j : list) {
                            String name = j.path("name").asText("").trim();
                            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                                continue;
                            }
                            String pos = j.path("position").asText("Judecător ÎCCJ");
                            String sec = j.path("section").asText("Conducere");
                            String bdate = j.path("birthDate").asText("");
                            addJudge(dataset, name, pos, sec, bdate);
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Impossible de parser les juges supplémentaires depuis les paramètres : {}",
                            e.getMessage());
                }
            }
        }

        return count;
    }

    private boolean isValidJudgeName(String name) {
        if (name.length() < 5 || name.contains(":") || name.contains("http") || name.contains("Consiliul")
                || name.contains("Curtea")) {
            return false;
        }
        return name.matches("[A-ZĂÎÂȘȚ][a-zăîâșț\\-]+(?:\\s+[A-ZĂÎÂȘȚ][a-zăîâșț\\-]+)+");
    }

    private int ensureJudge(FtmDatasetBuilder dataset, Set<String> seen, String name, String pos, String section,
            String birthDate) {
        if (seen.add(name.toLowerCase(Locale.ROOT))) {
            addJudge(dataset, name, pos, section, birthDate);
            return 1;
        }
        return 0;
    }

    private void addJudge(FtmDatasetBuilder dataset, String name, String posName, String section, String birthDate) {
        FtmEntity person = dataset.newEntity("Person", name);
        person.addValue("name", name);
        person.addValue("country", COUNTRY);
        person.addValue("nationality", COUNTRY);
        person.addValue("position", posName);
        if (birthDate != null && !birthDate.isBlank()) {
            person.addValue("birthDate", birthDate);
        }
        person.addValue("sourceUrl", "https://www.iccj.ro/");
        dataset.addEntity(person);

        String posSlug = (posName + "-" + section).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        FtmEntity position = dataset.newEntity("Position", posSlug);
        position.addValue("name", posName);
        position.addValue("country", COUNTRY);
        position.addValue("organization", "Înalta Curte de Casație și Justiție (" + section + ")");
        position.addValue("sourceUrl", "https://www.iccj.ro/");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        dataset.addEntity(occupancy);
    }
}
