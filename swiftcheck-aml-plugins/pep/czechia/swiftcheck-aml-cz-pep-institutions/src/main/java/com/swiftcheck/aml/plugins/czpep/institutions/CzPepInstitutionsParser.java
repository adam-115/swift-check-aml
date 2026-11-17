package com.swiftcheck.aml.plugins.czpep.institutions;

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

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>cz_pep_institutions</b> – conseil de la Banque nationale tchèque (ČNB) et juges de la Cour suprême (Nejvyšší soud).
 *
 * <p>Sources officielles, pages publiques téléchargées à chaque exécution :
 * <ul>
 *   <li>{@code cnb} : {@code https://www.cnb.cz/cs/o_cnb/bankovni-rada/} – « Členové bankovní rady » : lignes
 *       « guvernér ČNB : Nom », « viceguvernér(ka) ČNB : Nom », « člen(ka) bankovní rady ČNB : Nom » ;</li>
 *   <li>{@code ns-civil} et {@code ns-criminal} : pages « Soudci občanskoprávního a obchodního kolegia » et « Soudci trestního
 *       kolegia » de {@code https://www.nsoud.cz/o-nejvyssim-soudu/funkcionari-a-soudci/} – une ligne par juge (noms seuls).</li>
 * </ul>
 * Le Sénat (robots.txt interdisant tout), la NKÚ (HTTP 403) et la Cour constitutionnelle (site injoignable) ne sont pas utilisés.
 * Type {@code PLUGIN_MANAGED}. Paramètres (facultatifs) :
 * <pre>{@code
 * {"keys": "cnb,ns-civil,ns-criminal", "delayMs": "1500"}
 * }</pre>
 */
@Extension
public class CzPepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "cz-pep-institutions";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Tchequie - CNB, Nejvyssi soud)";
    private static final Pattern NAME = Pattern.compile("^\\p{Lu}[\\p{L}'’.-]*(?: (?:\\p{Lu}[\\p{L}'’.-]*|von|van|de|ml\\.|st\\.)){1,4}$");
    private static final Pattern BOARD = Pattern.compile("^(guvernér|viceguvernérka|viceguvernér|členka bankovní rady|člen bankovní rady) ČNB: (.+)$");

    /** Une source : clé, nom lisible, fichier brut, adresse, organisation. */
    record Source(String key, String name, String file, String url) {
    }

    static final List<Source> SOURCES = List.of(
            new Source("cnb", "Česká národní banka", "cnb.html", "https://www.cnb.cz/cs/o_cnb/bankovni-rada/"),
            new Source("ns-civil", "Nejvyšší soud", "ns-civil.html",
                    "https://www.nsoud.cz/o-nejvyssim-soudu/funkcionari-a-soudci/soudci-obcanskopravniho-a-obchodniho-kolegia"),
            new Source("ns-criminal", "Nejvyšší soud", "ns-criminal.html",
                    "https://www.nsoud.cz/o-nejvyssim-soudu/funkcionari-a-soudci/soudci-trestniho-kolegia"));

    /** Une personne lue. */
    record Person(String name, String role, String note) {
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
            Files.writeString(rawDir.resolve(s.file()), fetcher.get(s.url()), StandardCharsets.UTF_8);
        }
    }

    /** Lit les pages de {@code rawDir} et écrit les personnes ; renvoie le message du bilan. */
    String convert(Path rawDir, List<Source> sources, FtmDatasetBuilder dataset) throws IOException {
        int persons = 0;
        List<String> failures = new ArrayList<>();
        for (Source s : sources) {
            Path file = rawDir.resolve(s.file());
            if (!Files.exists(file)) {
                throw new IllegalStateException(s.file() + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
            List<Person> people;
            try {
                String html = Files.readString(file, StandardCharsets.UTF_8);
                people = s.key().equals("cnb") ? readBoard(html)
                        : readJudges(html, s.key().equals("ns-civil") ? "Soudci občanskoprávního a obchodního kolegia" : "Soudci trestního kolegia");
            } catch (IllegalStateException e) {
                failures.add(s.key() + " (" + e.getMessage() + ")");
                continue;
            }
            for (Person p : people) {
                write(s, p, dataset);
                persons++;
            }
        }
        if (persons == 0) {
            throw new IllegalStateException("Aucune personne lue : " + String.join(" ; ", failures));
        }
        String message = persons + " personnes lues sur " + (sources.size() - failures.size()) + " source(s)";
        return failures.isEmpty() ? message + "." : message + ". Sources non lues : " + String.join(" ; ", failures) + ".";
    }

    private static void write(Source s, Person p, FtmDatasetBuilder dataset) {
        boolean bank = s.key().equals("cnb");
        FtmEntity person = dataset.newEntity("Person", p.name());
        person.addValue("name", p.name());
        person.addValue("firstName", p.name().substring(0, p.name().indexOf(' ')));
        person.addValue("lastName", p.name().substring(p.name().lastIndexOf(' ') + 1));
        person.addValue("country", "cz");
        person.addValue("position", p.role());
        person.addValue("sourceUrl", s.url());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", s.name(), p.role());
        position.addValue("name", p.role());
        position.addValue("country", "cz");
        position.addValue("organization", s.name());
        position.addValue("topics", "gov.national");
        position.addValue("topics", bank ? "gov.financial" : "gov.judicial");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", p.name(), s.name(), p.role());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", p.note());
        occupancy.addValue("sourceUrl", s.url());
        dataset.addEntity(occupancy);
    }

    /** Le conseil de la ČNB : « guvernér ČNB: Nom », « viceguvernérka ČNB: Nom », « člen bankovní rady ČNB: Nom ». */
    static List<Person> readBoard(String html) {
        List<Person> out = new ArrayList<>();
        for (String line : lines(html)) {
            Matcher m = BOARD.matcher(line);
            if (m.matches() && NAME.matcher(m.group(2)).matches()) {
                String title = m.group(1);
                String role = title.equals("guvernér") ? "Gouverneur de la banque nationale (guvernér ČNB)"
                        : title.startsWith("viceguvernér") ? "Vice-gouverneur de la banque nationale (viceguvernér ČNB)"
                        : "Membre du conseil de la banque nationale (člen bankovní rady ČNB)";
                out.add(new Person(m.group(2), role, "Fonction publiée : " + line.substring(0, line.indexOf(':')).trim()));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun membre du conseil de la ČNB reconnu : la page a peut-être changé.");
        }
        return out;
    }

    /** Les juges d'un collège : les lignes de nom qui suivent le titre de la liste, jusqu'à « Obecné informace ». */
    static List<Person> readJudges(String html, String heading) {
        List<String> lines = lines(html);
        List<Person> out = new ArrayList<>();
        for (int start = 0; start < lines.size() && out.isEmpty(); start++) {
            if (!lines.get(start).equals(heading) || start + 1 >= lines.size() || !NAME.matcher(lines.get(start + 1)).matches()) {
                continue;
            }
            for (int i = start + 1; i < lines.size() && !lines.get(i).equals("Obecné informace"); i++) {
                if (NAME.matcher(lines.get(i)).matches()) {
                    out.add(new Person(lines.get(i), "Juge de la Cour suprême (soudce Nejvyššího soudu)", "Liste : " + heading));
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun juge reconnu sous « " + heading + " » : la page a peut-être changé.");
        }
        return out;
    }

    /** Lignes de texte de la page, entités décodées, caractères invisibles retirés. */
    static List<String> lines(String html) {
        String text = decode(html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<(br|/p|/div|/li|/h\\d|/a|/span)[^>]*>", "\n").replaceAll("<[^>]+>", " "));
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replaceAll("[\\u200B-\\u200F\\u2060\\uFEFF\\u00AD]", "").replaceAll("[\\u00A0\\u202F]", " ")
                    .replaceAll("\\s+", " ").trim();
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
        return sb.toString().replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"");
    }

    static List<Source> selected(Map<String, String> p) {
        String keys = p.get("keys");
        if (keys == null || keys.isBlank()) {
            return SOURCES;
        }
        Map<String, Source> byKey = new LinkedHashMap<>();
        SOURCES.forEach(s -> byKey.put(s.key(), s));
        List<Source> out = new ArrayList<>();
        for (String k : keys.split(",")) {
            Source s = byKey.get(k.trim());
            if (s == null) {
                throw new IllegalArgumentException("Source inconnue dans « keys » : « " + k.trim() + " ». Valeurs possibles : " + byKey.keySet() + ".");
            }
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
