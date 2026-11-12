package com.swiftcheck.aml.plugins.ropep.curteaconturi;

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
 * <b>ro_pep_court_of_accounts</b> – Membres de la direction et présidents de la Cour des Comptes de Roumanie (Curtea de Conturi a României).
 *
 * <p>Couvre la catégorie <b>RO-e</b>, fonction <b>Membre de la direction de la banque centrale ou de la Cour des comptes</b> (fonction 739)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 * Source dynamique : MediaWiki API (Wikipedia - Curtea de Conturi (România)).
 */
@Extension
public class RoPepCourtOfAccountsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepCourtOfAccountsParser.class);

    static final String ID_PREFIX = "ro-pep-court-accounts";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String JSON_FILE = "ro-court-of-accounts-wikipedia.json";

    public static final String PARAM_WIKIPEDIA_API_URL = "wikipedia.api.url";
    public static final String PARAM_PAGE_TITLE = "wikipedia.page.title";

    private static final String DEFAULT_API_URL = "https://ro.wikipedia.org/w/api.php";
    private static final String DEFAULT_TITLE = "Curtea_de_Conturi_(România)";

    private static final Pattern BULLET_PATTERN = Pattern.compile("^\\*\\s*(.+)$");
    private static final Pattern PRESIDENTS_SECTION = Pattern.compile("^==\\s*Președinți\\s*==", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_HEADER = Pattern.compile("^==[^=]");
    private static final Pattern YEARS_PATTERN = Pattern.compile("\\((\\d{4})\\s*[–\\-\\—]\\s*(\\d{4}|prezent|present)?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIKILINK_PATTERN = Pattern.compile("\\[\\[([^|\\]]+)(?:\\|([^|\\]]+))?\\]\\]");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);

        downloadWikipediaData(ctx, rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d présidents et membres de la Cour des Comptes de Roumanie indexés.", count)
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
                records.add(new SourceRecord(entity.getId(), "Person", name, "https://www.curteadeconturi.ro/"));
            }
        }
        return records;
    }

    private void downloadWikipediaData(ParserContext ctx, Path destination) throws IOException, InterruptedException {
        String apiUrl = ctx.parameters().getOrDefault(PARAM_WIKIPEDIA_API_URL, DEFAULT_API_URL);
        String title = ctx.parameters().getOrDefault(PARAM_PAGE_TITLE, DEFAULT_TITLE);

        String fullUrl = apiUrl + "?action=query&titles=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&prop=revisions&rvslots=main&rvprop=content&format=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("User-Agent", "SwiftCheckAML/1.0 (compliance@swiftcheck.com; automated PEP parser)")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("Erreur HTTP " + resp.statusCode() + " lors de la requête MediaWiki: " + fullUrl);
        }

        try (InputStream in = resp.body()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("MediaWiki : page de la Cour des Comptes roumaine téléchargée vers {}", destination);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pages = root.path("query").path("pages");
        if (pages.isMissingNode() || !pages.isObject() || pages.isEmpty()) {
            throw new IllegalStateException("Aucune page retournée dans l'arbre JSON : " + rawFile);
        }

        String wikitext = "";
        for (Iterator<JsonNode> it = pages.elements(); it.hasNext(); ) {
            JsonNode page = it.next();
            JsonNode revs = page.path("revisions");
            if (revs.isArray() && !revs.isEmpty()) {
                wikitext = revs.get(0).path("slots").path("main").path("*").asText("");
                if (wikitext.isEmpty()) {
                    wikitext = revs.get(0).path("*").asText("");
                }
                break;
            }
        }

        if (wikitext.isEmpty()) {
            throw new IllegalStateException("Contenu wikitext vide pour : " + rawFile);
        }

        Map<String, PresidentData> officials = extractPresidents(wikitext);
        int count = 0;

        for (PresidentData pd : officials.values()) {
            FtmEntity person = dataset.newEntity("Person", pd.name);
            person.addValue("name", pd.name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", pd.position);
            person.addValue("sourceUrl", "https://www.curteadeconturi.ro/");
            dataset.addEntity(person);

            String posSlug = pd.position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", pd.position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Curtea de Conturi a României");
            pos.addValue("topics", "gov.audit");
            pos.addValue("sourceUrl", "https://www.curteadeconturi.ro/");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            if (pd.startDate != null) {
                occupancy.addValue("startDate", pd.startDate);
            }
            if (pd.endDate != null) {
                occupancy.addValue("endDate", pd.endDate);
                occupancy.addValue("status", "ended");
            } else {
                occupancy.addValue("status", "current");
            }
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private Map<String, PresidentData> extractPresidents(String wikitext) {
        Map<String, PresidentData> map = new LinkedHashMap<>();
        String[] lines = wikitext.split("\r?\n");

        boolean inPresidents = false;

        // Also check chief1_name from infobox
        Pattern chiefPattern = Pattern.compile("chief1_name\\s*=\\s*(.+)", Pattern.CASE_INSENSITIVE);

        for (String line : lines) {
            String trimmed = line.trim();

            Matcher mChief = chiefPattern.matcher(trimmed);
            if (mChief.find()) {
                String name = cleanWikitext(mChief.group(1));
                if (name.length() >= 3) {
                    PresidentData pd = new PresidentData(name, "Președinte al Curții de Conturi a României");
                    pd.startDate = "2025";
                    map.put(name, pd);
                }
            }

            if (PRESIDENTS_SECTION.matcher(trimmed).find()) {
                inPresidents = true;
                continue;
            }
            if (inPresidents && ANY_HEADER.matcher(trimmed).find()) {
                inPresidents = false;
            }

            if (inPresidents) {
                Matcher mb = BULLET_PATTERN.matcher(trimmed);
                if (mb.matches()) {
                    parsePresidentBullet(mb.group(1), map);
                }
            }
        }

        return map;
    }

    private void parsePresidentBullet(String bulletText, Map<String, PresidentData> map) {
        String startYear = null;
        String endYear = null;

        Matcher my = YEARS_PATTERN.matcher(bulletText);
        if (my.find()) {
            startYear = my.group(1);
            String rawEnd = my.group(2);
            if (rawEnd != null && !rawEnd.equalsIgnoreCase("prezent") && !rawEnd.equalsIgnoreCase("present")) {
                endYear = rawEnd;
            }
        }

        // Modern Romania era (post-1989 / from 1990 onwards)
        if (startYear != null) {
            try {
                int sy = Integer.parseInt(startYear);
                if (sy < 1990) {
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        String rawName = bulletText;
        if (rawName.contains("(")) {
            rawName = rawName.substring(0, rawName.indexOf('('));
        }

        String name = cleanWikitext(rawName);
        if (name.length() < 3) {
            return;
        }

        PresidentData pd = map.computeIfAbsent(name, k -> new PresidentData(k, "Președinte al Curții de Conturi a României"));
        if (startYear != null) pd.startDate = startYear;
        if (endYear != null) pd.endDate = endYear;
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
        s = sb.toString();

        return s.replaceAll("\\s+", " ").trim();
    }

    private static class PresidentData {
        final String name;
        final String position;
        String startDate;
        String endDate;

        PresidentData(String name, String position) {
            this.name = name;
            this.position = position;
        }
    }
}
