package com.swiftcheck.aml.plugins.grpep.stateenterprises;

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
 * <b>gr_pep_state_enterprises</b> – Dirigeants des entreprises publiques helléniques (ΔΕΚΟ, HCAP, etc.).
 *
 * <p>Couvre la catégorie <b>GR-g</b>, fonction <b>Dirigeant d'une entreprise détenue par l'État</b> (fonction 667)
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (PPC, EYDAP, Hellenic Post, Hellenic Railways, EAS, HAI, Elliniko Metro).
 */
@Extension
public class GrPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepStateEnterprisesParser.class);

    static final String ID_PREFIX = "gr-pep-state-enterprises";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String JSON_FILE = "gr-state-enterprises-wikipedia.json";

    private static final String DEFAULT_TITLES =
            "Public_Power_Corporation|EYDAP|Hellenic_Post|Hellenic_Railways_Organisation|Hellenic_Defence_Systems|Hellenic_Aerospace_Industry|Attiko_Metro";

    private static final Pattern KEY_PEOPLE_PATTERN =
            Pattern.compile("(?m)^\\s*\\|\\s*key_people\\s*=\\s*([^|\\r\\n]+(?:[\\r\\n]+(?!\\s*\\|)[^\\r\\n|]+)*)");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public GrPepStateEnterprisesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GrPepStateEnterprisesParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d dirigeants d'entreprises publiques helléniques indexés.", count)
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
                        "https://hcap.gr/"));
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
        log.info("Données MediaWiki entreprises publiques helléniques téléchargées : {} octets", res.body().length);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pages = root.path("query").path("pages");
        if (pages.isMissingNode() || !pages.isObject() || pages.isEmpty()) {
            throw new IllegalStateException("Aucune page retournée dans : " + rawFile);
        }

        Map<String, SoeOfficial> officials = new LinkedHashMap<>();

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

            String companyName = cleanCompanyName(title);

            Matcher mKp = KEY_PEOPLE_PATTERN.matcher(content);
            if (mKp.find()) {
                String rawKp = mKp.group(1);
                String[] lines = rawKp.split("<br\\s*/?>|[\\r\\n]+");
                for (String line : lines) {
                    extractOfficialFromLine(line, companyName, officials);
                }
            }
        }

        if (officials.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant extrait du document : " + rawFile);
        }

        int count = 0;
        for (SoeOfficial off : officials.values()) {
            FtmEntity person = dataset.newEntity("Person", off.name);
            person.addValue("name", off.name);
            if (!off.greekAlias.isEmpty() && !off.greekAlias.equalsIgnoreCase(off.name)) {
                person.addValue("alias", off.greekAlias);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", off.role + " - " + off.company);
            person.addValue("sourceUrl", "https://hcap.gr/");
            dataset.addEntity(person);

            String posSlug = (off.role + "-" + off.company).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", off.role + " - " + off.company);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", off.company);
            pos.addValue("sourceUrl", "https://hcap.gr/");
            dataset.addEntity(pos);

            String occId = "occ-" + person.getId() + "-" + Math.abs((off.role + off.company).hashCode());
            FtmEntity occupancy = dataset.newEntity("Occupancy", occId);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);

            count++;
        }

        return count;
    }

    private void extractOfficialFromLine(String rawLine, String companyName, Map<String, SoeOfficial> officials) {
        String role = "Dirigeant d'entreprise publique";
        Pattern roleP = Pattern.compile("\\(([^)]+)\\)");
        Matcher mr = roleP.matcher(rawLine);
        if (mr.find()) {
            role = cleanWikitext(mr.group(1));
        } else if (rawLine.contains(":")) {
            int colonIdx = rawLine.indexOf(':');
            role = cleanWikitext(rawLine.substring(0, colonIdx));
        }

        String name = cleanWikitext(rawLine);
        if (name.isEmpty() || name.length() < 3 || name.contains("Director") || name.contains("President") || name.contains("Chairman")) {
            return;
        }

        final String finalRole = role;
        SoeOfficial soe = officials.computeIfAbsent(name, k -> {
            SoeOfficial s = new SoeOfficial(k, finalRole, companyName);
            s.greekAlias = transliterateGreek(k);
            return s;
        });
        soe.role = finalRole;
        soe.company = companyName;
    }

    private String cleanCompanyName(String title) {
        return switch (title) {
            case "Public Power Corporation" -> "ΔΕΗ (Public Power Corporation)";
            case "EYDAP" -> "ΕΥΔΑΠ (Compagnie des eaux d'Athènes - EYDAP)";
            case "Hellenic Post" -> "ΕΛΤΑ (Hellenic Post)";
            case "Hellenic Railways Organisation" -> "ΟΣΕ (Hellenic Railways Organisation)";
            case "Hellenic Defence Systems" -> "ΕΑΣ (Hellenic Defence Systems)";
            case "Hellenic Aerospace Industry" -> "ΕΑΒ (Hellenic Aerospace Industry)";
            case "Elliniko Metro" -> "Ελληνικό Μετρό (Elliniko Metro)";
            default -> title;
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
        text = text.replaceAll("'''?", "");
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

    private static class SoeOfficial {
        final String name;
        String role;
        String company;
        String greekAlias = "";

        SoeOfficial(String name, String role, String company) {
            this.name = name;
            this.role = role;
            this.company = company;
        }
    }
}
