package com.swiftcheck.aml.plugins.ptpep.internationalorgs;

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
 * <b>pt_pep_international_orgs</b> – Dirigeants des organisations internationales établies au Portugal.
 *
 * <p>Couvre la catégorie <b>PT-h</b>, fonction <b>Dirigeant d'une organisation internationale établie au Portugal</b> (fonction 267)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia CPLP, EMSA, EUDA).
 */
@Extension
public class PtPepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepInternationalOrgsParser.class);

    static final String ID_PREFIX = "pt-pep-international-orgs";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String JSON_FILE = "pt-international-orgs.json";

    static final String[] WIKI_PAGES = {
            "Secret\u00e1rio_Executivo_da_Comunidade_dos_Pa\u00edses_de_L\u00edngua_Portuguesa",
            "Ag\u00eancia_Europeia_de_Seguran\u00e7a_Mar\u00edtima",
            "Observat\u00f3rio_Europeu_da_Droga_e_da_Toxicodepend\u00eancia"
    };

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\|\\]]+)(?:\\|([^\\]]+))?\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PtPepInternationalOrgsParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PtPepInternationalOrgsParser(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        fetchLive(rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset, ctx);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d dirigeants des organisations internationales établies au Portugal indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            fetchLive(rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset, ctx);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://www.cplp.org/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        List<JsonNode> pages = new ArrayList<>();
        for (String page : WIKI_PAGES) {
            String url = "https://pt.wikipedia.org/w/api.php?action=parse&page=" + page + "&prop=wikitext&format=json";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            log.info("Interrogation MediaWiki pour l'organisation internationale : {}", page);
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                pages.add(json.readTree(resp.body()));
            }
        }
        Files.writeString(target, json.writeValueAsString(pages), StandardCharsets.UTF_8);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        return convert(rawFile, dataset, null);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset, ParserContext ctx) throws IOException {
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

            if (title.contains("CPLP") || title.contains("Língua Portuguesa")) {
                // Parse CPLP secretaries
                count += parseCplpSecretaries(dataset, seen, wikitext);
            } else {
                // Parse agency heads
                Pattern headPattern = Pattern.compile("(?m)^\\s*\\|\\s*(?:líder|diretor|presidente|chefe1_nome)\\s*=\\s*(.+)$");
                Matcher hm = headPattern.matcher(wikitext);
                while (hm.find()) {
                    String name = cleanWiki(hm.group(1));
                    if (!name.isEmpty() && name.length() > 3 && seen.add(name.toLowerCase(Locale.ROOT))) {
                        addLeader(dataset, name, "Diretor Executivo", title.replaceAll("_", " "), "https://pt.wikipedia.org/wiki/" + title);
                        count++;
                    }
                }
            }
        }

        // Ensure key incumbent leaders based in Lisbon
        ensureLeader(dataset, seen, "Zacarias da Costa", "Secretário Executivo da CPLP", "CPLP - Comunidade dos Países de Língua Portuguesa", "https://www.cplp.org/");
        ensureLeader(dataset, seen, "Maja Markovčić Kostelac", "Diretora Executiva da EMSA", "EMSA - Agência Europeia de Segurança Marítima", "https://www.emsa.europa.eu/");
        ensureLeader(dataset, seen, "Alexis Goosdeel", "Diretor Executivo da EUDA", "EUDA - Agência da União Europeia sobre Drogas", "https://www.euda.europa.eu/");
        ensureLeader(dataset, seen, "Francesca Camilleri Vettiger", "Presidente do Comité de Direção do Centro Norte-Sul", "Centro Norte-Sul do Conselho da Europa", "https://www.coe.int/nsc");
        ensureLeader(dataset, seen, "Ana Paula Laborinho", "Diretora do Escritório da OEI em Portugal", "OEI - Organização dos Estados Ibero-americanos (Lisboa)", "https://oei.int/");
        ensureLeader(dataset, seen, "Siobhán Fogarty", "Diretora do MAOC-N", "MAOC-N - Centro de Análise e Operações Marítimas", "https://maoc.eu/");

        // Extra leaders from parameters if supplied
        if (ctx != null && ctx.parameters() != null) {
            String extraLeadersJson = ctx.parameters().get("leaders");
            if (extraLeadersJson != null && !extraLeadersJson.isBlank()) {
                try {
                    JsonNode extra = json.readTree(extraLeadersJson);
                    JsonNode list = extra.isArray() ? extra : extra.path("leaders");
                    if (list.isArray()) {
                        for (JsonNode l : list) {
                            String name = l.path("name").asText("").trim();
                            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                                continue;
                            }
                            String org = l.path("organization").asText("Organização Internacional");
                            String position = l.path("position").asText("Dirigeant");
                            addLeader(dataset, name, position, org, "https://www.cplp.org/");
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Impossible de parser le paramètre leaders : {}", e.getMessage());
                }
            }
        }

        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).mapToInt(e -> 1).sum();
    }

    private int parseCplpSecretaries(FtmDatasetBuilder dataset, Set<String> seen, String wikitext) {
        int added = 0;
        String[] rows = wikitext.split("\\|-");
        for (String row : rows) {
            String[] lines = row.split("\n");
            List<String> cells = new ArrayList<>();
            for (String l : lines) {
                l = l.trim();
                if (l.startsWith("|")) {
                    cells.add(l.replaceFirst("^\\|\\s*", ""));
                }
            }
            if (cells.size() >= 2 && cells.get(0).matches("\\d+")) {
                String rawName = cells.get(1);
                String name = cleanWiki(rawName).replaceAll("'''", "").trim();
                if (!name.isEmpty() && name.length() > 3 && seen.add(name.toLowerCase(Locale.ROOT))) {
                    addLeader(dataset, name, "Secretário Executivo da CPLP", "CPLP - Comunidade dos Países de Língua Portuguesa", "https://www.cplp.org/");
                    added++;
                }
            }
        }
        return added;
    }

    private void ensureLeader(FtmDatasetBuilder dataset, Set<String> seen, String name, String position, String org, String url) {
        if (!seen.add(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        addLeader(dataset, name, position, org, url);
    }

    private void addLeader(FtmDatasetBuilder dataset, String name, String position, String org, String sourceUrl) {
        FtmEntity person = dataset.newEntity("Person", name);
        person.addValue("name", name);
        person.addValue("country", COUNTRY);
        person.addValue("position", position);
        person.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(person);

        FtmEntity orgEntity = dataset.newEntity("Organization", "io-" + org.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        orgEntity.addValue("name", org);
        orgEntity.addValue("country", COUNTRY);
        dataset.addEntity(orgEntity);

        FtmEntity posEntity = dataset.newEntity("Position", "intl-" + person.getId());
        posEntity.addValue("name", position);
        posEntity.addValue("country", COUNTRY);
        posEntity.addValue("organization", org);
        posEntity.addValue("topics", "gov.igo");
        dataset.addEntity(posEntity);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", posEntity.getId());
        occupancy.addValue("status", "current");
        dataset.addEntity(occupancy);
    }

    private String cleanWiki(String text) {
        if (text == null) return "";
        String s = text.replaceAll("<ref[^>]*>.*?</ref>", "")
                .replaceAll("<ref[^>]*/>", "")
                .replaceAll("<[^>]+>", "");
        Matcher m = WIKI_LINK_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String linkTarget = m.group(1);
            String linkText = m.group(2) != null ? m.group(2) : linkTarget;
            m.appendReplacement(sb, Matcher.quoteReplacement(linkText));
        }
        m.appendTail(sb);
        return sb.toString().trim();
    }
}
