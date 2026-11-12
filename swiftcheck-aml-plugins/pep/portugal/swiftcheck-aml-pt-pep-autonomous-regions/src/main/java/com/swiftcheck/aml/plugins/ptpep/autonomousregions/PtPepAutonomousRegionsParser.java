package com.swiftcheck.aml.plugins.ptpep.autonomousregions;

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
 * <b>pt_pep_autonomous_regions</b> – Membres des gouvernements régionaux et représentants de la République aux Açores et à Madère.
 *
 * <p>Couvre la catégorie <b>PT-REG</b>, fonction <b>Représentant de la République et membres des gouvernements des Açores et de Madère</b> (fonction 261)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Extraction dynamique en direct via l'API MediaWiki (Wikipedia Açores et Madère).
 */
@Extension
public class PtPepAutonomousRegionsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepAutonomousRegionsParser.class);

    static final String ID_PREFIX = "pt-pep-autonomous-regions";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String JSON_FILE = "pt-autonomous-regions.json";

    static final String[] WIKI_REGION_PAGES = {
            "XIV_Governo_Regional_dos_A\u00e7ores",
            "XVI_Governo_Regional_da_Madeira"
    };

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\|\\]]+)(?:\\|([^\\]]+))?\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PtPepAutonomousRegionsParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PtPepAutonomousRegionsParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants des gouvernements des Açores et de Madère indexés.", count)
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
                        "https://portal.azores.gov.pt/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        List<JsonNode> pages = new ArrayList<>();
        for (String page : WIKI_REGION_PAGES) {
            String url = "https://pt.wikipedia.org/w/api.php?action=parse&page=" + page + "&prop=wikitext&format=json";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            log.info("Interrogation MediaWiki pour le gouvernement régional : {}", page);
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

            boolean isMadeira = title.toLowerCase(Locale.ROOT).contains("madeira");
            String region = isMadeira ? "Madeira" : "Açores";

            List<MemberRecord> members = parseMembers(wikitext, isMadeira);
            for (MemberRecord m : members) {
                if (m.name.isEmpty() || !seen.add(m.name.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                FtmEntity person = dataset.newEntity("Person", m.name);
                person.addValue("name", m.name);
                person.addValue("country", COUNTRY);
                person.addValue("nationality", COUNTRY);
                person.addValue("position", m.position);
                person.addValue("sourceUrl", "https://pt.wikipedia.org/wiki/" + title.replaceAll(" ", "_"));
                dataset.addEntity(person);

                FtmEntity posEntity = dataset.newEntity("Position", "pt-reg-" + person.getId());
                posEntity.addValue("name", m.position);
                posEntity.addValue("country", COUNTRY);
                posEntity.addValue("organization", "Governo Regional dos " + region);
                posEntity.addValue("topics", "gov.regional");
                posEntity.addValue("topics", "gov.national");
                dataset.addEntity(posEntity);

                FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", posEntity.getId());
                occupancy.addValue("status", "current");
                dataset.addEntity(occupancy);

                count++;
            }
        }

        // Add Representantes da República if not yet present
        addRepresentante(dataset, seen, "Pedro Catarino", "Representante da República para os Açores", "Governo Regional dos Açores");
        addRepresentante(dataset, seen, "Ireneu Barreto", "Representante da República para a Madeira", "Governo Regional da Madeira");

        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).mapToInt(e -> 1).sum();
    }

    private void addRepresentante(FtmDatasetBuilder dataset, Set<String> seen, String name, String posName, String org) {
        if (!seen.add(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        FtmEntity person = dataset.newEntity("Person", name);
        person.addValue("name", name);
        person.addValue("country", COUNTRY);
        person.addValue("nationality", COUNTRY);
        person.addValue("position", posName);
        person.addValue("sourceUrl", "https://www.presidencia.pt/");
        dataset.addEntity(person);

        FtmEntity posEntity = dataset.newEntity("Position", "pt-reg-" + person.getId());
        posEntity.addValue("name", posName);
        posEntity.addValue("country", COUNTRY);
        posEntity.addValue("organization", org);
        posEntity.addValue("topics", "gov.regional");
        posEntity.addValue("topics", "gov.national");
        dataset.addEntity(posEntity);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", posEntity.getId());
        occupancy.addValue("status", "current");
        dataset.addEntity(occupancy);
    }

    private List<MemberRecord> parseMembers(String wikitext, boolean isMadeira) {
        List<MemberRecord> result = new ArrayList<>();
        String[] rows = wikitext.split("\\|-");
        for (int i = 1; i < rows.length; i++) {
            String row = rows[i].trim();
            String[] rawLines = row.split("\n");
            List<String> cells = new ArrayList<>();
            for (String l : rawLines) {
                l = l.trim();
                if (l.startsWith("|")) {
                    String clean = l.replaceFirst("^\\|\\s*", "");
                    if (!clean.startsWith("width=") && !clean.startsWith("colspan=") && !clean.startsWith("bgcolor=")) {
                        cells.add(clean);
                    }
                }
            }

            if (cells.size() >= 2) {
                String position;
                String name;
                if (isMadeira && cells.size() >= 3 && cells.get(0).toLowerCase(Locale.ROOT).contains("ficheiro:")) {
                    position = cleanWiki(cells.get(1));
                    name = cleanWiki(cells.get(2));
                } else {
                    position = cleanWiki(cells.get(0));
                    name = cleanWiki(cells.get(1));
                }

                if (name.contains("Ficheiro:") || name.contains("wikitable") || name.length() < 3 || position.isEmpty()) {
                    continue;
                }
                // Strip dates or annotations from name
                name = name.replaceAll("(?i)(?:\\d{1,2}\\s+de\\s+[a-zç]+\\s+de\\s+\\d{4}|presente|interinamente|–|-).*", "").trim();
                position = position.replaceAll("rowspan=\"\\d+\"\\s*\\|\\s*", "").trim();

                if (!name.isEmpty() && name.length() > 3 && !name.contains("colspan")) {
                    result.add(new MemberRecord(name, position));
                }
            }
        }
        return result;
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

    private static class MemberRecord {
        final String name;
        final String position;

        MemberRecord(String name, String position) {
            this.name = name;
            this.position = position;
        }
    }
}
