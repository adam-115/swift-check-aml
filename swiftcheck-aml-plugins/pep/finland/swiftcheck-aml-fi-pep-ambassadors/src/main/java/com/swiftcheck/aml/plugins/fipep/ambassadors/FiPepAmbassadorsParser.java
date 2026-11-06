package com.swiftcheck.aml.plugins.fipep.ambassadors;

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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fi_pep_ambassadors</b> – chefs des missions diplomatiques de la Finlande : ambassadeurs, consuls généraux,
 * représentants permanents auprès des organisations internationales, chefs de bureau de représentation et ambassadeurs
 * itinérants, personnes politiquement exposées au titre des ambassadeurs.
 *
 * <p>Source officielle : le site du ministère des Affaires étrangères {@code https://um.fi}.
 * <ul>
 *   <li>{@code /finland-s-representation-abroad-by-country} : liste des représentations par pays, avec un lien vers la fiche de
 *       contact de chacune (les consulats honoraires sont ignorés) ;</li>
 *   <li>{@code /missions-to-international-organisations} : missions permanentes (ONU, UE, OTAN, Conseil de l'Europe, OCDE…) ;</li>
 *   <li>{@code /roving-ambassadors} : ambassadeurs itinérants, en poste à Helsinki ;</li>
 *   <li>chaque fiche de contact : rubrique « Personnel », le chef est la personne dont le titre commence par « Head of
 *       Mission », « Consul General », « Head of Office », « Permanent Representative » ou « Roving Ambassador ».</li>
 * </ul>
 * Le {@code robots.txt} d'um.fi demande 20 secondes entre deux requêtes ({@code Crawl-delay: 20}) : le parser ne descend
 * jamais sous ce délai, même si le paramètre {@code delayMs} est plus petit. Une exécution complète (environ 95 pages) dure
 * donc une trentaine de minutes. Une fiche illisible ne bloque pas les autres et est signalée dans le message. Type
 * {@code PLUGIN_MANAGED}. Paramètres (facultatifs) :
 * <pre>{@code
 * {"delayMs": "20000", "maxMissions": "0"}
 * }</pre>
 * {@code maxMissions} limite le nombre de fiches lues (0 = toutes), pour un essai rapide.
 */
@Extension
public class FiPepAmbassadorsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(FiPepAmbassadorsParser.class);

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fi-pep-ambassadors";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String BASE = "https://um.fi";
    static final String LIST_BY_COUNTRY = BASE + "/finland-s-representation-abroad-by-country";
    static final String LIST_ORGANISATIONS = BASE + "/missions-to-international-organisations";
    static final String ROVING = BASE + "/roving-ambassadors";
    static final String LIST_FILE = "list-by-country.html";
    static final String ORGS_FILE = "list-organisations.html";
    static final String ROVING_FILE = "roving.html";
    static final String MISSION_PREFIX = "mission-";

    /** Délai minimal demandé par le robots.txt d'um.fi. */
    static final long MIN_DELAY_MS = 20_000;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Finlande - ambassadeurs)";
    private static final Pattern LINK = Pattern.compile("<a ([^>]*)href=\"([^\"]*contactInfoOrganization/id/(\\d+))\"[^>]*>\\s*([^<]*)");
    private static final Pattern HEAD_TITLE = Pattern.compile(
            "^(Head of Mission|Head of Office|Head of Representation|Consul General|Permanent Representative|Roving Ambassador|Ambassador)\\b.*");
    private static final Pattern NAME = Pattern.compile("^\\p{Lu}[\\p{L}'’.-]*(?: (?:\\p{Lu}[\\p{L}'’.-]*|von|van|de|af|la|le|der|den|da|do|di)){1,4}$");

    /** Une représentation listée. */
    record Mission(String id, String name, String url) {
    }

    /** Un chef de mission lu sur une fiche. */
    record Head(String name, String title) {
    }

    /** Résultat de la lecture d'une fiche. */
    record Outcome(Mission mission, List<Head> heads, String problem) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(delayMs(p)), ctx.rawDir(), intParam(p, "maxMissions", 0));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque chef de mission, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Télécharge les listes, la page des ambassadeurs itinérants puis la fiche de chaque représentation. */
    static void download(Fetcher fetcher, Path rawDir, int maxMissions) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String list = fetcher.get(LIST_BY_COUNTRY);
        Files.writeString(rawDir.resolve(LIST_FILE), list, StandardCharsets.UTF_8);
        String orgs = fetcher.get(LIST_ORGANISATIONS);
        Files.writeString(rawDir.resolve(ORGS_FILE), orgs, StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve(ROVING_FILE), fetcher.get(ROVING), StandardCharsets.UTF_8);
        Map<String, Mission> missions = new LinkedHashMap<>();
        missions(list).forEach(m -> missions.putIfAbsent(m.id(), m));
        missions(orgs).forEach(m -> missions.putIfAbsent(m.id(), m));
        int n = 0;
        int failed = 0;
        for (Mission m : missions.values()) {
            if (maxMissions > 0 && n >= maxMissions) {
                break;
            }
            n++;
            try {
                Files.writeString(rawDir.resolve(MISSION_PREFIX + m.id() + ".html"), fetcher.get(m.url()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                failed++;
                log.warn("Fiche {} non téléchargée : {}", m.name(), e.getMessage());
            }
        }
        if (n > 0 && failed == n) {
            throw new IOException("Aucune fiche de représentation téléchargée.");
        }
    }

    /** Lit les fichiers de {@code rawDir} et écrit les chefs de mission ; renvoie le message du bilan. */
    String convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Path listFile = rawDir.resolve(LIST_FILE);
        if (!Files.exists(listFile)) {
            throw new IllegalStateException(LIST_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        Map<String, Mission> missions = new LinkedHashMap<>();
        missions(Files.readString(listFile, StandardCharsets.UTF_8)).forEach(m -> missions.putIfAbsent(m.id(), m));
        Path orgsFile = rawDir.resolve(ORGS_FILE);
        if (Files.exists(orgsFile)) {
            missions(Files.readString(orgsFile, StandardCharsets.UTF_8)).forEach(m -> missions.putIfAbsent(m.id(), m));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Mission m : missions.values()) {
            Path file = rawDir.resolve(MISSION_PREFIX + m.id() + ".html");
            if (!Files.exists(file)) {
                continue;                                   // fiche non téléchargée (maxMissions ou échec)
            }
            List<Head> heads = readHeads(Files.readString(file, StandardCharsets.UTF_8));
            outcomes.add(new Outcome(m, heads, heads.isEmpty() ? "aucun chef de mission sur la fiche" : null));
        }
        Path rovingFile = rawDir.resolve(ROVING_FILE);
        if (Files.exists(rovingFile)) {
            List<Head> roving = readHeads(Files.readString(rovingFile, StandardCharsets.UTF_8));
            outcomes.add(new Outcome(new Mission("roving", "Ambassadeurs itinérants (Helsinki)", ROVING), roving,
                    roving.isEmpty() ? "aucun ambassadeur itinérant sur la page" : null));
        }
        int persons = 0;
        List<String> without = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (o.problem() != null) {
                without.add(o.mission().name());
                continue;
            }
            for (Head h : o.heads()) {
                write(o.mission(), h, dataset);
                persons++;
            }
        }
        if (persons == 0) {
            throw new IllegalStateException("Aucun chef de mission lu sur " + outcomes.size() + " fiche(s) : la mise en page a peut-être changé.");
        }
        String message = persons + " chefs de mission lus sur " + (outcomes.size() - without.size()) + " représentation(s) ("
                + missions.size() + " listées)";
        return without.isEmpty() ? message + "." : message + ". Sans chef de mission : " + String.join(" ; ", without) + ".";
    }

    private static void write(Mission m, Head h, FtmDatasetBuilder dataset) {
        String role = role(h.title());
        String post = role + " – " + m.name();
        FtmEntity person = dataset.newEntity("Person", h.name());
        person.addValue("name", h.name());
        person.addValue("firstName", h.name().substring(0, h.name().indexOf(' ')));
        person.addValue("lastName", h.name().substring(h.name().lastIndexOf(' ') + 1));
        person.addValue("country", "fi");
        person.addValue("position", post);
        person.addValue("sourceUrl", m.url());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", m.id(), role);
        position.addValue("name", post);
        position.addValue("country", "fi");
        position.addValue("organization", m.name());
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", h.name(), m.id(), role);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", "Titre publié : " + h.title());
        occupancy.addValue("sourceUrl", m.url());
        dataset.addEntity(occupancy);
    }

    static String role(String title) {
        if (title.startsWith("Roving Ambassador")) {
            return "Ambassadeur itinérant";
        }
        if (title.startsWith("Permanent Representative")) {
            return "Représentant permanent (ambassadeur)";
        }
        if (title.startsWith("Consul General")) {
            return "Consul général";
        }
        if (title.startsWith("Head of Office") || title.startsWith("Head of Representation")) {
            return "Chef de bureau de représentation";
        }
        return "Ambassadeur (chef de mission)";
    }

    /** Les représentations (hors consulats honoraires) liées dans une page de liste, sans doublon. */
    static List<Mission> missions(String html) {
        Map<String, Mission> out = new LinkedHashMap<>();
        Matcher m = LINK.matcher(html);
        while (m.find()) {
            String name = decode(m.group(4)).replaceAll("\\s+", " ").trim();
            if (name.isEmpty() || name.matches("(?i).*(honorary|kunniakons|hedersk).*")) {
                continue;
            }
            String href = decode(m.group(2));
            out.putIfAbsent(m.group(3), new Mission(m.group(3), name, href.startsWith("/") ? BASE + href : href));
        }
        return new ArrayList<>(out.values());
    }

    /** Les chefs d'une fiche : sous « Personnel », le nom qui précède un titre de chef de mission. */
    static List<Head> readHeads(String html) {
        List<String> lines = lines(html);
        int start = lines.indexOf("Personnel");
        List<Head> out = new ArrayList<>();
        if (start < 0) {
            return out;
        }
        for (int i = start + 2; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("Language versions") || line.equals("Countries")) {
                break;
            }
            String name = lines.get(i - 1);
            if (HEAD_TITLE.matcher(line).matches() && NAME.matcher(name).matches()
                    && out.stream().noneMatch(h -> h.name().equals(name))) {
                out.add(new Head(name, line));
            }
        }
        return out;
    }

    /** Lignes de texte de la page, entités décodées, caractères invisibles retirés. */
    static List<String> lines(String html) {
        String text = decode(html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<(br|/p|/div|/li|/h\\d|/a|/td|/tr|/dd|/dt)[^>]*>", "\n").replaceAll("<[^>]+>", " "));
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replaceAll("[\\u200B-\\u200F\\u2060\\uFEFF\\u00AD]", "").replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    private static String decode(String s) {
        Matcher m = Pattern.compile("&#x([0-9A-Fa-f]+);|&#(\\d+);").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp = m.group(1) != null ? Integer.parseInt(m.group(1), 16) : Integer.parseInt(m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&nbsp;", " ").replace("&amp;", "&").replace("&auml;", "ä").replace("&ouml;", "ö")
                .replace("&aring;", "å").replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&#39;", "'");
    }

    /** Délai entre requêtes : jamais moins que le Crawl-delay demandé par le robots.txt. */
    static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        long asked = v == null || v.isBlank() ? MIN_DELAY_MS : Long.parseLong(v.trim());
        return Math.max(asked, MIN_DELAY_MS);
    }

    private static int intParam(Map<String, String> p, String key, int def) {
        String v = p.get(key);
        return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        long[] last = {0};
        return url -> {
            long wait = last[0] + delayMs - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(2)).header("User-Agent", USER_AGENT).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " : " + url);
                }
                return response.body();
            } finally {
                last[0] = System.currentTimeMillis();
            }
        };
    }
}
