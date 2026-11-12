package com.swiftcheck.aml.plugins.ptpep.financialregulators;

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
 * <b>pt_pep_financial_regulators</b> – Organes dirigeants de la CMVM, de l'ASF et de la CNPD du Portugal.
 *
 * <p>Couvre la catégorie <b>PT-d</b>, fonction <b>Organes de la CMVM, de l'ASF et de la CNPD</b> (fonction 259)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia CMVM, ASF, CNPD).
 */
@Extension
public class PtPepFinancialRegulatorsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepFinancialRegulatorsParser.class);

    static final String ID_PREFIX = "pt-pep-financial-regulators";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String JSON_FILE = "pt-financial-regulators.json";

    static final String[] WIKI_REGULATOR_PAGES = {
            "Comiss\u00e3o_do_Mercado_de_Valores_Mobili\u00e1rios",
            "Autoridade_de_Supervis\u00e3o_de_Seguros_e_Fundos_de_Pens\u00f5es",
            "Comiss\u00e3o_Nacional_de_Prote\u00e7\u00e3o_de_Dados"
    };

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\|\\]]+)(?:\\|([^\\]]+))?\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PtPepFinancialRegulatorsParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PtPepFinancialRegulatorsParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants des régulateurs (CMVM, ASF, CNPD) indexés.", count)
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
                        "https://www.cmvm.pt/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        List<JsonNode> pages = new ArrayList<>();
        for (String page : WIKI_REGULATOR_PAGES) {
            String url = "https://pt.wikipedia.org/w/api.php?action=parse&page=" + page + "&prop=wikitext&format=json";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            log.info("Interrogation MediaWiki pour le régulateur : {}", page);
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

            String orgShort = getOrgShortName(title);

            // Extract from infobox chefe1_nome, presidente
            Pattern bossPattern = Pattern.compile("(?m)^\\s*\\|\\s*(?:chefe1_nome|presidente)\\s*=\\s*(.+)$");
            Matcher bm = bossPattern.matcher(wikitext);
            while (bm.find()) {
                String rawName = bm.group(1).trim();
                String name = cleanWiki(rawName);
                if (name.isEmpty() || name.length() < 3 || !seen.add(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                String posName = "Presidente do Conselho de Administração da " + orgShort;
                addLeader(dataset, name, posName, orgShort, "https://pt.wikipedia.org/wiki/" + title.replaceAll(" ", "_"));
                count++;
            }
        }

        // Add incumbent leaders of the 3 statutory regulators if not already parsed
        ensureLeader(dataset, seen, "Luís Laginha de Sousa", "Presidente do Conselho de Administração da CMVM", "CMVM", "https://www.cmvm.pt/");
        ensureLeader(dataset, seen, "Inês Drumond", "Vice-Presidente do Conselho de Administração da CMVM", "CMVM", "https://www.cmvm.pt/");
        ensureLeader(dataset, seen, "Margarida Corrêa de Aguiar", "Presidente do Conselho de Administração da ASF", "ASF", "https://www.asf.com.pt/");
        ensureLeader(dataset, seen, "Paula Meira Lourenço", "Presidente da Comissão Nacional de Proteção de Dados (CNPD)", "CNPD", "https://www.cnpd.pt/");

        // Extra members from parameters if supplied
        if (ctx != null && ctx.parameters() != null) {
            String extraMembersJson = ctx.parameters().get("members");
            if (extraMembersJson != null && !extraMembersJson.isBlank()) {
                try {
                    JsonNode extra = json.readTree(extraMembersJson);
                    JsonNode list = extra.isArray() ? extra : extra.path("members");
                    if (list.isArray()) {
                        for (JsonNode m : list) {
                            String name = m.path("name").asText("").trim();
                            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                                continue;
                            }
                            String org = m.path("organization").asText("Regulador");
                            String position = m.path("position").asText("Membro");
                            addLeader(dataset, name, position, org, "https://www.cmvm.pt/");
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Impossible de parser le paramètre members : {}", e.getMessage());
                }
            }
        }

        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).mapToInt(e -> 1).sum();
    }

    private void ensureLeader(FtmDatasetBuilder dataset, Set<String> seen, String name, String posName, String org, String url) {
        if (!seen.add(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        addLeader(dataset, name, posName, org, url);
    }

    private void addLeader(FtmDatasetBuilder dataset, String name, String position, String org, String sourceUrl) {
        FtmEntity person = dataset.newEntity("Person", name);
        person.addValue("name", name);
        person.addValue("country", COUNTRY);
        person.addValue("nationality", COUNTRY);
        person.addValue("position", position);
        person.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(person);

        FtmEntity posEntity = dataset.newEntity("Position", "reg-" + person.getId());
        posEntity.addValue("name", position);
        posEntity.addValue("country", COUNTRY);
        posEntity.addValue("organization", org);
        posEntity.addValue("topics", "gov.financial");
        posEntity.addValue("topics", "gov.national");
        dataset.addEntity(posEntity);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", posEntity.getId());
        occupancy.addValue("status", "current");
        dataset.addEntity(occupancy);
    }

    private String getOrgShortName(String title) {
        if (title.contains("Mobiliários") || title.contains("Valores")) {
            return "CMVM";
        }
        if (title.contains("Seguros") || title.contains("Pensões")) {
            return "ASF";
        }
        if (title.contains("Dados") || title.contains("Proteção")) {
            return "CNPD";
        }
        return "Regulador Financeiro";
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
