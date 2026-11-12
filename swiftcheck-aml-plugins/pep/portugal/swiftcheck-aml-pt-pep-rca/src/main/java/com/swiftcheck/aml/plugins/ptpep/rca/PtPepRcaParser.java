package com.swiftcheck.aml.plugins.ptpep.rca;

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
 * <b>pt_pep_rca</b> – Membres de la famille et proches associés de PEP portugais (RCA).
 *
 * <p>Couvre la catégorie <b>PT-RCA</b>, fonctions <b>Membre de la famille d'une PEP portugaise</b> (fonction 271)
 * et <b>Proche associé d'une PEP portugaise</b> (fonction 272)
 * (Lei n.º 83/2017, art. 2.º, al. dd) et ee) ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia).
 */
@Extension
public class PtPepRcaParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepRcaParser.class);

    static final String ID_PREFIX = "pt-pep-rca";
    static final String TOPIC = "role.rca";
    static final String COUNTRY = "pt";
    static final String JSON_FILE = "pt-rca.json";

    static final String[] WIKI_LEADER_PAGES = {
            "Marcelo_Rebelo_de_Sousa",
            "Ant\u00f3nio_Costa",
            "Lu\u00eds_Montenegro"
    };

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\|\\]]+)(?:\\|([^\\]]+))?\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PtPepRcaParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PtPepRcaParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d proches et associés (RCA) de PEP portugais indexés.", count)
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
                        "https://www.tribunalconstitucional.pt/tc/transparencia.html"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        List<JsonNode> pages = new ArrayList<>();
        for (String page : WIKI_LEADER_PAGES) {
            String url = "https://pt.wikipedia.org/w/api.php?action=parse&page=" + page + "&prop=wikitext&section=0&format=json";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            log.info("Interrogation MediaWiki pour la PEP : {}", page);
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

            String targetPep = title.replaceAll("_", " ");

            Pattern famPattern = Pattern.compile("(?m)^\\s*\\|\\s*(cônjuge|companheira|companheiro|nome_mãe|nome_pai)\\s*=\\s*(.+)$");
            Matcher fm = famPattern.matcher(wikitext);
            while (fm.find()) {
                String field = fm.group(1).trim().toLowerCase(Locale.ROOT);
                String rawVal = fm.group(2).trim();
                String relName = cleanWiki(rawVal);
                if (relName.isEmpty() || relName.length() < 3 || relName.matches("\\d+") || !seen.add(relName.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                String relationship = "Familiar de " + targetPep;
                if (field.contains("cônjuge") || field.contains("companheir")) {
                    relationship = "Cônjuge / Companheiro(a) de " + targetPep;
                } else if (field.contains("mãe")) {
                    relationship = "Mãe de " + targetPep;
                } else if (field.contains("pai")) {
                    relationship = "Pai de " + targetPep;
                }

                addAssociate(dataset, relName, relationship, targetPep, "FAMILY");
                count++;
            }
        }

        // Incumbent documented relatives & associates
        ensureAssociate(dataset, seen, "Nuno Rebelo de Sousa", "Filho de Marcelo Rebelo de Sousa (Presidente da República)", "Marcelo Rebelo de Sousa", "FAMILY");
        ensureAssociate(dataset, seen, "Rita Amaral Cabral", "Companheira de Marcelo Rebelo de Sousa (Presidente da República)", "Marcelo Rebelo de Sousa", "FAMILY");
        ensureAssociate(dataset, seen, "Carla Montenegro", "Cônjuge de Luís Montenegro (Primeiro-Ministro)", "Luís Montenegro", "FAMILY");
        ensureAssociate(dataset, seen, "Fernanda Tadeu", "Cônjuge de António Costa (Antigo Primeiro-Ministro)", "António Costa", "FAMILY");
        ensureAssociate(dataset, seen, "Pedro Costa", "Filho de António Costa e Presidente de Junta de Freguesia", "António Costa", "FAMILY");
        ensureAssociate(dataset, seen, "Diogo Lacerda Machado", "Proche associé et conseiller d'affaires (Affaire Influencer)", "António Costa", "ASSOCIATE");
        ensureAssociate(dataset, seen, "Vítor Escária", "Antigo Chefe de Gabinete do Primeiro-Ministro", "António Costa", "ASSOCIATE");

        // Parameters if supplied
        if (ctx != null && ctx.parameters() != null) {
            String extraAssociatesJson = ctx.parameters().get("associates");
            if (extraAssociatesJson != null && !extraAssociatesJson.isBlank()) {
                try {
                    JsonNode extra = json.readTree(extraAssociatesJson);
                    JsonNode list = extra.isArray() ? extra : extra.path("associates");
                    if (list.isArray()) {
                        for (JsonNode a : list) {
                            String name = a.path("name").asText("").trim();
                            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                                continue;
                            }
                            String rel = a.path("relationship").asText("Proche d'une PEP");
                            String target = a.path("targetPep").asText("PEP");
                            String cat = a.path("category").asText("FAMILY");
                            addAssociate(dataset, name, rel, target, cat);
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Impossible de parser le paramètre associates : {}", e.getMessage());
                }
            }
        }

        return count;
    }

    private void ensureAssociate(FtmDatasetBuilder dataset, Set<String> seen, String name, String relationship, String targetPep, String category) {
        if (!seen.add(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        addAssociate(dataset, name, relationship, targetPep, category);
    }

    private void addAssociate(FtmDatasetBuilder dataset, String name, String relationship, String targetPep, String category) {
        FtmEntity person = dataset.newEntity("Person", name);
        person.addValue("name", name);
        person.addValue("country", COUNTRY);
        person.addValue("nationality", COUNTRY);
        person.addValue("summary", relationship);
        person.addValue("sourceUrl", "https://www.tribunalconstitucional.pt/tc/transparencia.html");
        dataset.addEntity(person);

        FtmEntity target = dataset.newEntity("Person", targetPep);
        target.addValue("name", targetPep);
        target.addValue("country", COUNTRY);
        dataset.addEntity(target);

        if ("FAMILY".equalsIgnoreCase(category)) {
            FtmEntity edge = dataset.newEntity("Family", "rel-" + person.getId() + "-" + target.getId());
            edge.addValue("person", target.getId());
            edge.addValue("relative", person.getId());
            edge.addValue("relationship", relationship);
            dataset.addEntity(edge);
        } else {
            FtmEntity edge = dataset.newEntity("Associate", "rel-" + person.getId() + "-" + target.getId());
            edge.addValue("person", target.getId());
            edge.addValue("associate", person.getId());
            edge.addValue("relationship", relationship);
            dataset.addEntity(edge);
        }
    }

    private String cleanWiki(String text) {
        if (text == null) return "";
        String s = text.replaceAll("<ref[^>]*>.*?</ref>", "")
                .replaceAll("<ref[^>]*/>", "")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\{\\{[^\\}]+\\}\\}", "");
        Matcher m = WIKI_LINK_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String linkTarget = m.group(1);
            String linkText = m.group(2) != null ? m.group(2) : linkTarget;
            m.appendReplacement(sb, Matcher.quoteReplacement(linkText));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("(?i)\\s*\\(\\d{4}.*", "").trim();
    }
}
