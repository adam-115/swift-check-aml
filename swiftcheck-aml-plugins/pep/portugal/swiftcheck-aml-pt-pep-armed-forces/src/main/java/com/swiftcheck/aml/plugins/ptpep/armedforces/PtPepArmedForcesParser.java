package com.swiftcheck.aml.plugins.ptpep.armedforces;

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
 * <b>pt_pep_armed_forces</b> – Hauts commandants des Forces armées, de la GNR et de la PSP du Portugal.
 *
 * <p>Couvre la catégorie <b>PT-e</b>, fonction <b>Officier général des forces armées et de la GNR, commissaire de la PSP</b> (fonction 263)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki de Wikipedia.
 */
@Extension
public class PtPepArmedForcesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepArmedForcesParser.class);

    static final String ID_PREFIX = "pt-pep-armed-forces";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String JSON_FILE = "pt-armed-forces.json";

    static final String[] WIKI_BRANCH_PAGES = {
            "Estado-Maior-General_das_Forças_Armadas",
            "Exército_Português",
            "Marinha_Portuguesa",
            "Força_Aérea_Portuguesa",
            "Guarda_Nacional_Republicana"
    };

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\|\\]]+)(?:\\|([^\\]]+))?\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PtPepArmedForcesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PtPepArmedForcesParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d hauts commandants des Forces armées et de sécurité du Portugal indexés.", count)
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
                        "https://www.defesa.gov.pt/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        List<JsonNode> pages = new ArrayList<>();
        for (String page : WIKI_BRANCH_PAGES) {
            String url = "https://pt.wikipedia.org/w/api.php?action=parse&page=" + page + "&prop=wikitext&section=0&format=json";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            log.info("Interrogation MediaWiki pour la page militaire portugaise : {}", page);
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                pages.add(json.readTree(resp.body()));
            }
        }
        Files.writeString(target, json.writeValueAsString(pages), StandardCharsets.UTF_8);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        List<JsonNode> pageNodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(pageNodes::add);
        } else {
            pageNodes.add(root);
        }

        Set<String> seen = new HashSet<>();
        int count = 0;

        for (JsonNode p : pageNodes) {
            String title = p.path("parse").path("title").asText("");
            String wikitext = p.path("parse").path("wikitext").path("*").asText("");
            if (wikitext.isEmpty()) {
                continue;
            }

            String branch = title.replaceAll("_", " ");
            List<String> leaders = extractLeaders(wikitext);
            for (String leaderName : leaders) {
                if (leaderName.isEmpty() || !seen.add(leaderName.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                String posName = "Comandante / Chefe do Estado-Maior – " + branch;

                FtmEntity person = dataset.newEntity("Person", leaderName);
                person.addValue("name", leaderName);
                person.addValue("country", COUNTRY);
                person.addValue("nationality", COUNTRY);
                person.addValue("position", posName);
                person.addValue("sourceUrl", "https://pt.wikipedia.org/wiki/" + title.replaceAll(" ", "_"));
                dataset.addEntity(person);

                FtmEntity posEntity = dataset.newEntity("Position", "pt-mil-" + person.getId());
                posEntity.addValue("name", posName);
                posEntity.addValue("country", COUNTRY);
                posEntity.addValue("organization", branch);
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

    private List<String> extractLeaders(String wikitext) {
        List<String> names = new ArrayList<>();
        Pattern linePattern = Pattern.compile("(?m)^\\s*\\|\\s*(?:comandante|chefe1_nome|diretor|secretario_geral)\\s*=\\s*(.+)$");
        Matcher lm = linePattern.matcher(wikitext);
        while (lm.find()) {
            String val = lm.group(1).trim();
            Matcher linkMatcher = WIKI_LINK_PATTERN.matcher(val);
            boolean foundLink = false;
            while (linkMatcher.find()) {
                String target = linkMatcher.group(1).trim();
                String label = linkMatcher.group(2) != null ? linkMatcher.group(2).trim() : target;
                if (!target.toLowerCase(Locale.ROOT).contains("general")
                        && !target.toLowerCase(Locale.ROOT).contains("almirante")
                        && !label.toLowerCase(Locale.ROOT).contains("general")
                        && !label.toLowerCase(Locale.ROOT).contains("almirante")
                        && !label.isEmpty()) {
                    names.add(label);
                    foundLink = true;
                }
            }
            if (!foundLink) {
                String clean = val.replaceAll("<ref[^>]*>.*?</ref>", "")
                        .replaceAll("<[^>]+>", "")
                        .replaceAll("\\[\\[[^\\]]+\\]\\]", "")
                        .replaceAll("(?i)^(?:General|Almirante|Tenente-General|Major-General|Brigadeiro-General)\\s+", "")
                        .trim();
                if (clean.length() > 3) {
                    names.add(clean);
                }
            }
        }
        return names;
    }
}
