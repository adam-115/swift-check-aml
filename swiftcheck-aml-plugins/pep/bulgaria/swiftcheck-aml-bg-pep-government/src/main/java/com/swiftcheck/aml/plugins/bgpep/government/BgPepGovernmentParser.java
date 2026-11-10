package com.swiftcheck.aml.plugins.bgpep.government;

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
 * <b>bg_pep_government</b> – Premier ministre, vice-premiers ministres et ministres du Conseil des ministres bulgare, personnes
 * politiquement exposées au sens de la loi bulgare sur les mesures contre le blanchiment d'argent (ЗМИП).
 *
 * <p>Source officielle : le site du Conseil des ministres ({@code www.government.bg}), deux pages (robots.txt sans restriction) :
 * <ul>
 *   <li>« Състав на правителството / Биографии » : un lien par membre, « Nom - fonction » ;</li>
 *   <li>« Министър-председател / Биография » : le Premier ministre, avec sa date et son lieu de naissance (les fiches des autres
 *       membres n'en donnent pas).</li>
 * </ul>
 * Chaque nom reçoit en alias l'ordre inverse et la translittération latine officielle (système rationalisé, loi de 2009). Les dates
 * de prise de fonction ne sont pas publiées sur ces pages.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"membersUrl": "https://www.government.bg/bg/pravitelstvo/sastav-na-pravitelstvoto",
 *  "primeMinisterUrl": "https://www.government.bg/bg/pravitelstvo/ministar-predsedatel"}
 * }</pre>
 */
@Extension
public class BgPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "bg-pep-government";
    static final String TOPIC = "role.pep";
    static final String MEMBERS_FILE = "members.html";
    static final String PM_FILE = "pm.html";
    static final String DEFAULT_MEMBERS_URL = "https://www.government.bg/bg/pravitelstvo/sastav-na-pravitelstvoto";
    static final String DEFAULT_PM_URL = "https://www.government.bg/bg/pravitelstvo/ministar-predsedatel";
    static final String PM_ROLE = "министър-председател";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Bulgarie - Conseil des ministres)";
    private static final long DELAY_MS = 1000;
    private static final Pattern ANCHOR = Pattern.compile("<a\\s[^>]*href=\"[^\"]*/biografii/[^\"]+\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern PM_NAME = Pattern.compile("<h1>\\s*([^<]+?)\\s*</h1>");
    private static final Pattern PM_SENTENCE = Pattern.compile("(\\p{L}[\\p{L} .'-]+?) е министър-председател на Република България");
    private static final Pattern BIRTH = Pattern.compile(
            "Роден(?:а)? е на (\\d{1,2}) (\\p{L}+) (\\d{4}) г\\.(?: в ([^.<]+))?");
    private static final List<String> MONTHS = List.of("януари", "февруари", "март", "април", "май", "юни", "юли", "август", "септември",
            "октомври", "ноември", "декември");

    /** Un membre du gouvernement : nom, fonction, et naissance si la page la donne. */
    record Member(String name, String role, LocalDate birthDate, String birthPlace) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(), param(p, "membersUrl", DEFAULT_MEMBERS_URL), param(p, "primeMinisterUrl", DEFAULT_PM_URL), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), param(p, "membersUrl", DEFAULT_MEMBERS_URL), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), DEFAULT_MEMBERS_URL, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    static void download(Fetcher fetcher, String membersUrl, String pmUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Files.writeString(rawDir.resolve(MEMBERS_FILE), fetcher.get(membersUrl), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve(PM_FILE), fetcher.get(pmUrl), StandardCharsets.UTF_8);
    }

    /** Lit les deux pages de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, String url, FtmDatasetBuilder dataset) throws IOException {
        Path members = rawDir.resolve(MEMBERS_FILE);
        Path pm = rawDir.resolve(PM_FILE);
        if (!Files.exists(members) || !Files.exists(pm)) {
            throw new IllegalStateException(MEMBERS_FILE + " ou " + PM_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Member> all = new ArrayList<>();
        all.add(primeMinister(Files.readString(pm, StandardCharsets.UTF_8)));
        for (Member m : members(Files.readString(members, StandardCharsets.UTF_8))) {
            if (all.stream().noneMatch(x -> x.name().equalsIgnoreCase(m.name()))) {
                all.add(m);
            }
        }

        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        for (Member m : all) {
            FtmEntity person = dataset.newEntity("Person", transliterate(m.name()));
            person.addValue("name", m.name());
            for (String alias : aliases(m.name())) {
                person.addValue("alias", alias);
            }
            person.addValue("country", "bg");
            person.addValue("nationality", "bg");
            if (m.birthDate() != null) {
                person.addValue("birthDate", m.birthDate().toString());
            }
            if (m.birthPlace() != null) {
                person.addValue("birthPlace", m.birthPlace());
            }
            person.addValue("position", m.role());
            person.addValue("sourceUrl", url);
            dataset.addEntity(person);

            FtmEntity position = positions.get(m.role());
            if (position == null) {
                position = dataset.newEntity("Position", transliterate(m.role()));
                position.addValue("name", m.role() + " (Bulgarie)");
                position.addValue("country", "bg");
                position.addValue("organization", "Министерски съвет на Република България (Conseil des ministres)");
                position.addValue("topics", "gov.national");
                position.addValue("topics", "gov.executive");
                if (m.role().equals(PM_ROLE)) {
                    position.addValue("topics", "gov.head");
                }
                dataset.addEntity(position);
                positions.put(m.role(), position);
            }
            FtmEntity occupancy = dataset.newEntity("Occupancy", transliterate(m.name()), transliterate(m.role()));
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", url);
            dataset.addEntity(occupancy);
        }
        return all.size() + " membres du Gouvernement. Source : Министерски съвет, www.government.bg.";
    }

    /** Les membres listés par la page « Състав на правителството / Биографии » : « Nom - fonction ». */
    static List<Member> members(String html) {
        List<Member> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            String text = clean(m.group(1));
            int cut = text.lastIndexOf(" - ");
            if (text.isEmpty() || cut < 0) {
                continue;                    // lien-image sans texte, ou texte sans fonction
            }
            String name = normalizeName(text.substring(0, cut));
            String role = text.substring(cut + 3).trim();
            if (!name.isEmpty() && !role.isEmpty() && seen.add(name.toLowerCase())) {
                out.add(new Member(name, role, null, null));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page « Биографии » : aucun membre du Gouvernement trouvé (la page a changé).");
        }
        return out;
    }

    /** Le Premier ministre : nom (titre de page), naissance (« Роден е на 18 юни 1963 г. в Димитровград »). */
    static Member primeMinister(String html) {
        Matcher n = PM_NAME.matcher(html);
        String name = null;
        if (n.find()) {
            name = normalizeName(clean(n.group(1)));
        }
        String text = clean(html.replaceAll("(?s)<script.*?</script>", " "));
        if (name == null || name.isEmpty()) {
            Matcher s = PM_SENTENCE.matcher(text);
            if (s.find()) {
                name = normalizeName(s.group(1));
            }
        }
        if (name == null || name.isEmpty() || !text.contains("е министър-председател на Република България")) {
            throw new IllegalStateException("Page « Министър-председател » : nom du Premier ministre introuvable (la page a changé).");
        }
        LocalDate birth = null;
        String place = null;
        Matcher b = BIRTH.matcher(text);
        if (b.find()) {
            int month = MONTHS.indexOf(b.group(2).toLowerCase()) + 1;
            if (month > 0) {
                birth = LocalDate.of(Integer.parseInt(b.group(3)), month, Integer.parseInt(b.group(1)));
            }
            place = b.group(4) == null ? null : b.group(4).trim();
        }
        return new Member(name, PM_ROLE, birth, place);
    }

    /** Titre retiré (« Проф. », « Доц. », « Д-р »), tiret demi-cadratin d'un nom composé ramené à un trait d'union. */
    static String normalizeName(String raw) {
        String s = raw.replaceAll("(?iu)^(проф\\.?|доц\\.?|д-р|акад\\.?)\\s+", "").replaceAll("\\s*[–—]\\s*", "-").replaceAll("\\s+", " ").trim();
        return s;
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

    /** Translittération officielle bulgare (système rationalisé) : « Гълъб Донев » → « Galab Donev » ; « -ия » final → « -ia ». */
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
    static String clean(String html) {
        return html.replaceAll("(?s)<!--.*?-->", " ").replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&ndash;", "–").replace("&laquo;", "«")
                .replace("&raquo;", "»").replace("&bdquo;", "„").replace("&ldquo;", "“").replace(' ', ' ')
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
