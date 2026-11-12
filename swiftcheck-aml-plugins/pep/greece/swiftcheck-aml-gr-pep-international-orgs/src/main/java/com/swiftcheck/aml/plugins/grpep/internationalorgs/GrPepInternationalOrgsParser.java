package com.swiftcheck.aml.plugins.grpep.internationalorgs;

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
 * <b>gr_pep_international_orgs</b> – Dirigeants d'organisations internationales et agences européennes basées en Grèce.
 *
 * <p>Couvre la catégorie <b>GR-h</b>, fonction <b>Dirigeant d'une organisation internationale</b> (fonction 668)
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (ENISA, Cedefop, BSTDB, BSEC, Commission européenne).
 */
@Extension
public class GrPepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepInternationalOrgsParser.class);

    static final String ID_PREFIX = "gr-pep-international-orgs";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String JSON_FILE = "gr-international-orgs-wikipedia.json";

    private static final String DEFAULT_TITLES =
            "European_Union_Agency_for_Cybersecurity|European_Centre_for_the_Development_of_Vocational_Training|Black_Sea_Trade_and_Development_Bank|Organization_of_the_Black_Sea_Economic_Cooperation|Margaritis_Schinas";

    private static final Pattern CHIEF1_NAME_PATTERN = Pattern.compile("(?m)^\\s*\\|\\s*chief1_name\\s*=\\s*([^|\\r\\n]+)");
    private static final Pattern CHIEF1_POS_PATTERN = Pattern.compile("(?m)^\\s*\\|\\s*chief1_position\\s*=\\s*([^|\\r\\n]+)");
    private static final Pattern LEADER_NAME_PATTERN = Pattern.compile("(?m)^\\s*\\|\\s*leader_name\\s*=\\s*([^|\\r\\n]+)");
    private static final Pattern LEADER_TITLE_PATTERN = Pattern.compile("(?m)^\\s*\\|\\s*leader_title\\s*=\\s*([^|\\r\\n]+)");
    private static final Pattern OFFICEHOLDER_NAME_PATTERN = Pattern.compile("(?m)^\\s*\\|\\s*name\\s*=\\s*([^|\\r\\n]+)");
    private static final Pattern OFFICEHOLDER_OFFICE_PATTERN = Pattern.compile("(?m)^\\s*\\|\\s*office1?\\s*=\\s*([^|\\r\\n]+)");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public GrPepInternationalOrgsParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GrPepInternationalOrgsParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants d'organisations internationales en Grèce indexés.", count)
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
                        "https://enisa.europa.eu/"));
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
        log.info("Données MediaWiki organisations internationales téléchargées : {} octets", res.body().length);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pages = root.path("query").path("pages");
        if (pages.isMissingNode() || !pages.isObject() || pages.isEmpty()) {
            throw new IllegalStateException("Aucune page retournée dans : " + rawFile);
        }

        Map<String, OrgLeader> leaders = new LinkedHashMap<>();

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

            // Check chief1 (ENISA, CEDEFOP)
            Matcher mChiefName = CHIEF1_NAME_PATTERN.matcher(content);
            if (mChiefName.find()) {
                String name = cleanWikitext(mChiefName.group(1));
                String role = "Executive Director";
                Matcher mChiefPos = CHIEF1_POS_PATTERN.matcher(content);
                if (mChiefPos.find()) {
                    role = cleanWikitext(mChiefPos.group(1));
                }
                addLeader(leaders, name, role, title, "https://enisa.europa.eu/");
                continue;
            }

            // Check leader_name (BSTDB, BSEC)
            Matcher mLeaderName = LEADER_NAME_PATTERN.matcher(content);
            if (mLeaderName.find()) {
                String name = cleanWikitext(mLeaderName.group(1));
                String role = "Leader";
                Matcher mLeaderPos = LEADER_TITLE_PATTERN.matcher(content);
                if (mLeaderPos.find()) {
                    role = cleanWikitext(mLeaderPos.group(1));
                }
                addLeader(leaders, name, role, title, "https://bsec-organization.org/");
                continue;
            }

            // Check officeholder (Margaritis Schinas)
            if (content.contains("{{Infobox officeholder")) {
                String name = title;
                Matcher mName = OFFICEHOLDER_NAME_PATTERN.matcher(content);
                if (mName.find()) {
                    name = cleanWikitext(mName.group(1));
                }
                String role = "Vice-President of the European Commission";
                Matcher mOffice = OFFICEHOLDER_OFFICE_PATTERN.matcher(content);
                if (mOffice.find()) {
                    role = cleanWikitext(mOffice.group(1));
                }
                addLeader(leaders, name, role, "European Commission", "https://ec.europa.eu/");
            }
        }

        if (leaders.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant extrait du document : " + rawFile);
        }

        int count = 0;
        for (OrgLeader leader : leaders.values()) {
            FtmEntity person = dataset.newEntity("Person", leader.name);
            person.addValue("name", leader.name);
            person.addValue("country", COUNTRY);
            person.addValue("position", leader.role);
            person.addValue("sourceUrl", leader.sourceUrl);
            dataset.addEntity(person);

            String posSlug = (leader.role + "-" + leader.organization).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", leader.role);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", leader.organization);
            pos.addValue("sourceUrl", leader.sourceUrl);
            dataset.addEntity(pos);

            String occId = "occ-" + person.getId() + "-" + Math.abs((leader.role + leader.organization).hashCode());
            FtmEntity occupancy = dataset.newEntity("Occupancy", occId);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private void addLeader(Map<String, OrgLeader> leaders, String name, String role, String organization, String sourceUrl) {
        if (name == null || name.isBlank() || name.length() < 3) {
            return;
        }
        leaders.put(name, new OrgLeader(name, role, organization, sourceUrl));
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

    private static class OrgLeader {
        final String name;
        final String role;
        final String organization;
        final String sourceUrl;

        OrgLeader(String name, String role, String organization, String sourceUrl) {
            this.name = name;
            this.role = role;
            this.organization = organization;
            this.sourceUrl = sourceUrl;
        }
    }
}
