package com.swiftcheck.aml.plugins.ptpep.bank;

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
 * <b>pt_pep_bank_of_portugal</b> – Organes dirigeants de la Banque du Portugal.
 *
 * <p>Couvre la catégorie <b>PT-d</b>, fonction <b>Organes de la Banque du Portugal</b> (fonction 257)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia Banco de Portugal).
 */
@Extension
public class PtPepBankOfPortugalParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepBankOfPortugalParser.class);

    static final String ID_PREFIX = "pt-pep-bank-of-portugal";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String JSON_FILE = "pt-bank-of-portugal.json";
    static final String WIKI_PAGE = "Banco_de_Portugal";

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\|\\]]+)(?:\\|([^\\]]+))?\\]\\]");
    private static final Pattern YEARS_PATTERN = Pattern.compile("\\((\\d{4})[-–](\\d{4}|presente)\\)");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PtPepBankOfPortugalParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PtPepBankOfPortugalParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants du Banco de Portugal indexés.", count)
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
                        "https://pt.wikipedia.org/wiki/Banco_de_Portugal"));
            }
        }
        return records;
    }

    private void fetchLive(Path target) throws IOException, InterruptedException {
        String url = "https://pt.wikipedia.org/w/api.php?action=parse&page=" + WIKI_PAGE + "&prop=wikitext&format=json";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        log.info("Interrogation MediaWiki pour le Banco de Portugal");
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 200) {
            Files.writeString(target, resp.body(), StandardCharsets.UTF_8);
        } else {
            throw new IOException("Erreur HTTP " + resp.statusCode() + " lors de la récupération de " + url);
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        return convert(rawFile, dataset, null);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset, ParserContext ctx) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        String wikitext = root.path("parse").path("wikitext").path("*").asText("");

        Set<String> seen = new HashSet<>();
        int count = 0;

        // 1. Current governor from infobox
        Matcher execMatcher = Pattern.compile("(?m)^\\s*\\|\\s*executivo\\s*=\\s*(.+)$").matcher(wikitext);
        if (execMatcher.find()) {
            String currentGov = cleanWiki(execMatcher.group(1));
            if (!currentGov.isEmpty() && seen.add(currentGov.toLowerCase(Locale.ROOT))) {
                addGovernor(dataset, currentGov, "Governador do Banco de Portugal", "current", null, null);
                count++;
            }
        }

        // 2. Governors from historical table
        String[] rows = wikitext.split("\\|-");
        for (String row : rows) {
            String[] rawLines = row.split("\n");
            List<String> cells = new ArrayList<>();
            for (String l : rawLines) {
                l = l.trim();
                if (l.startsWith("|")) {
                    cells.add(l.replaceFirst("^\\|\\s*", ""));
                }
            }

            // Each row contains 1 or 2 entries: [index1, name1, period1, index2, name2, period2]
            if (cells.size() >= 3 && isNumeric(cells.get(0))) {
                count += processGovEntry(dataset, seen, cells.get(1), cells.get(2));
            }
            if (cells.size() >= 6 && isNumeric(cells.get(3))) {
                count += processGovEntry(dataset, seen, cells.get(4), cells.get(5));
            }
        }

        // 3. Optional extra members passed via parameters (e.g. from central administration or DB)
        if (ctx != null && ctx.parameters() != null) {
            String extraMembersJson = ctx.parameters().get("bankMembers");
            if (extraMembersJson != null && !extraMembersJson.isBlank()) {
                try {
                    JsonNode extra = json.readTree(extraMembersJson);
                    JsonNode members = extra.isArray() ? extra : extra.path("members");
                    if (members.isArray()) {
                        for (JsonNode m : members) {
                            String name = m.path("name").asText("").trim();
                            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                                continue;
                            }
                            String position = m.path("position").asText("Membro do Conselho de Administração");
                            String since = m.path("since").asText("");
                            addGovernor(dataset, name, position, position.startsWith("Antig") ? "ended" : "current", since, null);
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Impossible de parser le paramètre bankMembers : {}", e.getMessage());
                }
            }
        }

        return count;
    }

    private int processGovEntry(FtmDatasetBuilder dataset, Set<String> seen, String rawName, String rawPeriod) {
        String name = cleanWiki(rawName).replaceAll("(?i)\\s*\\(interino\\)", "").trim();
        if (name.isEmpty() || name.length() < 3 || name.contains("colspan")) {
            return 0;
        }

        if (!seen.add(name.toLowerCase(Locale.ROOT))) {
            return 0;
        }

        String startYear = null;
        String endYear = null;
        boolean isCurrent = false;

        Matcher pm = YEARS_PATTERN.matcher(rawPeriod);
        if (pm.find()) {
            startYear = pm.group(1);
            String end = pm.group(2);
            if ("presente".equalsIgnoreCase(end)) {
                isCurrent = true;
            } else {
                endYear = end;
            }
        }

        String position = isCurrent ? "Governador do Banco de Portugal" : "Antigo Governador do Banco de Portugal";
        String status = isCurrent ? "current" : "ended";

        addGovernor(dataset, name, position, status, startYear, endYear);
        return 1;
    }

    private void addGovernor(FtmDatasetBuilder dataset, String name, String position, String status, String startYear, String endYear) {
        FtmEntity person = dataset.newEntity("Person", name);
        person.addValue("name", name);
        person.addValue("country", COUNTRY);
        person.addValue("nationality", COUNTRY);
        person.addValue("position", position);
        person.addValue("sourceUrl", "https://pt.wikipedia.org/wiki/Banco_de_Portugal");
        dataset.addEntity(person);

        FtmEntity posEntity = dataset.newEntity("Position", "bdp-" + person.getId());
        posEntity.addValue("name", position);
        posEntity.addValue("country", COUNTRY);
        posEntity.addValue("organization", "Banco de Portugal");
        posEntity.addValue("topics", "gov.financial");
        posEntity.addValue("topics", "gov.national");
        dataset.addEntity(posEntity);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", posEntity.getId());
        occupancy.addValue("status", status);
        if (startYear != null && !startYear.isEmpty()) {
            occupancy.addValue("startDate", startYear);
        }
        if (endYear != null && !endYear.isEmpty()) {
            occupancy.addValue("endDate", endYear);
        }
        dataset.addEntity(occupancy);
    }

    private boolean isNumeric(String s) {
        return s != null && s.trim().matches("\\d+");
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
