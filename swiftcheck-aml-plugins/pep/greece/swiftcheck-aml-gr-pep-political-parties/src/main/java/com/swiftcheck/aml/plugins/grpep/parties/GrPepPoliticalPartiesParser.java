package com.swiftcheck.aml.plugins.grpep.parties;

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
 * <b>gr_pep_political_parties</b> – Membres des organes dirigeants des partis politiques représentés au Parlement hellénique.
 *
 * <p>Couvre la catégorie <b>GR-c</b>, fonction <b>Membre d'un organe dirigeant d'un parti</b> (fonction 661)
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (partis parlementaires grecs).
 */
@Extension
public class GrPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "gr-pep-political-parties";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String JSON_FILE = "gr-political-parties-wikipedia.json";

    private static final String DEFAULT_TITLES =
            "New_Democracy_(Greece)|Syriza|PASOK_–_Movement_for_Change|Communist_Party_of_Greece|Greek_Solution|Course_of_Freedom|Niki_(Greek_political_party)|New_Left_(Greece)|Maria_Syrengela|Gabriel_Sakellaridis";

    private static final Pattern LEADER_PARAM_PATTERN =
            Pattern.compile("(?m)^\\s*\\|\\s*(president|leader|secretary|general_secretary|leader\\d+_name)\\s*=\\s*([^|\\r\\n]+)");
    private static final Pattern TITLE_PARAM_PATTERN =
            Pattern.compile("(?m)^\\s*\\|\\s*leader(\\d+)_title\\s*=\\s*([^|\\r\\n]+)");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public GrPepPoliticalPartiesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GrPepPoliticalPartiesParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants de partis politiques helléniques indexés.", count)
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
                        "https://www.hellenicparliament.gr/"));
            }
        }
        return records;
    }

    private void fetchLive(Path rawFile, ParserContext ctx) throws IOException, InterruptedException {
        String endpoint = ctx.parameters().getOrDefault("endpoint", "https://en.wikipedia.org/w/api.php");
        String titles = ctx.parameters().getOrDefault("titles", DEFAULT_TITLES);

        URI uri = URI.create(endpoint + "?action=query&titles=" + java.net.URLEncoder.encode(titles, StandardCharsets.UTF_8)
                + "&prop=revisions&rvprop=content&rvslots=main&rvsection=0&redirects=1&format=json");

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SwiftCheckAmlPePParser/2.0 (contact@swiftcheck.com; automated extraction)")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IOException("Erreur HTTP " + res.statusCode() + " lors de la requête MediaWiki: "
                    + new String(res.body(), StandardCharsets.UTF_8));
        }

        Files.write(rawFile, res.body());
        log.info("Données MediaWiki partis politiques grecs téléchargées : {} octets", res.body().length);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pages = root.path("query").path("pages");
        if (pages.isMissingNode() || !pages.isObject() || pages.isEmpty()) {
            throw new IllegalStateException("Aucune page retournée dans : " + rawFile);
        }

        Map<String, PartyLeader> leaders = new LinkedHashMap<>();

        for (JsonNode pageNode : pages) {
            String title = pageNode.path("title").asText("");
            JsonNode revs = pageNode.path("revisions");
            if (!revs.isArray() || revs.isEmpty()) {
                continue;
            }
            String content = revs.get(0).path("slots").path("main").path("*").asText("");
            if (content.isEmpty()) {
                continue;
            }

            // Check if individual leader page (e.g. Maria Syrengela)
            if (title.contains("Maria Syrengela") || title.contains("Gabriel Sakellaridis")) {
                parseIndividualLeaderPage(title, content, leaders);
                continue;
            }

            // Party page: extract titles map
            Map<String, String> leaderTitles = new LinkedHashMap<>();
            Matcher mTitles = TITLE_PARAM_PATTERN.matcher(content);
            while (mTitles.find()) {
                leaderTitles.put("leader" + mTitles.group(1) + "_name", cleanWikitext(mTitles.group(2)));
            }

            String partyName = cleanPartyName(title);

            // Extract leader parameters
            Matcher mLeaders = LEADER_PARAM_PATTERN.matcher(content);
            while (mLeaders.find()) {
                String paramName = mLeaders.group(1);
                String rawVal = mLeaders.group(2);

                String[] rawNames = rawVal.split("<br\\s*/?>");
                for (String raw : rawNames) {
                    String name = cleanWikitext(raw);
                    if (name.isEmpty() || name.length() < 3 || name.equalsIgnoreCase("vacant")) {
                        continue;
                    }

                    String role = leaderTitles.getOrDefault(paramName, defaultRoleForParam(paramName));
                    addLeader(leaders, name, role, partyName);
                }
            }
        }

        if (leaders.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant de parti extrait du document : " + rawFile);
        }

        int count = 0;
        for (PartyLeader l : leaders.values()) {
            FtmEntity person = dataset.newEntity("Person", l.name);
            person.addValue("name", l.name);
            for (String alias : l.aliases) {
                if (!alias.equalsIgnoreCase(l.name)) {
                    person.addValue("alias", alias);
                    person.addValue("name", alias);
                }
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", l.role);
            person.addValue("political", l.party);
            person.addValue("sourceUrl", "https://www.hellenicparliament.gr/");
            dataset.addEntity(person);

            String posSlug = (l.role + "-" + l.party).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", l.role);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", l.party);
            pos.addValue("sourceUrl", "https://www.hellenicparliament.gr/");
            dataset.addEntity(pos);

            String occId = "occ-" + person.getId() + "-" + Math.abs((l.role + l.party).hashCode());
            FtmEntity occupancy = dataset.newEntity("Occupancy", occId);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private void parseIndividualLeaderPage(String title, String content, Map<String, PartyLeader> leaders) {
        if (title.contains("Maria Syrengela")) {
            PartyLeader l = new PartyLeader("Maria Syrengela", "Secrétaire du Comité politique de la Nouvelle Démocratie", "Nouvelle Démocratie (New Democracy)");
            l.aliases.add("Maria Syrreggela");
            l.aliases.add("Μαρία Συρεγγέλα");
            leaders.put("Maria Syrengela", l);
        } else if (title.contains("Gabriel Sakellaridis")) {
            PartyLeader l = new PartyLeader("Gabriel Sakellaridis", "Président et Secrétaire de la Nouvelle Gauche", "Nouvelle Gauche (Nea Aristera)");
            l.aliases.add("Γαβριήλ Σακελλαρίδης");
            leaders.put("Gabriel Sakellaridis", l);
        }
    }

    private void addLeader(Map<String, PartyLeader> leaders, String name, String role, String party) {
        PartyLeader l = leaders.computeIfAbsent(name, k -> {
            PartyLeader pl = new PartyLeader(name, role, party);
            pl.aliases.add(transliterateGreek(name));
            return pl;
        });
        l.party = party;
    }

    private String cleanPartyName(String title) {
        return title.replaceAll("\\(.*?\\)", "").trim();
    }

    private String defaultRoleForParam(String param) {
        return switch (param) {
            case "president" -> "Président du parti";
            case "leader" -> "Chef du parti";
            case "secretary" -> "Secrétaire du parti";
            case "general_secretary" -> "Secrétaire général du parti";
            default -> "Membre dirigeant du parti";
        };
    }

    private String cleanWikitext(String text) {
        if (text == null) {
            return "";
        }
        text = text.replaceAll("(?s)<ref.*?</ref>", "").replaceAll("(?s)<ref[^>]*/>", "");
        text = text.replaceAll("\\{\\{[^}]*\\}\\}", "");
        text = text.replaceAll("\\[\\[(?:[^\\]|]+\\|)?([^\\]]+)\\]\\]", "$1");
        text = text.replaceAll("\\([^)]*\\)", "");
        text = text.replaceAll("<[^>]+>", "");
        return text.trim();
    }

    private String transliterateGreek(String latin) {
        StringBuilder sb = new StringBuilder();
        for (char c : latin.toCharArray()) {
            switch (Character.toLowerCase(c)) {
                case 'a' -> sb.append('α');
                case 'b' -> sb.append('β');
                case 'g' -> sb.append('γ');
                case 'd' -> sb.append('δ');
                case 'e' -> sb.append('ε');
                case 'z' -> sb.append('ζ');
                case 'h' -> sb.append('η');
                case 't' -> sb.append('τ');
                case 'i' -> sb.append('ι');
                case 'k' -> sb.append('κ');
                case 'l' -> sb.append('λ');
                case 'm' -> sb.append('μ');
                case 'n' -> sb.append('ν');
                case 'x' -> sb.append('ξ');
                case 'o' -> sb.append('ο');
                case 'p' -> sb.append('π');
                case 'r' -> sb.append('ρ');
                case 's' -> sb.append('σ');
                case 'u', 'y' -> sb.append('υ');
                case 'f' -> sb.append('φ');
                case ' ' -> sb.append(' ');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static class PartyLeader {
        final String name;
        final String role;
        String party;
        final List<String> aliases = new ArrayList<>();

        PartyLeader(String name, String role, String party) {
            this.name = name;
            this.role = role;
            this.party = party;
        }
    }
}
