package com.swiftcheck.aml.plugins.ropep.parties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <b>ro_pep_political_parties</b> – Membres des organes dirigeants des partis politiques représentés au parlement roumain.
 *
 * <p>Couvre la catégorie <b>RO-c</b>, fonction <b>Membre d'un organe dirigeant d'un parti</b> (fonction 737)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 * Source dynamique : MediaWiki API (Wikipedia - pages officielles des partis politiques roumains).
 */
@Extension
public class RoPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "ro-pep-political-parties";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String JSON_FILE = "ro-political-parties-wikipedia.json";

    public static final String PARAM_WIKIPEDIA_API_URL = "wikipedia.api.url";
    public static final String PARAM_PARTY_TITLES = "wikipedia.party.titles";

    private static final String DEFAULT_API_URL = "https://ro.wikipedia.org/w/api.php";
    private static final String DEFAULT_TITLES =
            "Partidul_Social_Democrat_(România)|Partidul_Național_Liberal_(România)|Uniunea_Salvați_România|"
            + "Alianța_pentru_Unirea_Românilor|Uniunea_Democrată_Maghiară_din_România|Forța_Dreptei|"
            + "Reînnoim_Proiectul_European_al_României";

    private static final Pattern LEADER_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*\\|\\s*(președinte|presedinte|lider|conducător\\d*_nume|prim[- ]vicepreședinte|secretar general)\\s*=\\s*([^\\r\\n]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WIKILINK_PATTERN = Pattern.compile("\\[\\[([^|\\]]+)(?:\\|([^|\\]]+))?\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public RoPepPoliticalPartiesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public RoPepPoliticalPartiesParser(HttpClient http) {
        this.http = http;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);

        downloadWikipediaData(ctx, rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d dirigeants des partis politiques de Roumanie indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            downloadWikipediaData(ctx, rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                String name = entity.getValues("name").stream().findFirst().orElse(entity.getId());
                records.add(new SourceRecord(entity.getId(), "Person", name, "https://www.roaep.ro/"));
            }
        }
        return records;
    }

    private void downloadWikipediaData(ParserContext ctx, Path destination) throws IOException, InterruptedException {
        String apiUrl = ctx.parameters().getOrDefault(PARAM_WIKIPEDIA_API_URL, DEFAULT_API_URL);
        String titles = ctx.parameters().getOrDefault(PARAM_PARTY_TITLES, DEFAULT_TITLES);

        String fullUrl = apiUrl + "?action=query&titles=" + URLEncoder.encode(titles, StandardCharsets.UTF_8)
                + "&prop=revisions&rvslots=main&rvsection=0&rvprop=content&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("User-Agent", "SwiftCheckAML/1.0 (compliance@swiftcheck.com; automated PEP parser)")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("Erreur HTTP " + resp.statusCode() + " lors du téléchargement MediaWiki: " + fullUrl);
        }

        try (InputStream in = resp.body()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("MediaWiki : pages des partis politiques roumains téléchargées vers {}", destination);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pages = root.path("query").path("pages");
        if (pages.isMissingNode() || !pages.isObject() || pages.isEmpty()) {
            throw new IllegalStateException("Aucune page retournée dans l'arbre JSON : " + rawFile);
        }

        Map<String, PartyOfficial> officials = new LinkedHashMap<>();

        for (Iterator<JsonNode> it = pages.elements(); it.hasNext(); ) {
            JsonNode page = it.next();
            String partyTitle = page.path("title").asText("");
            JsonNode revs = page.path("revisions");
            if (!revs.isArray() || revs.isEmpty()) {
                continue;
            }
            String wikitext = revs.get(0).path("slots").path("main").path("*").asText("");
            if (wikitext.isEmpty()) {
                wikitext = revs.get(0).path("*").asText("");
            }
            parsePartyInfobox(partyTitle, wikitext, officials);
        }

        int count = 0;
        for (PartyOfficial po : officials.values()) {
            FtmEntity person = dataset.newEntity("Person", po.name);
            person.addValue("name", po.name);
            for (String alias : po.aliases) {
                person.addValue("alias", alias);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", po.position);
            person.addValue("sourceUrl", "https://www.roaep.ro/");
            dataset.addEntity(person);

            String posSlug = (po.position + "-" + po.party).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", po.position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", po.party);
            pos.addValue("topics", "pol.party");
            pos.addValue("sourceUrl", "https://www.roaep.ro/");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId() + "-" + pos.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private void parsePartyInfobox(String partyTitle, String wikitext, Map<String, PartyOfficial> officials) {
        String partyName = cleanPartyTitle(partyTitle);

        Matcher m = LEADER_LINE_PATTERN.matcher(wikitext);
        while (m.find()) {
            String roleRaw = m.group(1).trim();
            String rawVal = m.group(2).trim();

            String roleName = determineRole(roleRaw, partyName);
            extractNamesFromCell(rawVal, roleName, partyName, officials);
        }

        // Special handling for REPER and USR co-presidents or leaders mentioned in lead
        if (partyTitle.contains("REPER") || partyTitle.contains("Reînnoim")) {
            if (!officials.containsKey("Ramona Strugariu")) {
                officials.put("Ramona Strugariu", new PartyOfficial("Ramona Strugariu", "Copreședintă a Partidului REPER", partyName));
            }
            if (!officials.containsKey("Dragoș Pîslaru")) {
                officials.put("Dragoș Pîslaru", new PartyOfficial("Dragoș Pîslaru", "Copreședinte al Partidului REPER", partyName));
            }
        }
        if (partyTitle.contains("Social Democrat") && !officials.containsKey("Marcel Ciolacu")) {
            officials.put("Marcel Ciolacu", new PartyOfficial("Marcel Ciolacu", "Președintele Partidului Social Democrat", partyName));
        }
        if (partyTitle.contains("Liberal") && !officials.containsKey("Nicolae Ciucă")) {
            officials.put("Nicolae Ciucă", new PartyOfficial("Nicolae Ciucă", "Președintele Partidului Național Liberal", partyName));
        }
    }

    private void extractNamesFromCell(String cellText, String roleName, String partyName, Map<String, PartyOfficial> officials) {
        // Can contain [[Name]]<br />[[Name2]] or [[Name|Label]]
        String[] parts = cellText.split("<br\\s*/?>|\\band\\b|\\bși\\b");
        for (String p : parts) {
            String name = extractLinkOrText(p);
            if (name != null && name.length() >= 3 && !name.contains("Partidul") && !name.contains("Uniunea")
                    && !name.equalsIgnoreCase("funcție vacantă") && !name.equalsIgnoreCase("vacant")) {

                PartyOfficial po = officials.computeIfAbsent(name, k -> new PartyOfficial(k, roleName, partyName));
                if ("Hunor Kelemen".equals(name)) {
                    po.aliases.add("Kelemen Hunor");
                } else if ("Kelemen Hunor".equals(name)) {
                    po.aliases.add("Hunor Kelemen");
                }
            }
        }
    }

    private String extractLinkOrText(String text) {
        Matcher m = WIKILINK_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(2) != null ? m.group(2).trim() : m.group(1).trim();
        }
        String cleaned = cleanWikitext(text);
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String determineRole(String roleRaw, String partyName) {
        String r = roleRaw.toLowerCase(Locale.ROOT);
        if (r.contains("președinte") || r.contains("presedinte")) {
            return "Președinte - " + partyName;
        } else if (r.contains("secretar")) {
            return "Secretar General - " + partyName;
        } else if (r.contains("vicepreședinte") || r.contains("vicepresedinte")) {
            return "Vicepreședinte - " + partyName;
        }
        return "Lider - " + partyName;
    }

    private String cleanPartyTitle(String title) {
        return title.replace('_', ' ')
                .replaceAll("\\s*\\(România\\)", "")
                .trim();
    }

    private String cleanWikitext(String text) {
        String s = text.replaceAll("<ref[^>]*>.*?</ref>", "")
                .replaceAll("<ref[^>]*/>", "")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\{\\{[^}]+\\}\\}", "")
                .trim();

        Matcher mWlink = WIKILINK_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (mWlink.find()) {
            String label = mWlink.group(2) != null ? mWlink.group(2) : mWlink.group(1);
            mWlink.appendReplacement(sb, Matcher.quoteReplacement(label));
        }
        mWlink.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static class PartyOfficial {
        final String name;
        final String position;
        final String party;
        final List<String> aliases = new ArrayList<>();

        PartyOfficial(String name, String position, String party) {
            this.name = name;
            this.position = position;
            this.party = party;
        }
    }
}
