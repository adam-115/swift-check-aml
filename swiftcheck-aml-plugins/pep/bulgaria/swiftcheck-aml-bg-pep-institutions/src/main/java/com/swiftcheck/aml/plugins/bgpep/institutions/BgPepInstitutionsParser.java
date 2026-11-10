package com.swiftcheck.aml.plugins.bgpep.institutions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <b>bg_pep_institutions</b> – président de la République et juges de la Cour constitutionnelle de Bulgarie, personnes
 * politiquement exposées au sens de la loi bulgare sur les mesures contre le blanchiment d'argent (ЗМИП).
 *
 * <p>Sources officielles (robots.txt sans restriction utile, 1 seconde entre les appels, User-Agent générique) :
 * <ul>
 *   <li>{@code www.president.bg/cat9/Biografia-na-prezidenta/} : la biographie du président en exercice (nom, naissance, date de
 *       prise de fonction) ;</li>
 *   <li>{@code www.constcourt.bg/bg/judges} : les juges (nom, fonction, date et lieu de naissance).</li>
 * </ul>
 * Chaque nom reçoit en alias l'ordre inverse et la translittération latine officielle (système rationalisé, loi de 2009).
 * Non lus : la Banque nationale (le menu du site est généré par script), la Cour des comptes (biographies sans date de naissance
 * et accordéons), les cours suprêmes.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"presidentUrl": "https://www.president.bg/cat9/Biografia-na-prezidenta/",
 *  "judgesUrl": "https://www.constcourt.bg/bg/judges"}
 * }</pre>
 */
@Extension
public class BgPepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "bg-pep-institutions";
    static final String TOPIC = "role.pep";
    static final String PRESIDENT_FILE = "president.html";
    static final String JUDGES_FILE = "judges.html";
    static final String DEFAULT_PRESIDENT_URL = "https://www.president.bg/cat9/Biografia-na-prezidenta/";
    static final String DEFAULT_JUDGES_URL = "https://www.constcourt.bg/bg/judges";
    static final String PRESIDENT_ROLE = "Президент на Република България";
    static final String COURT_CHAIR_ROLE = "Председател на Конституционния съд";
    static final String COURT_JUDGE_ROLE = "Съдия от Конституционния съд";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Bulgarie - institutions)";
    private static final long DELAY_MS = 1000;
    private static final String NAME = "\\p{Lu}\\p{Ll}+(?:[ -]\\p{Lu}\\p{Ll}+){1,3}";
    private static final String DATE = "(\\d{1,2}) (\\p{L}+) (\\d{4})";
    private static final Pattern PRESIDENT_BIRTH = Pattern.compile("(" + NAME + ") е роден(?:а)? на " + DATE);
    private static final Pattern PRESIDENT_START = Pattern.compile("На " + DATE + " г\\. " + NAME + " встъпва в длъжност като държавен глава");
    private static final Pattern JUDGE_CHUNK = Pattern.compile("<div class=\"person-item\"[^>]*>(.*?)(?=<div class=\"person-item\"|</section>|$)", Pattern.DOTALL);
    private static final Pattern JUDGE_NAME = Pattern.compile("<h4 class=\"person-name\">\\s*([^<]+?)\\s*</h4>");
    private static final Pattern WORD_DATE = Pattern.compile("(?:Роден(?:а)?|роден(?:а)?) на " + DATE + "\\s*(?:г\\.|год\\.|г)");
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?:Роден(?:а)?|роден(?:а)?) на (\\d{2})\\.(\\d{2})\\.(\\d{4})\\s*(?:г\\.|год\\.|г)?");
    private static final Pattern BIRTH_PLACE = Pattern.compile("\\sв (?:гр\\.|с\\.)?\\s*([\\p{L}][\\p{L} -]*?)(?:,|\\.|$| област)");
    private static final List<String> MONTHS = List.of("януари", "февруари", "март", "април", "май", "юни", "юли", "август", "септември",
            "октомври", "ноември", "декември");

    /** Une personne lue : fonction, naissance, et début de fonction si la page le donne. */
    record Holder(String name, String role, LocalDate birthDate, String birthPlace, LocalDate start) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(), param(p, "presidentUrl", DEFAULT_PRESIDENT_URL), param(p, "judgesUrl", DEFAULT_JUDGES_URL), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), param(p, "presidentUrl", DEFAULT_PRESIDENT_URL), param(p, "judgesUrl", DEFAULT_JUDGES_URL), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), DEFAULT_PRESIDENT_URL, DEFAULT_JUDGES_URL, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    static void download(Fetcher fetcher, String presidentUrl, String judgesUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Files.writeString(rawDir.resolve(PRESIDENT_FILE), fetcher.get(presidentUrl), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve(JUDGES_FILE), fetcher.get(judgesUrl), StandardCharsets.UTF_8);
    }

    /** Lit les deux pages de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, String presidentUrl, String judgesUrl, FtmDatasetBuilder dataset) throws IOException {
        Path president = rawDir.resolve(PRESIDENT_FILE);
        Path judges = rawDir.resolve(JUDGES_FILE);
        if (!Files.exists(president) || !Files.exists(judges)) {
            throw new IllegalStateException(PRESIDENT_FILE + " ou " + JUDGES_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Holder> all = new ArrayList<>();
        Holder head = president(Files.readString(president, StandardCharsets.UTF_8));
        all.add(head);
        List<Holder> court = judges(Files.readString(judges, StandardCharsets.UTF_8));
        all.addAll(court);

        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        for (Holder h : all) {
            String url = h == head ? presidentUrl : judgesUrl;
            String key = transliterate(h.name()).toLowerCase() + (h.birthDate() == null ? "" : "|" + h.birthDate());
            FtmEntity person = dataset.newEntity("Person", key);
            person.addValue("name", h.name());
            for (String alias : aliases(h.name())) {
                person.addValue("alias", alias);
            }
            person.addValue("country", "bg");
            person.addValue("nationality", "bg");
            if (h.birthDate() != null) {
                person.addValue("birthDate", h.birthDate().toString());
            }
            if (h.birthPlace() != null) {
                person.addValue("birthPlace", h.birthPlace());
            }
            person.addValue("position", h.role());
            person.addValue("sourceUrl", url);
            dataset.addEntity(person);

            FtmEntity position = positions.get(h.role());
            if (position == null) {
                position = dataset.newEntity("Position", transliterate(h.role()));
                position.addValue("name", h.role());
                position.addValue("country", "bg");
                position.addValue("topics", "gov.national");
                if (h.role().equals(PRESIDENT_ROLE)) {
                    position.addValue("organization", "Президент на Република България");
                    position.addValue("topics", "gov.executive");
                    position.addValue("topics", "gov.head");
                } else {
                    position.addValue("organization", "Конституционен съд на Република България");
                    position.addValue("topics", "gov.judicial");
                }
                dataset.addEntity(position);
                positions.put(h.role(), position);
            }
            FtmEntity occupancy = dataset.newEntity("Occupancy", key, transliterate(h.role()));
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", h.start() == null ? null : h.start().toString());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", url);
            dataset.addEntity(occupancy);
        }
        return "1 président de la République et " + court.size() + " juges de la Cour constitutionnelle. "
                + "Source : www.president.bg et www.constcourt.bg.";
    }

    /** Le président : nom et naissance (« Илияна Йотова е родена на 24 октомври 1964 г. ») et prise de fonction. */
    static Holder president(String html) {
        String text = text(html.replaceAll("(?s)<script.*?</script>", " "));
        Matcher b = PRESIDENT_BIRTH.matcher(text);
        if (!b.find()) {
            throw new IllegalStateException("Page « Биография на президента » : nom et naissance introuvables (la page a changé).");
        }
        LocalDate birth = day(b.group(2), b.group(3), b.group(4));
        LocalDate start = null;
        Matcher s = PRESIDENT_START.matcher(text);
        if (s.find()) {
            start = day(s.group(1), s.group(2), s.group(3));
        }
        return new Holder(b.group(1), PRESIDENT_ROLE, birth, null, start);
    }

    /** Les juges de la page « Състав » : un bloc {@code person-item} par juge, avec son nom, sa fonction et sa naissance. */
    static List<Holder> judges(String html) {
        List<Holder> out = new ArrayList<>();
        Matcher chunks = JUDGE_CHUNK.matcher(html);
        while (chunks.find()) {
            String chunk = chunks.group(1);
            Matcher n = JUDGE_NAME.matcher(chunk);
            if (!n.find()) {
                continue;
            }
            String name = text(n.group(1));
            String text = text(chunk);
            LocalDate birth = null;
            Matcher w = WORD_DATE.matcher(text);
            Matcher d = NUMERIC_DATE.matcher(text);
            String after = null;
            if (w.find()) {
                birth = day(w.group(1), w.group(2), w.group(3));
                after = text.substring(w.end());
            } else if (d.find()) {
                birth = LocalDate.of(Integer.parseInt(d.group(3)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(1)));
                after = text.substring(d.end());
            }
            String place = null;
            if (after != null) {
                Matcher p = BIRTH_PLACE.matcher(" " + after.trim().replaceAll("^г\\.", "").trim());
                if (p.find()) {
                    place = p.group(1).trim();
                }
            }
            String role = text.contains(name + " Председател") ? COURT_CHAIR_ROLE : COURT_JUDGE_ROLE;
            out.add(new Holder(name, role, birth, place, null));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page « Състав » de la Cour constitutionnelle : aucun juge trouvé (la page a changé).");
        }
        return out;
    }

    private static LocalDate day(String d, String month, String year) {
        int m = MONTHS.indexOf(month.toLowerCase()) + 1;
        if (m == 0) {
            return null;
        }
        return LocalDate.of(Integer.parseInt(year), m, Integer.parseInt(d));
    }

    /** Noms en alias : ordre inverse et translittération latine officielle. */
    static Set<String> aliases(String name) {
        List<String> words = List.of(name.split(" "));
        Set<String> cyrillic = new LinkedHashSet<>();
        if (words.size() >= 2) {
            cyrillic.add(words.get(words.size() - 1) + " " + String.join(" ", words.subList(0, words.size() - 1)));
            if (words.size() == 3) {
                cyrillic.add(words.get(0) + " " + words.get(2));
                cyrillic.add(words.get(2) + " " + words.get(0));
            }
        }
        Set<String> out = new LinkedHashSet<>(cyrillic);
        out.add(name);
        Set<String> latin = new LinkedHashSet<>();
        for (String a : out) {
            String t = transliterate(a);
            latin.add(t);
            if (t.contains("iya") || t.contains("Iya")) {
                latin.add(t.replace("iya", "ia").replace("Iya", "Ia"));        // usage courant : Iliana, Mariana…
            }
        }
        out.addAll(latin);
        out.remove(name);
        return out;
    }

    private static final Map<Character, String> LATIN = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"), Map.entry('д', "d"),
            Map.entry('е', "e"), Map.entry('ж', "zh"), Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('й', "y"),
            Map.entry('к', "k"), Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"),
            Map.entry('п', "p"), Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"), Map.entry('у', "u"),
            Map.entry('ф', "f"), Map.entry('х', "h"), Map.entry('ц', "ts"), Map.entry('ч', "ch"), Map.entry('ш', "sh"),
            Map.entry('щ', "sht"), Map.entry('ъ', "a"), Map.entry('ь', "y"), Map.entry('ю', "yu"), Map.entry('я', "ya"));

    /** Translittération officielle bulgare (système rationalisé) : « Павлина Панова » → « Pavlina Panova » ; « -ия » final → « -ia ». */
    static String transliterate(String text) {
        StringBuilder out = new StringBuilder();
        String[] words = text.split(" ");
        for (int w = 0; w < words.length; w++) {
            String lower = words[w].toLowerCase();
            boolean endsIya = lower.endsWith("ия") && lower.length() > 2;
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < lower.length(); i++) {
                if (endsIya && i == lower.length() - 2) {
                    t.append("ia");
                    break;
                }
                t.append(LATIN.getOrDefault(lower.charAt(i), String.valueOf(lower.charAt(i))));
            }
            boolean upper = true;
            StringBuilder cased = new StringBuilder();
            for (char c : t.toString().toCharArray()) {
                cased.append(upper ? Character.toUpperCase(c) : c);
                upper = c == '-' || c == '\'';
            }
            out.append(w == 0 ? "" : " ").append(cased);
        }
        return out.toString();
    }

    /** Texte d'un fragment HTML : balises retirées, entités courantes décodées, espaces réduits. */
    static String text(String html) {
        return html.replaceAll("(?s)<!--.*?-->", " ").replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&ndash;", "–").replace("&laquo;", "«")
                .replace("&raquo;", "»").replace("&bdquo;", "„").replace("&ldquo;", "“").replace("&rdquo;", "”").replace(' ', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    private static String param(Map<String, String> p, String name, String fallback) {
        String v = p.get(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(DELAY_MS);
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
