package com.swiftcheck.aml.plugins.ptpep.enterprises;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <b>pt_pep_state_enterprises</b> – Dirigeants des entreprises publiques stratégiques de l'État portugais (Setor Empresarial do Estado).
 *
 * <p>Couvre la catégorie <b>PT-f</b>, fonction <b>Dirigeant d'un institut public, d'une fondation publique ou d'une entreprise publique</b> (fonction 265)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia entreprises publiques portugaises).
 */
@Extension
public class PtPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepStateEnterprisesParser.class);

    static final String ID_PREFIX = "pt-pep-state-enterprises";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String JSON_FILE = "pt-state-enterprises-wikipedia.json";

    static final String DEFAULT_TITLES = "Caixa_Geral_de_Dep\u00f3sitos|"
            + "TAP_Air_Portugal|"
            + "Comboios_de_Portugal|"
            + "Infraestruturas_de_Portugal|"
            + "R\u00e1dio_e_Televis\u00e3o_de_Portugal|"
            + "\u00c1guas_de_Portugal|"
            + "Parp\u00fablica|"
            + "Navega\u00e7\u00e3o_A\u00e9rea_de_Portugal|"
            + "Docapesca|"
            + "Lusa_(ag\u00eancia_de_not\u00edcias)|"
            + "Transtejo_Soflusa|"
            + "Transportes_Metropolitanos_de_Lisboa|"
            + "Arsenal_do_Alfeite|"
            + "Teatro_Nacional_D._Maria_II|"
            + "Teatro_Nacional_S\u00e3o_Jo\u00e3o";

    private static final Pattern REF_PATTERN = Pattern.compile("(?i)<ref[^>]*>.*?</ref>|<ref[^>]*/>|<ref[^>]*>");
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{[^{}]*\\}\\}");
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");
    private static final Pattern EXT_LINK_PATTERN = Pattern.compile("\\[https?://[^\\s\\]]+(?:\\s+([^\\]]+))?\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public PtPepStateEnterprisesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    PtPepStateEnterprisesParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants des entreprises publiques d'\u00c9tat du Portugal index\u00e9s.", count)
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
                        "https://pt.wikipedia.org/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target, ParserContext ctx) throws IOException, InterruptedException {
        String titlesParam = null;
        if (ctx != null && ctx.parameters() != null) {
            titlesParam = ctx.parameters().get("titles");
        }
        String titles = (titlesParam != null && !titlesParam.isBlank()) ? titlesParam : DEFAULT_TITLES;

        String encodedTitles = URLEncoder.encode(titles, StandardCharsets.UTF_8).replace("%7C", "|");
        String url = "https://pt.wikipedia.org/w/api.php?action=query&prop=revisions&rvprop=content&rvslots=main&titles="
                + encodedTitles + "&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        log.info("Interrogation MediaWiki pour les entreprises publiques portugaises : {}", url);
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 200) {
            Files.writeString(target, resp.body(), StandardCharsets.UTF_8);
            log.info("Donn\u00e9es MediaWiki enregistr\u00e9es dans {}", target);
        } else {
            throw new IOException("MediaWiki API a r\u00e9pondu avec le statut " + resp.statusCode());
        }
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pagesNode = root.path("query").path("pages");
        if (pagesNode.isMissingNode() || !pagesNode.isObject()) {
            throw new IllegalStateException("Format de r\u00e9ponse MediaWiki inattendu dans : " + rawFile);
        }

        int count = 0;
        Set<String> seenPersons = new HashSet<>();

        for (JsonNode page : pagesNode) {
            String title = page.path("title").asText("");
            JsonNode revisions = page.path("revisions");
            if (!revisions.isArray() || revisions.isEmpty()) {
                continue;
            }
            String wikitext = revisions.get(0).path("slots").path("main").path("*").asText("");
            if (wikitext.isEmpty()) {
                continue;
            }

            String companyName = cleanTitleToCompany(title);
            String pageUrl = "https://pt.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8);

            List<RawExecutive> rawList = extractExecutives(wikitext, companyName);
            for (RawExecutive exec : rawList) {
                String name = cleanName(exec.name);
                if (name.isEmpty() || name.length() < 3 || seenPersons.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                seenPersons.add(name.toLowerCase(Locale.ROOT));

                String role = exec.role.isEmpty() ? "Dirigente de " + companyName : exec.role;

                FtmEntity person = dataset.newEntity("Person", name);
                person.addValue("name", name);
                String[] nameParts = splitName(name);
                if (!nameParts[0].isEmpty()) {
                    person.addValue("firstName", nameParts[0]);
                }
                if (!nameParts[1].isEmpty()) {
                    person.addValue("lastName", nameParts[1]);
                }
                person.addValue("country", COUNTRY);
                person.addValue("nationality", COUNTRY);
                person.addValue("position", role);
                person.addValue("sourceUrl", pageUrl);
                dataset.addEntity(person);

                String compId = "see-" + companyName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                FtmEntity compEntity = dataset.newEntity("Company", compId);
                compEntity.addValue("name", companyName);
                compEntity.addValue("country", COUNTRY);
                compEntity.addValue("jurisdiction", COUNTRY);
                dataset.addEntity(compEntity);

                String posId = "pos-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "-" + compId;
                FtmEntity posEntity = dataset.newEntity("Position", posId);
                posEntity.addValue("name", role);
                posEntity.addValue("country", COUNTRY);
                posEntity.addValue("organization", companyName);
                posEntity.addValue("topics", "gov.soe");
                posEntity.addValue("topics", "corp.governance");
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

    private static String cleanTitleToCompany(String title) {
        return title.replaceAll("\\s*\\([^)]*\\)", "").trim();
    }

    record RawExecutive(String name, String role) {}

    static List<RawExecutive> extractExecutives(String wikitext, String companyName) {
        List<RawExecutive> list = new ArrayList<>();
        Map<String, String> infobox = parseInfoboxFields(wikitext);

        for (Map.Entry<String, String> entry : infobox.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT).trim();
            String rawVal = entry.getValue();

            if (key.matches("principais_pessoas|presidente|director|diretor|ceo|chefe\\d*_nome")) {
                parseFieldValues(rawVal, key, companyName, list);
            }
        }
        return list;
    }

    private static Map<String, String> parseInfoboxFields(String wikitext) {
        Map<String, String> fields = new LinkedHashMap<>();
        int infoStart = wikitext.indexOf("{{Info/");
        if (infoStart == -1) {
            infoStart = wikitext.indexOf("{{info/");
        }
        if (infoStart == -1) {
            return fields;
        }

        int pos = infoStart + 7;
        int depth = 1;
        int fieldStart = -1;
        String currentKey = null;

        while (pos < wikitext.length() && depth > 0) {
            char c = wikitext.charAt(pos);
            if (c == '{' && pos + 1 < wikitext.length() && wikitext.charAt(pos + 1) == '{') {
                depth++;
                pos += 2;
                continue;
            }
            if (c == '}' && pos + 1 < wikitext.length() && wikitext.charAt(pos + 1) == '}') {
                depth--;
                if (depth == 0) {
                    if (currentKey != null && fieldStart != -1) {
                        fields.put(currentKey, wikitext.substring(fieldStart, pos).trim());
                    }
                    break;
                }
                pos += 2;
                continue;
            }

            if (depth == 1 && c == '|') {
                if (currentKey != null && fieldStart != -1) {
                    fields.put(currentKey, wikitext.substring(fieldStart, pos).trim());
                }
                int eq = wikitext.indexOf('=', pos);
                int nextPipe = wikitext.indexOf('|', pos + 1);
                int nextClose = wikitext.indexOf("}}", pos + 1);
                int limit = nextPipe != -1 ? (nextClose != -1 ? Math.min(nextPipe, nextClose) : nextPipe) : nextClose;

                if (eq != -1 && (limit == -1 || eq < limit)) {
                    currentKey = wikitext.substring(pos + 1, eq).trim();
                    pos = eq + 1;
                    fieldStart = pos;
                    continue;
                }
            }
            pos++;
        }

        return fields;
    }

    private static void parseFieldValues(String rawVal, String fieldKey, String companyName, List<RawExecutive> list) {
        String cleaned = cleanWikiMarkup(rawVal);
        String[] lines = cleaned.split("[\\r\\n]+");

        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("Conselho de Administra") || l.equalsIgnoreCase("Org\u00e3os Sociais")) {
                continue;
            }

            // Handle comma or " e " lists like: Afonso Oliveira (Presidente), Márcia Passos e Isabel Esteves (Vogais)
            if (l.contains(",") || l.contains(" e ")) {
                String[] segments = l.split("(?i),|(?<=\\))\\s*e\\s+");
                for (String seg : segments) {
                    addExecutiveSegment(seg.trim(), fieldKey, companyName, list);
                }
            } else {
                addExecutiveSegment(l, fieldKey, companyName, list);
            }
        }
    }

    private static void addExecutiveSegment(String seg, String fieldKey, String companyName, List<RawExecutive> list) {
        String s = seg.replaceAll("^[\\-*#•]\\s*", "").trim();
        if (s.isEmpty()) {
            return;
        }

        String name = s;
        String role = "";

        // Check parentheses e.g. "Rui Vilar (Presidente Não Executivo)"
        int pOpen = s.indexOf('(');
        int pClose = s.lastIndexOf(')');
        if (pOpen != -1 && pClose > pOpen) {
            name = s.substring(0, pOpen).trim();
            String inner = s.substring(pOpen + 1, pClose).trim();
            if (!inner.toLowerCase(Locale.ROOT).startsWith("desde") && !inner.matches("\\d{4}.*")) {
                role = inner + " da " + companyName;
            }
        } else if (s.contains(",")) {
            String[] parts = s.split(",", 2);
            name = parts[0].trim();
            role = parts[1].trim() + " da " + companyName;
        }

        if (role.isEmpty()) {
            if ("ceo".equalsIgnoreCase(fieldKey)) {
                role = "CEO da " + companyName;
            } else if ("diretor".equalsIgnoreCase(fieldKey) || "director".equalsIgnoreCase(fieldKey)) {
                role = "Diretor da " + companyName;
            } else {
                role = "Presidente do Conselho de Administra\u00e7\u00e3o da " + companyName;
            }
        }

        name = cleanName(name);
        if (!name.isEmpty() && name.length() > 3 && !name.contains("http") && !name.toLowerCase(Locale.ROOT).startsWith("conselho")) {
            list.add(new RawExecutive(name, role));
        }
    }

    private static String cleanWikiMarkup(String text) {
        String t = REF_PATTERN.matcher(text).replaceAll("");
        // Remove nested templates iteratively
        while (TEMPLATE_PATTERN.matcher(t).find()) {
            t = TEMPLATE_PATTERN.matcher(t).replaceAll("");
        }
        t = t.replaceAll("(?i)<small>[^<]*</small>", "");
        t = t.replaceAll("(?i)<br\\s*/?>", "\n");
        t = EXT_LINK_PATTERN.matcher(t).replaceAll(m -> m.group(1) != null ? m.group(1) : "");
        t = WIKI_LINK_PATTERN.matcher(t).replaceAll("$1");
        return t;
    }

    private static String cleanName(String name) {
        String n = name.replaceAll("(?i)^Almirante\\s+", "");
        n = n.replaceAll("[\\(\\)\\[\\]\\{\\}\\\"']", "");
        n = n.replaceAll("\\s+", " ").trim();
        return n;
    }

    private static String[] splitName(String fullName) {
        String[] parts = fullName.split("\\s+");
        if (parts.length == 1) {
            return new String[]{parts[0], ""};
        }
        return new String[]{parts[0], parts[parts.length - 1]};
    }
}
