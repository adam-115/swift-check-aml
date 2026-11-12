package com.swiftcheck.aml.plugins.grpep.parliament;

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
 * <b>gr_pep_parliament</b> – Députés et anciens députés du Parlement hellénique
 * (Βουλή των Ελλήνων).
 *
 * <p>
 * Couvre la catégorie <b>GR-b</b>, fonctions:
 * <ul>
 * <li><b>Député du Parlement hellénique</b> (fonction 659)</li>
 * <li><b>Ancien député du Parlement hellénique</b> (fonction 660)</li>
 * </ul>
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 *
 * <p>
 * Alimentation dynamique en direct via l'API MediaWiki (List of members of the
 * Hellenic Parliament, June 2023).
 */
@Extension
public class GrPepParliamentParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepParliamentParser.class);

    static final String ID_PREFIX = "gr-pep-parliament";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String JSON_FILE = "gr-parliament-wikipedia.json";

    private static final String DEFAULT_PAGE = "List_of_members_of_the_Hellenic_Parliament,_June_2023";

    private static final Pattern ROW_PATTERN = Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL_PATTERN = Pattern.compile("(?s)<td[^>]*>(.*?)</td>");
    private static final Pattern LINK_PATTERN = Pattern
            .compile("(?i)<a\\s+[^>]*href=\"(/wiki/[^\"]+|/w/index\\.php\\?title=[^\"&]+)[^\"]*\"[^>]*>([^<]+)</a>");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public GrPepParliamentParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GrPepParliamentParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d députés et parlementaires helléniques indexés.", count));
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
        String page = ctx.parameters().getOrDefault("page", DEFAULT_PAGE);

        URI uri = URI.create(endpoint + "?action=parse&page=" + page + "&prop=text&format=json");

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
        log.info("Données MediaWiki Parlement hellénique téléchargées : {} octets", res.body().length);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        String html = root.path("parse").path("text").path("*").asText("");
        if (html.isEmpty()) {
            throw new IllegalStateException("Aucun contenu HTML dans : " + rawFile);
        }

        Map<String, MpData> mps = new LinkedHashMap<>();

        Matcher rowMatcher = ROW_PATTERN.matcher(html);
        while (rowMatcher.find()) {
            String rowHtml = rowMatcher.group(1);
            Matcher cellMatcher = CELL_PATTERN.matcher(rowHtml);
            List<String> rawCells = new ArrayList<>();
            while (cellMatcher.find()) {
                rawCells.add(cellMatcher.group(1));
            }
            if (rawCells.size() < 2) {
                continue;
            }

            for (String cellHtml : rawCells) {
                Matcher linkMatcher = LINK_PATTERN.matcher(cellHtml);
                while (linkMatcher.find()) {
                    String target = linkMatcher.group(1);
                    String name = linkMatcher.group(2).trim();

                    if (isExcluded(target, name)) {
                        continue;
                    }

                    boolean isFormer = cellHtml.contains("<s>")
                            || cellHtml.toLowerCase(Locale.ROOT).contains("until")
                            || cellHtml.toLowerCase(Locale.ROOT).contains("resigned");

                    MpData mp = mps.computeIfAbsent(name, k -> {
                        MpData d = new MpData(name);
                        d.greekAlias = transliterateGreek(name);
                        return d;
                    });
                    if (isFormer) {
                        mp.isFormer = true;
                    }
                }
            }
        }

        if (mps.isEmpty()) {
            throw new IllegalStateException("Aucun parlementaire trouvé dans : " + rawFile);
        }

        int count = 0;
        for (MpData mp : mps.values()) {
            String position = mp.isFormer
                    ? "Ancien député du Parlement hellénique (Βουλή των Ελλήνων)"
                    : "Député du Parlement hellénique (Βουλή των Ελλήνων)";

            FtmEntity person = dataset.newEntity("Person", mp.name);
            person.addValue("name", mp.name);
            if (!mp.greekAlias.isEmpty() && !mp.greekAlias.equalsIgnoreCase(mp.name)) {
                person.addValue("alias", mp.greekAlias);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", position);
            person.addValue("sourceUrl", "https://www.hellenicparliament.gr/");
            dataset.addEntity(person);

            String posSlug = position.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", position);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Βουλή των Ελλήνων (Parlement hellénique)");
            pos.addValue("sourceUrl", "https://www.hellenicparliament.gr/");
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("startDate", "2023-06-25");
            if (mp.isFormer) {
                occupancy.addValue("status", "ended");
            } else {
                occupancy.addValue("status", "current");
            }
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private boolean isExcluded(String target, String name) {
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        if (lowerTarget.contains("constituency") || lowerTarget.contains("party")
                || lowerTarget.contains("election") || lowerTarget.contains("cite")
                || lowerTarget.contains("file:") || lowerTarget.contains("category:")
                || lowerTarget.contains("portal:") || lowerTarget.contains("help:")
                || lowerTarget.contains("hellenic_parliament") || lowerTarget.contains("special:")
                || lowerTarget.contains("talk:") || lowerTarget.contains("template:")) {
            return true;
        }

        if (name.length() < 3 || name.startsWith("[") && name.endsWith("]") || name.equalsIgnoreCase("el")) {
            return true;
        }

        String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.contains("new democracy") || lowerName.contains("pasok")
                || lowerName.contains("syriza") || lowerName.contains("communist party")
                || lowerName.contains("spartans") || lowerName.contains("greek solution")
                || lowerName.contains("course of freedom") || lowerName.contains("niki")
                || lowerName.contains("new left") || lowerName.contains("independents")
                || lowerName.contains("executive") || lowerName.contains("government")
                || lowerName.contains("cabinet") || lowerName.contains("prime minister")
                || lowerName.contains("head of state") || lowerName.contains("president of");
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

    private static class MpData {
        final String name;
        String greekAlias = "";
        boolean isFormer = false;

        MpData(String name) {
            this.name = name;
        }
    }
}
