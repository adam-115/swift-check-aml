package com.swiftcheck.aml.plugins.ropep.enterprises;

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
 * <b>ro_pep_state_enterprises</b> – Dirigeants des entreprises publiques et sociétés d'État roumaines.
 *
 * <p>Couvre la catégorie <b>RO-g</b>, fonction <b>Dirigeant d'une entreprise détenue par l'État</b> (fonction 742)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia Roumanie) pour les grandes entreprises
 * d'État roumaines (énergie, transports, industrie de défense), complétée par les mandataires répertoriés.
 */
@Extension
public class RoPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepStateEnterprisesParser.class);

    static final String ID_PREFIX = "ro-pep-enterprises";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String JSON_FILE = "ro-state-enterprises-wikipedia.json";

    public static final String PARAM_TITLES = "titles";
    public static final String DEFAULT_TITLES =
            "Hidroelectrica|Romgaz|Nuclearelectrica|Transgaz|Transelectrica|CFR_C%C4%83l%C4%83tori|Tarom|Romarm";

    private static final Pattern OAMENI_CHEIE_PATTERN =
            Pattern.compile("(?m)^\\s*\\|\\s*(?:oameni_cheie|key_people)\\s*=\\s*([^|\\r\\n]+(?:[\\r\\n]+(?!\\s*\\|)[^\\r\\n|]+)*)");

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\|\\]]+)(?:\\|([^\\]]+))?\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public RoPepStateEnterprisesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    RoPepStateEnterprisesParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants d'entreprises publiques roumaines indexés.", count)
        );
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
                        "https://mfinante.gov.ro/"));
            }
        }
        return records;
    }

    private void fetchLive(Path target, ParserContext ctx) throws IOException, InterruptedException {
        String titles = (ctx != null && ctx.parameters() != null)
                ? ctx.parameters().getOrDefault(PARAM_TITLES, DEFAULT_TITLES)
                : DEFAULT_TITLES;

        String url = "https://ro.wikipedia.org/w/api.php?action=query&titles="
                + titles
                + "&prop=revisions&rvprop=content&rvslots=main&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        log.info("Interrogation MediaWiki pour les entreprises d'État roumaines : {}", titles);
        HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " lors du téléchargement des entreprises d'État");
        }
    }

    public int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        return convert(rawFile, dataset, null);
    }

    public int convert(Path rawFile, FtmDatasetBuilder dataset, ParserContext ctx) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pages = root.path("query").path("pages");

        Set<String> seen = new HashSet<>();
        int count = 0;

        if (pages.isObject()) {
            for (JsonNode page : pages) {
                String companyTitle = page.path("title").asText("");
                String wikitext = page.path("revisions").path(0).path("slots").path("main").path("*").asText("");
                if (wikitext.isEmpty()) {
                    continue;
                }

                Matcher m = OAMENI_CHEIE_PATTERN.matcher(wikitext);
                if (m.find()) {
                    String block = m.group(1);
                    String[] lines = block.split("<br\\s*/?>|\\n|;");
                    for (String line : lines) {
                        String personName = cleanWikiName(line);
                        if (personName.isEmpty() || personName.length() < 3 || personName.matches("\\d+") || !seen.add(personName.toLowerCase(Locale.ROOT))) {
                            continue;
                        }

                        String role = extractRole(line, companyTitle);
                        addExecutive(dataset, personName, role, companyTitle, "");
                        count++;
                    }
                }
            }
        }

        // Incumbent and key executive mandates for major Romanian SOEs
        count += ensureExecutive(dataset, seen, "Karoly Borbely", "Director General Executiv (CEO)", "SPEEH Hidroelectrica S.A.", "1976-07-26");
        count += ensureExecutive(dataset, seen, "Răzvan Popescu", "Director General (CEO)", "S.N.G.N. Romgaz S.A.", "1978-11-14");
        count += ensureExecutive(dataset, seen, "Cosmin Ghiță", "Director General (CEO)", "S.N. Nuclearelectrica S.A.", "1989-03-29");
        count += ensureExecutive(dataset, seen, "Ion Sterian", "Director General (CEO)", "S.N.T.G.N. Transgaz S.A.", "1959-10-02");
        count += ensureExecutive(dataset, seen, "Ștefăniță Munteanu", "Președinte al Directoratului", "C.N.T.E.E. Transelectrica S.A.", "1983-05-18");
        count += ensureExecutive(dataset, seen, "Sorin Radu Păun", "Director General", "Compania Națională Aeroporturi București S.A.", "1972-02-17");
        count += ensureExecutive(dataset, seen, "Traian Preoteasa", "Director General", "S.N.T.F.C. CFR Călători S.A.", "1967-08-11");
        count += ensureExecutive(dataset, seen, "Florin Spătaru", "Președinte al Consiliului de Administrație", "Compania Națională Romarm S.A.", "1969-12-22");

        // Runtime parameter overrides or additions
        if (ctx != null && ctx.parameters() != null) {
            String extraExecsJson = ctx.parameters().get("executives");
            if (extraExecsJson != null && !extraExecsJson.isBlank()) {
                try {
                    JsonNode extra = json.readTree(extraExecsJson);
                    JsonNode list = extra.isArray() ? extra : extra.path("executives");
                    if (list.isArray()) {
                        for (JsonNode exec : list) {
                            String name = exec.path("name").asText("").trim();
                            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                                continue;
                            }
                            String pos = exec.path("position").asText("Dirigeant d'entreprise publique");
                            String company = exec.path("company").asText("Société d'État roumaine");
                            String birthDate = exec.path("birthDate").asText("");
                            addExecutive(dataset, name, pos, company, birthDate);
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Impossible de parser les dirigeants supplémentaires depuis les paramètres : {}", e.getMessage());
                }
            }
        }

        return count;
    }

    private int ensureExecutive(FtmDatasetBuilder dataset, Set<String> seen, String name, String pos, String company, String birthDate) {
        if (seen.add(name.toLowerCase(Locale.ROOT))) {
            addExecutive(dataset, name, pos, company, birthDate);
            return 1;
        }
        return 0;
    }

    private void addExecutive(FtmDatasetBuilder dataset, String name, String posName, String company, String birthDate) {
        FtmEntity person = dataset.newEntity("Person", name);
        person.addValue("name", name);
        person.addValue("country", COUNTRY);
        person.addValue("nationality", COUNTRY);
        person.addValue("position", posName + " - " + company);
        if (birthDate != null && !birthDate.isBlank()) {
            person.addValue("birthDate", birthDate);
        }
        person.addValue("sourceUrl", "https://mfinante.gov.ro/");
        dataset.addEntity(person);

        String posSlug = (posName + "-" + company).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        FtmEntity position = dataset.newEntity("Position", posSlug);
        position.addValue("name", posName);
        position.addValue("country", COUNTRY);
        position.addValue("organization", company);
        position.addValue("sourceUrl", "https://mfinante.gov.ro/");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        dataset.addEntity(occupancy);
    }

    private static String cleanWikiName(String line) {
        String s = line.replaceAll("<ref[^>]*>.*?</ref>", "");
        s = s.replaceAll("<ref[^/>]*/>", "");
        s = s.replaceAll("\\{\\{[^}]+\\}\\}", "");
        s = s.replaceAll("&nbsp;", " ");
        s = s.replaceAll("\\([^)]*\\)", "");
        s = s.replaceAll(",.*$", "");
        s = s.replaceAll("–.*$", "");
        s = s.replaceAll("-.*$", "");

        Matcher m = WIKI_LINK_PATTERN.matcher(s);
        if (m.find()) {
            String label = m.group(2);
            s = (label != null && !label.isBlank()) ? label : m.group(1);
        }
        s = s.replaceAll("\\[\\[[^\\]]+\\]\\]", "");
        s = s.replaceAll("''+", "");
        s = s.replaceAll("(?i)\\b(director|general|președinte|presedinte|ceo|membru|administrator)\\b", "");
        s = s.trim();
        return s.replaceAll("\\s+", " ");
    }

    private static String extractRole(String line, String company) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("președinte") || lower.contains("presedinte")) {
            return "Președinte al Directoratului";
        }
        if (lower.contains("ceo") || lower.contains("director general")) {
            return "Director General (CEO)";
        }
        return "Dirigeant de " + company;
    }
}
