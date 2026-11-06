package com.swiftcheck.aml.plugins.fipep.institutions;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fi_pep_institutions</b> – Président de la République de Finlande (en fonction et anciens présidents vivants) et
 * dirigeants de Solidium, la société d'investissement de l'État (conseil d'administration et direction générale).
 *
 * <p>Sources officielles, pages publiques téléchargées à chaque exécution :
 * <ul>
 *   <li>{@code president} : {@code https://www.presidentti.fi/en/curriculum-vitae/} (président en fonction) et
 *       {@code https://www.presidentti.fi/en/presidency/former-presidents-of-the-republic-of-finland/} (« Nom, Prénoms
 *       (AAAA–) » puis la période du mandat ; les présidents décédés sont ignorés) ;</li>
 *   <li>{@code solidium} : {@code https://www.solidium.fi/en/company/board-of-directors/} (nom, rôle éventuel, « Read Bio »,
 *       « born AAAA ») et {@code https://www.solidium.fi/en/company/team/} (directeur général, financier et des
 *       investissements).</li>
 * </ul>
 * Les sites des ministères et de la Banque de Finlande sont protégés ou rendus côté navigateur et ne sont pas utilisés.
 * Type {@code PLUGIN_MANAGED}. Paramètres (facultatifs) :
 * <pre>{@code
 * {"keys": "president,solidium", "delayMs": "1500"}
 * }</pre>
 */
@Extension
public class FiPepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fi-pep-institutions";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String PRESIDENT_ROLE = "Président de la République de Finlande (tasavallan presidentti)";
    static final String FORMER_PRESIDENT_ROLE = "Ancien président de la République de Finlande";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Finlande - president et Solidium)";
    private static final Pattern FORMER = Pattern.compile("^([\\p{L}'’ -]+), ([\\p{L}'’ -]+) \\((\\d{4})–(\\d{4})?\\)$");
    private static final Pattern TERM = Pattern.compile("^(\\d{4})–(\\d{4})?$");
    private static final Pattern BORN = Pattern.compile("^(?:[Bb]orn|s\\.)\\.? ?(\\d{4})\\b.*");
    private static final Set<String> BOARD_ROLES = Set.of("Chair", "Vice Chair");
    private static final Map<String, String> EXECUTIVES = Map.of("Chief Executive Officer", "Directeur général (CEO)",
            "Chief Financial Officer", "Directeur financier (CFO)", "Chief Investment Officer", "Directeur des investissements (CIO)");

    /** Une source : clé, nom lisible et fichiers (nom de fichier brut, adresse). */
    record Source(String key, String name, Map<String, String> pages) {
    }

    static final List<Source> SOURCES = List.of(
            new Source("president", "Président de la République",
                    Map.of("president-cv.html", "https://www.presidentti.fi/en/curriculum-vitae/",
                            "president-former.html", "https://www.presidentti.fi/en/presidency/former-presidents-of-the-republic-of-finland/")),
            new Source("solidium", "Solidium Oy",
                    Map.of("solidium-board.html", "https://www.solidium.fi/en/company/board-of-directors/",
                            "solidium-team.html", "https://www.solidium.fi/en/company/team/")));

    /** Une personne lue : nom, rôle, année de naissance, début et fin de fonction (années) et note. */
    record Person(String name, String alias, String role, String birthYear, String startYear, String endYear, String note,
            String organization, String sourceUrl) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<Source> sources = selected(p);
        download(httpFetcher(delayMs(p)), sources, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), sources, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), selected(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    static void download(Fetcher fetcher, List<Source> sources, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        for (Source s : sources) {
            for (Map.Entry<String, String> page : s.pages().entrySet()) {
                Files.writeString(rawDir.resolve(page.getKey()), fetcher.get(page.getValue()), StandardCharsets.UTF_8);
            }
        }
    }

    /** Lit les pages de {@code rawDir} et écrit les personnes ; renvoie le message du bilan. */
    String convert(Path rawDir, List<Source> sources, FtmDatasetBuilder dataset) throws IOException {
        int persons = 0;
        List<String> failures = new ArrayList<>();
        for (Source s : sources) {
            List<Person> people = new ArrayList<>();
            try {
                if (s.key().equals("president")) {
                    people.addAll(readCurrentPresident(file(rawDir, "president-cv.html"), s.pages().get("president-cv.html")));
                    people.addAll(readFormerPresidents(file(rawDir, "president-former.html"), s.pages().get("president-former.html")));
                } else {
                    people.addAll(readSolidiumBoard(file(rawDir, "solidium-board.html"), s.pages().get("solidium-board.html")));
                    people.addAll(readSolidiumTeam(file(rawDir, "solidium-team.html"), s.pages().get("solidium-team.html")));
                }
            } catch (IllegalStateException e) {
                failures.add(s.name() + " (" + e.getMessage() + ")");
                continue;
            }
            for (Person person : people) {
                write(s, person, dataset);
                persons++;
            }
        }
        if (persons == 0) {
            throw new IllegalStateException("Aucune personne lue : " + String.join(" ; ", failures));
        }
        String message = persons + " personnes lues sur " + (sources.size() - failures.size()) + " source(s)";
        return failures.isEmpty() ? message + "." : message + ". Sources non lues : " + String.join(" ; ", failures) + ".";
    }

    private static String file(Path rawDir, String name) throws IOException {
        Path f = rawDir.resolve(name);
        if (!Files.exists(f)) {
            throw new IllegalStateException(name + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    private static void write(Source s, Person p, FtmDatasetBuilder dataset) {
        boolean president = s.key().equals("president");
        FtmEntity person = dataset.newEntity("Person", s.key(), p.name());
        person.addValue("name", p.name());
        person.addValue("alias", p.alias());
        person.addValue("firstName", p.name().substring(0, p.name().indexOf(' ')));
        person.addValue("lastName", p.name().substring(p.name().lastIndexOf(' ') + 1));
        person.addValue("birthDate", p.birthYear());
        person.addValue("country", "fi");
        person.addValue("position", p.role());
        person.addValue("sourceUrl", p.sourceUrl());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", s.key(), p.role());
        position.addValue("name", p.role());
        position.addValue("country", "fi");
        position.addValue("organization", p.organization());
        position.addValue("topics", "gov.national");
        position.addValue("topics", president ? "gov.head" : "gov.soe");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", s.key(), p.name(), p.role());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", p.startYear());
        occupancy.addValue("endDate", p.endYear());
        occupancy.addValue("status", p.endYear() == null ? "current" : "ended");
        occupancy.addValue("description", p.note());
        occupancy.addValue("sourceUrl", p.sourceUrl());
        dataset.addEntity(occupancy);
    }

    /** Le président en fonction : le nom qui suit « President of the Republic » sur la page du curriculum vitae. */
    static List<Person> readCurrentPresident(String html, String url) {
        List<String> lines = lines(html);
        int i = lines.indexOf("President of the Republic");
        if (i < 0 || i + 1 >= lines.size() || !lines.get(i + 1).matches("^\\p{Lu}[\\p{L}'’.-]*(?: \\p{Lu}[\\p{L}'’.-]*){1,3}$")) {
            throw new IllegalStateException("Président en fonction introuvable : la page a peut-être changé.");
        }
        return List.of(new Person(lines.get(i + 1), null, PRESIDENT_ROLE, null, null, null,
                "Président en fonction à la date de lecture de la page ; la page ne donne ni naissance ni date de début", "Présidence de la République", url));
    }

    /** Les anciens présidents vivants : « Nom, Prénoms (AAAA–) » puis « AAAA–AAAA ». */
    static List<Person> readFormerPresidents(String html, String url) {
        List<String> lines = lines(html);
        List<Person> out = new ArrayList<>();
        boolean any = false;
        for (int i = 0; i + 1 < lines.size(); i++) {
            Matcher m = FORMER.matcher(lines.get(i));
            Matcher term = TERM.matcher(lines.get(i + 1));
            if (!m.matches() || !term.matches()) {
                continue;
            }
            any = true;
            if (m.group(4) != null) {
                continue;                                              // décédé
            }
            String surname = m.group(1).trim();
            String given = m.group(2).trim();
            String first = given.split(" ")[0];
            String name = first + " " + surname;
            String full = given + " " + surname;
            out.add(new Person(name, full.equals(name) ? null : full, FORMER_PRESIDENT_ROLE, m.group(3), term.group(1), term.group(2),
                    "Mandat présidentiel " + term.group(1) + "–" + term.group(2), "Présidence de la République", url));
        }
        if (!any) {
            throw new IllegalStateException("Aucun ancien président reconnu : la page a peut-être changé.");
        }
        return out;
    }

    /** Le conseil d'administration de Solidium : nom, rôle éventuel (Chair, Vice Chair), « Read Bio », puis « born AAAA ». */
    static List<Person> readSolidiumBoard(String html, String url) {
        List<String> lines = lines(html);
        List<Person> out = new ArrayList<>();
        for (int i = 1; i + 1 < lines.size(); i++) {
            if (!lines.get(i).equals("Read Bio")) {
                continue;
            }
            String before = lines.get(i - 1);
            boolean hasRole = BOARD_ROLES.contains(before);
            String name = hasRole ? lines.get(i - 2) : before;
            Matcher born = BORN.matcher(lines.get(i + 1));
            if (!name.matches("^\\p{Lu}[\\p{L}'’.-]*(?: \\p{Lu}[\\p{L}'’.-]*){1,3}$")) {
                continue;
            }
            String role = hasRole ? (before.equals("Chair") ? "Président du conseil d'administration de Solidium" : "Vice-président du conseil d'administration de Solidium")
                    : "Membre du conseil d'administration de Solidium";
            out.add(new Person(name, null, role, born.matches() ? born.group(1) : null, null, null,
                    "Société d'investissement détenue par l'État finlandais", "Solidium Oy", url));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun administrateur de Solidium reconnu : la page a peut-être changé.");
        }
        return out;
    }

    /** La direction de Solidium : nom puis titre parmi directeur général, financier et des investissements. */
    static List<Person> readSolidiumTeam(String html, String url) {
        List<String> lines = lines(html);
        List<Person> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String role = EXECUTIVES.get(lines.get(i));
            String name = lines.get(i - 1);
            if (role != null && name.matches("^\\p{Lu}[\\p{L}'’.-]*(?: \\p{Lu}[\\p{L}'’.-]*){1,3}$")) {
                out.add(new Person(name, null, role + " de Solidium", null, null, null,
                        "Société d'investissement détenue par l'État finlandais", "Solidium Oy", url));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant de Solidium reconnu : la page a peut-être changé.");
        }
        return out;
    }

    /** Lignes de texte de la page (partie principale), entités décodées, caractères invisibles retirés. */
    static List<String> lines(String html) {
        String body = html.contains("<main") ? html.substring(html.indexOf("<main")) : html;
        String text = decode(body.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<(br|/p|/div|/li|/h\\d|/a|/span)[^>]*>", "\n").replaceAll("<[^>]+>", " "));
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
                .replace("&aring;", "å").replace("&Auml;", "Ä").replace("&Ouml;", "Ö");
    }

    static List<Source> selected(Map<String, String> p) {
        String keys = p.get("keys");
        if (keys == null || keys.isBlank()) {
            return SOURCES;
        }
        List<Source> out = new ArrayList<>();
        for (String k : keys.split(",")) {
            Source s = SOURCES.stream().filter(x -> x.key().equals(k.trim())).findFirst().orElseThrow(() ->
                    new IllegalArgumentException("Source inconnue dans « keys » : « " + k.trim() + " ». Valeurs possibles : president, solidium."));
            out.add(s);
        }
        return out;
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 1500 : Long.parseLong(v.trim());
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(delayMs);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
