package com.swiftcheck.aml.plugins.grpep.armedforces;

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
 * <b>gr_pep_armed_forces</b> – Officiers généraux des forces armées helléniques (GEETHA, GES, GEN, GEA).
 *
 * <p>Couvre la catégorie <b>GR-f</b>, fonction <b>Officier général des forces armées</b> (fonction 666)
 * (Loi 4557/2018, art. 3; JO C/2023/724).
 *
 * <p>Alimentation dynamique en direct via l'API MediaWiki (Wikipedia EL).
 */
@Extension
public class GrPepArmedForcesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(GrPepArmedForcesParser.class);

    static final String ID_PREFIX = "gr-pep-armed-forces";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "gr";
    static final String JSON_FILE = "gr-armed-forces-wikipedia.json";

    static final String DEFAULT_TITLES = "%CE%93%CE%B5%CE%BD%CE%B9%CE%BA%CF%8C_%CE%95%CF%80%CE%B9%CF%84%CE%B5%CE%BB%CE%B5%CE%AF%CE%BF_%CE%95%CE%B8%CE%BD%CE%B9%CE%BA%CE%AE%CF%82_%CE%86%CE%BC%CF%85%CE%BD%CE%B1%CF%82_(%CE%95%CE%BB%CE%BB%CE%AC%CE%B4%CE%B1)|%CE%93%CE%B5%CE%BD%CE%B9%CE%BA%CF%8C_%CE%95%CF%80%CE%B9%CF%84%CE%B5%CE%BB%CE%B5%CE%AF%CE%BF_%CE%A3%CF%84%CF%81%CE%B1%CF%84%CE%BF%CF%8D|%CE%93%CE%B5%CE%BD%CE%B9%CE%BA%CF%8C_%CE%95%CF%80%CE%B9%CF%84%CE%B5%CE%BB%CE%B5%CE%AF%CE%BF_%CE%9D%CE%B1%CF%85%CF%84%CE%B9%CE%BA%CE%BF%CF%8D|%CE%93%CE%B5%CE%BD%CE%B9%CE%BA%CF%8C_%CE%95%CF%80%CE%B9%CF%84%CE%B5%CE%BB%CE%B5%CE%AF%CE%BF_%CE%91%CE%B5%CF%81%CE%BF%CF%80%CE%BF%CF%81%CE%AF%CE%B1%CF%82";

    private static final Pattern COMMANDER_PATTERN = Pattern.compile("(?i)\\|\\s*Διοικητής(\\d*)\\s*=\\s*([^\\n\\r|]+)");
    private static final Pattern ROLE_PATTERN = Pattern.compile("(?i)\\|\\s*Ιδιότητα διοικητή(\\d*)\\s*=\\s*([^\\n\\r|]+)");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]");

    private static final Pattern RANK_CLEAN_PATTERN = Pattern.compile("(?i)\\[\\[(?:Στρατηγός|Αντιστράτηγος|Αντιναύαρχος|Αντιπτέραρχος|Υποστράτηγος|Υποναύαρχος|Υποπτέραρχος)\\]\\]|Στρατηγός|Αντιστράτηγος|Αντιναύαρχος|Αντιπτέραρχος|Υποστράτηγος|Υποναύαρχος|Υποπτέραρχος|\\(Ι\\)|\\(Μ\\)|ΠΝ");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient;

    public GrPepArmedForcesParser() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GrPepArmedForcesParser(HttpClient httpClient) {
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
                String.format(Locale.ROOT, "%d officiers généraux des forces armées helléniques indexés.", count)
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
                        "https://geetha.mil.gr/"));
            }
        }
        return records;
    }

    private void fetchLive(Path rawFile, ParserContext ctx) throws IOException, InterruptedException {
        String titles = ctx.parameters().getOrDefault("wikipedia_titles", DEFAULT_TITLES);
        String url = ctx.parameters().getOrDefault("wikipedia_api_url",
                "https://el.wikipedia.org/w/api.php?action=query&prop=revisions&titles="
                        + titles + "&redirects=1&rvslots=*&rvprop=content&format=json");

        log.info("Récupération en direct des officiers généraux grecs depuis : {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SwiftCheckAml/1.0 (PEP Parser; https://swiftcheck.com; info@swiftcheck.com)")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Échec requête MediaWiki API (" + response.statusCode() + ") : " + response.body());
        }

        Files.writeString(rawFile, response.body(), StandardCharsets.UTF_8);
    }

    int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        JsonNode pagesNode = root.path("query").path("pages");
        if (pagesNode.isMissingNode() || !pagesNode.isObject()) {
            throw new IllegalStateException("Réponse MediaWiki invalide : query.pages manquant dans " + rawFile);
        }

        Map<String, Commander> commanders = new LinkedHashMap<>();

        pagesNode.fields().forEachRemaining(entry -> {
            JsonNode page = entry.getValue();
            String title = page.path("title").asText("");
            JsonNode revisions = page.path("revisions");
            if (revisions.isArray() && !revisions.isEmpty()) {
                String wikitext = revisions.get(0).path("slots").path("main").path("*").asText("");
                extractCommanders(title, wikitext, commanders);
            }
        });

        if (commanders.isEmpty()) {
            throw new IllegalStateException("Aucun officier général extrait depuis " + rawFile);
        }

        int count = 0;
        for (Commander cmd : commanders.values()) {
            String latin = transliterateGreek(cmd.name);

            FtmEntity person = dataset.newEntity("Person", latin);
            person.addValue("name", latin);
            person.addValue("name", cmd.name);
            person.addValue("alias", cmd.name);
            if (latin.contains("Ch")) {
                String altLatin = latin.replace("Ch", "H");
                person.addValue("name", altLatin);
                person.addValue("alias", altLatin);
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", cmd.role + " – " + cmd.organization);
            person.addValue("sourceUrl", "https://geetha.mil.gr/");
            dataset.addEntity(person);

            String posSlug = (cmd.role + "-" + cmd.organization).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", cmd.role + " – " + cmd.organization);
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", cmd.organization);
            pos.addValue("sourceUrl", "https://geetha.mil.gr/");
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

    private void extractCommanders(String pageTitle, String wikitext, Map<String, Commander> commanders) {
        String org = mapOrganization(pageTitle);

        Map<String, String> rolesByNum = new LinkedHashMap<>();
        Matcher rm = ROLE_PATTERN.matcher(wikitext);
        while (rm.find()) {
            String num = rm.group(1);
            String role = sanitizeText(rm.group(2));
            rolesByNum.put(num, role);
        }

        Matcher cm = COMMANDER_PATTERN.matcher(wikitext);
        while (cm.find()) {
            String num = cm.group(1);
            String rawVal = cm.group(2);
            String cleaned = sanitizeCommanderName(rawVal);
            if (cleaned.length() >= 4 && cleaned.contains(" ")) {
                String role = rolesByNum.getOrDefault(num, "Αρχηγός / Διοικητής");
                commanders.putIfAbsent(cleaned, new Commander(cleaned, role, org));
            }
        }
    }

    private String sanitizeCommanderName(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("(?i)<ref[^>]*>.*?</ref>|<ref[^>]*/>|<ref[^>]*>", "")
                .replaceAll("\\{\\{[^}]*\\}\\}", "")
                .replaceAll("\\{\\{.*", "");
        s = RANK_CLEAN_PATTERN.matcher(s).replaceAll("");
        Matcher linkM = LINK_PATTERN.matcher(s);
        if (linkM.find()) {
            s = linkM.group(1);
        }
        return s.replaceAll("'''?", "")
                .replaceAll("–", "-")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String sanitizeText(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)<ref[^>]*>.*?</ref>|<ref[^>]*/>|<ref[^>]*>", "")
                .replaceAll("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)\\]\\]", "$1")
                .replaceAll("'''?", "")
                .trim();
    }

    private String mapOrganization(String title) {
        if (title.contains("Αεροπορίας")) return "Γενικό Επιτελείο Αεροπορίας (GEA)";
        if (title.contains("Ναυτικού")) return "Γενικό Επιτελείο Ναυτικού (GEN)";
        if (title.contains("Στρατού")) return "Γενικό Επιτελείο Στρατού (GES)";
        return "Γενικό Επιτελείο Εθνικής Άμυνας (GEETHA)";
    }

    static String transliterateGreek(String greek) {
        if (greek == null) return "";
        String s = greek
                .replace("ου", "ou").replace("Ου", "Ou").replace("ΟΥ", "OU").replace("ού", "ou")
                .replace("αι", "ai").replace("Αι", "Ai").replace("ΑΙ", "AI").replace("αί", "ai")
                .replace("ει", "ei").replace("Ει", "Ei").replace("ΕΙ", "EI").replace("εί", "ei")
                .replace("οι", "oi").replace("Οι", "Oi").replace("ΟΙ", "OI").replace("οί", "oi")
                .replace("υι", "yi").replace("Υι", "Yi").replace("ΥΙ", "YI").replace("υί", "yi")
                .replace("μπ", "b").replace("Μπ", "B").replace("ΜΠ", "B")
                .replace("ντ", "d").replace("Ντ", "D").replace("ΝΤ", "D")
                .replace("γκ", "g").replace("Γκ", "G").replace("ΓΚ", "G")
                .replace("γγ", "ng");
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case 'Α', 'Ά' -> sb.append('A');
                case 'Β' -> sb.append('V');
                case 'Γ' -> sb.append('G');
                case 'Δ' -> sb.append('D');
                case 'Ε', 'Έ' -> sb.append('E');
                case 'Ζ' -> sb.append('Z');
                case 'Η', 'Ή' -> sb.append('I');
                case 'Θ' -> sb.append("Th");
                case 'Ι', 'Ί', 'Ϊ' -> sb.append('I');
                case 'Κ' -> sb.append('K');
                case 'Λ' -> sb.append('L');
                case 'Μ' -> sb.append('M');
                case 'Ν' -> sb.append('N');
                case 'Ξ' -> sb.append('X');
                case 'Ο', 'Ό' -> sb.append('O');
                case 'Π' -> sb.append('P');
                case 'Ρ' -> sb.append('R');
                case 'Σ' -> sb.append('S');
                case 'Τ' -> sb.append('T');
                case 'Υ', 'Ύ', 'Ϋ' -> sb.append('Y');
                case 'Φ' -> sb.append('F');
                case 'Χ' -> sb.append("Ch");
                case 'Ψ' -> sb.append("Ps");
                case 'Ω', 'Ώ' -> sb.append('O');

                case 'α', 'ά' -> sb.append('a');
                case 'β' -> sb.append('v');
                case 'γ' -> sb.append('g');
                case 'δ' -> sb.append('d');
                case 'ε', 'έ' -> sb.append('e');
                case 'ζ' -> sb.append('z');
                case 'η', 'ή' -> sb.append('i');
                case 'θ' -> sb.append("th");
                case 'ι', 'ί', 'ϊ', 'ΐ' -> sb.append('i');
                case 'κ' -> sb.append('k');
                case 'λ' -> sb.append('l');
                case 'μ' -> sb.append('m');
                case 'ν' -> sb.append('n');
                case 'ξ' -> sb.append('x');
                case 'ο', 'ό' -> sb.append('o');
                case 'π' -> sb.append('p');
                case 'ρ' -> sb.append('r');
                case 'σ', 'ς' -> sb.append('s');
                case 'τ' -> sb.append('t');
                case 'υ', 'ύ', 'ϋ', 'ΰ' -> sb.append('y');
                case 'φ' -> sb.append('f');
                case 'χ' -> sb.append("ch");
                case 'ψ' -> sb.append("ps");
                case 'ω', 'ώ' -> sb.append('o');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static class Commander {
        final String name;
        final String role;
        final String organization;

        Commander(String name, String role, String organization) {
            this.name = name;
            this.role = role;
            this.organization = organization;
        }
    }
}
