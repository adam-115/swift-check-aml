package com.swiftcheck.aml.plugins.hrpep.institutions;

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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <b>hr_pep_institutions</b> – président de la République, direction de la Banque nationale de Croatie (HNB) et juges de la Cour
 * constitutionnelle, personnes politiquement exposées au sens de la loi croate sur la prévention du blanchiment de capitaux.
 *
 * <p>Six pages officielles (clé du paramètre {@code keys}) :
 * <ul>
 *   <li>{@code president} : {@code predsjednik.hr/predsjednik/} (nom, naissance, première élection) ;</li>
 *   <li>{@code hnb-governor}, {@code hnb-deputy}, {@code hnb-vice} : {@code hnb.hr/.../savjet-i-rukovodstvo/…} (gouverneur, gouverneure
 *       adjointe, vice-gouverneurs : naissance et date de nomination) ; les commentaires HTML sont retirés (la page garde en
 *       commentaire la fiche de l'ancien gouverneur) ;</li>
 *   <li>{@code constitutional-court} et {@code constitutional-court-president} : {@code usud.hr} (« NOM, PRÉNOM (année de naissance) »).</li>
 * </ul>
 * La Cour des comptes (revizija.hr) et la Cour suprême (vsrh.hr) répondent mais ne sont pas lues dans ce lot. Aucun des sites
 * n'interdit les robots sur ces pages ; une page toutes les 2 secondes, User-Agent générique. Type {@code PLUGIN_MANAGED}.
 * Paramètres :
 * <pre>{@code
 * {"keys": "president,hnb-governor,hnb-deputy,hnb-vice,constitutional-court,constitutional-court-president", "delayMs": "2000"}
 * }</pre>
 */
@Extension
public class HrPepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "hr-pep-institutions";
    static final String TOPIC = "role.pep";
    static final long DEFAULT_DELAY_MS = 2000;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Croatie - institutions)";
    private static final List<String> MONTHS = List.of("siječnja", "veljače", "ožujka", "travnja", "svibnja", "lipnja", "srpnja",
            "kolovoza", "rujna", "listopada", "studenoga", "prosinca");
    private static final String DATE = "(\\d{1,2})\\. (\\p{L}+) (\\d{4})\\.";
    private static final Pattern BORN_DATE_FIRST = Pattern.compile("[Rr]ođen(?:a)? je " + DATE + "(?: godine)?(?: u ([\\p{L} ]+?)[.,])?");
    private static final Pattern BORN_PLACE_FIRST = Pattern.compile("[Rr]ođen(?:a)? je u ([\\p{L} ]+?) " + DATE);
    private static final Pattern STRONG = Pattern.compile("<strong>([^<]{4,80})</strong>");
    private static final Pattern TITLES = Pattern.compile("^(?:(?:Dr|Prof|Mr|Izv|Doc|sc|dr)\\.? ?)+");
    private static final Pattern JUDGE = Pattern.compile(
            "^((?:(?i:izv|prof|doc|mr|dr|sc)\\. ?)*)([\\p{Lu}'’-]+(?: [\\p{Lu}'’-]+)*), ([\\p{Lu}'’-]+(?: [\\p{Lu}'’-]+)*) \\((\\d{4})\\.\\)$");

    /** Une page source : clé, fichier brut, adresse. */
    record Source(String key, String file, String url) {
    }

    static final List<Source> SOURCES = List.of(
            new Source("president", "president.html", "https://www.predsjednik.hr/predsjednik/"),
            new Source("hnb-governor", "hnb-governor.html", "https://www.hnb.hr/o-nama/funkcije-i-struktura/savjet-i-rukovodstvo/guverner"),
            new Source("hnb-deputy", "hnb-deputy.html", "https://www.hnb.hr/o-nama/funkcije-i-struktura/savjet-i-rukovodstvo/zamjenica-guvernera"),
            new Source("hnb-vice", "hnb-vice.html", "https://www.hnb.hr/o-nama/funkcije-i-struktura/savjet-i-rukovodstvo/viceguverneri"),
            new Source("constitutional-court", "usud-judges.html", "https://www.usud.hr/hr/suci-ustavnog-suda-republike-hrvatske"),
            new Source("constitutional-court-president", "usud-president.html",
                    "https://www.usud.hr/hr/predsjednik-ustavnog-suda-republike-hrvatske"));

    /** Un titulaire : {@code birth} et {@code start} sont des dates ISO, éventuellement partielles (année seule). */
    record Holder(String name, String role, String organization, String birth, String birthPlace, String start, String topic, String url) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<Source> selected = selected(p);
        download(httpFetcher(delayMs(p)), selected, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), selected, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
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

    /** Lit les pages de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, List<Source> sources, FtmDatasetBuilder dataset) throws IOException {
        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        int persons = 0;
        for (Source s : sources) {
            Path file = rawDir.resolve(s.file());
            if (!Files.exists(file)) {
                throw new IllegalStateException(s.file() + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
            for (Holder h : holders(s, Files.readString(file, StandardCharsets.UTF_8))) {
                FtmEntity person = dataset.newEntity("Person", h.name(), String.valueOf(h.birth()));
                person.addValue("name", h.name());
                person.addValue("birthDate", h.birth());
                person.addValue("birthPlace", h.birthPlace());
                person.addValue("country", "hr");
                person.addValue("nationality", "hr");
                person.addValue("position", h.role());
                person.addValue("sourceUrl", h.url());
                dataset.addEntity(person);

                FtmEntity position = positions.get(h.role());
                if (position == null) {
                    position = dataset.newEntity("Position", h.organization(), h.role());
                    position.addValue("name", h.role());
                    position.addValue("country", "hr");
                    position.addValue("organization", h.organization());
                    position.addValue("topics", "gov.national");
                    position.addValue("topics", h.topic());
                    dataset.addEntity(position);
                    positions.put(h.role(), position);
                }
                FtmEntity occupancy = dataset.newEntity("Occupancy", h.name(), String.valueOf(h.birth()), h.role());
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", position.getId());
                occupancy.addValue("startDate", h.start());
                occupancy.addValue("status", "current");
                occupancy.addValue("sourceUrl", h.url());
                dataset.addEntity(occupancy);
                persons++;
            }
        }
        return persons + " personnes lues sur " + sources.size() + " page(s). Sources : Présidence, Banque nationale de Croatie et Cour constitutionnelle (sites officiels).";
    }

    static List<Holder> holders(Source s, String html) {
        return switch (s.key()) {
            case "president" -> president(html, s.url());
            case "hnb-governor" -> hnb(html, s.url(), "Gouverneur de la Banque nationale de Croatie (guverner HNB)");
            case "hnb-deputy" -> hnb(html, s.url(), "Gouverneur adjoint de la Banque nationale de Croatie (zamjenik guvernera HNB)");
            case "hnb-vice" -> hnb(html, s.url(), "Vice-gouverneur de la Banque nationale de Croatie (viceguverner HNB)");
            case "constitutional-court" -> court(html, s.url(), "Juge de la Cour constitutionnelle (sudac Ustavnog suda)");
            case "constitutional-court-president" -> court(html, s.url(), "Président de la Cour constitutionnelle (predsjednik Ustavnog suda)");
            default -> throw new IllegalArgumentException("Source inconnue : " + s.key());
        };
    }

    static List<Holder> president(String html, String url) {
        List<String> lines = lines(html);
        int at = lines.indexOf("PREDSJEDNIK REPUBLIKE HRVATSKE");
        String name = null;
        if (at >= 0 && at + 1 < lines.size() && lines.get(at + 1).matches("^[\\p{Lu}'’-]+(?: [\\p{Lu}'’-]+)+$")) {
            name = titleWords(lines.get(at + 1));
        }
        String text = String.join(" ", lines);
        Matcher born = BORN_DATE_FIRST.matcher(text);
        Matcher first = Pattern.compile("(\\d{4})\\. godine postaje (?:peti )?predsjednik").matcher(text);
        if (name == null || !born.find() || !first.find()) {
            throw new IllegalStateException("Page du président : nom, naissance ou première élection introuvables (la page a changé).");
        }
        return List.of(new Holder(name, "Président de la République (Predsjednik Republike Hrvatske)", "Predsjednik Republike Hrvatske",
                iso(born.group(1), born.group(2), born.group(3)), born.group(4) == null ? null : born.group(4).trim(), first.group(1),
                "gov.head", url));
    }

    /** Une page HNB : un bloc par {@code <strong>Nom</strong>}, commentaires HTML retirés. */
    static List<Holder> hnb(String html, String url, String role) {
        String clean = html.replaceAll("(?s)<!--.*?-->", "");
        Matcher n = STRONG.matcher(clean);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (n.find()) {
            names.add(TITLES.matcher(decode(n.group(1)).trim()).replaceFirst("").trim());
            spans.add(new int[] {n.end()});
        }
        List<Holder> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            int to = i + 1 < names.size() ? clean.indexOf("<strong>", spans.get(i)[0]) : clean.length();
            if (to < 0) {
                to = clean.length();
            }
            String text = decode(clean.substring(spans.get(i)[0], Math.max(to, spans.get(i)[0])).replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ");
            String birth = null;
            String place = null;
            Matcher b = BORN_DATE_FIRST.matcher(text);
            Matcher p = BORN_PLACE_FIRST.matcher(text);
            if (b.find() && (!p.find() || b.start() <= p.start())) {
                birth = iso(b.group(1), b.group(2), b.group(3));
                place = b.group(4) == null ? null : b.group(4).trim();
            } else if (p.find(0)) {
                birth = iso(p.group(2), p.group(3), p.group(4));
                place = p.group(1).trim();
            }
            int odlukom = text.indexOf("Odlukom");
            String start = null;
            if (odlukom >= 0) {
                Matcher d = Pattern.compile(DATE).matcher(text.substring(odlukom));
                if (d.find()) {
                    start = iso(d.group(1), d.group(2), d.group(3));
                }
            }
            out.add(new Holder(names.get(i), role, "Hrvatska narodna banka (Banque nationale de Croatie)", birth, place, start, "gov.financial", url));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page de la HNB : aucun nom trouvé (la page a changé).");
        }
        return out;
    }

    /** « NOM, PRÉNOM (1969.) » : nom et prénom en capitales, année de naissance seule. */
    static List<Holder> court(String html, String url, String role) {
        List<Holder> out = new ArrayList<>();
        for (String line : lines(html)) {
            Matcher m = JUDGE.matcher(line);
            if (m.matches()) {
                out.add(new Holder(titleWords(m.group(3)) + " " + titleWords(m.group(2)), role, "Ustavni sud Republike Hrvatske (Cour constitutionnelle)",
                        m.group(4), null, null, "gov.judicial", url));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page de la Cour constitutionnelle : aucun juge trouvé (la page a changé).");
        }
        return out;
    }

    /** « MAROCHINI ZRINSKI » → « Marochini Zrinski » ; « BEZBRADICA-X » → « Bezbradica-X ». */
    static String titleWords(String upper) {
        return Arrays.stream(upper.split(" ")).map(w -> Arrays.stream(w.split("-"))
                .map(p -> p.isEmpty() ? p : p.charAt(0) + p.substring(1).toLowerCase(Locale.ROOT)).reduce((a, b) -> a + "-" + b).orElse(w))
                .reduce((a, b) -> a + " " + b).orElse(upper);
    }

    /** « 8. svibnja 1972. » → 1972-05-08 ; {@code null} si illisible. */
    static String iso(String day, String month, String year) {
        int index = MONTHS.indexOf(month);
        if (index < 0 && month.startsWith("studen")) {
            index = 10;                                   // « studenoga » ou « studenog »
        }
        if (index < 0) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(year), index + 1, Integer.parseInt(day)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Lignes de texte non vides de la page, sans balises ni commentaires, entités décodées. */
    static List<String> lines(String html) {
        List<String> out = new ArrayList<>();
        String body = html.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<svg.*?</svg>", "").replaceAll("(?s)<script.*?</script>", "")
                .replaceAll("(?s)<style.*?</style>", "");
        for (String raw : body.replaceAll("<[^>]+>", "\n").split("\n")) {
            String line = decode(raw).replaceAll("\\s+", " ").trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    static String decode(String s) {
        String r = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#8220;", "“").replace("&#8221;", "”");
        Matcher m = Pattern.compile("&#(\\d+);").matcher(r);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(m.group(1))))));
        }
        m.appendTail(sb);
        return sb.toString().replace(' ', ' ');
    }

    static List<Source> selected(Map<String, String> p) {
        String keys = p.get("keys");
        if (keys == null || keys.isBlank()) {
            return SOURCES;
        }
        List<Source> out = new ArrayList<>();
        for (String key : Arrays.stream(keys.split(",")).map(String::trim).filter(k -> !k.isEmpty()).toList()) {
            out.add(SOURCES.stream().filter(s -> s.key().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Source inconnue : « " + key + " » (valeurs : "
                            + SOURCES.stream().map(Source::key).toList() + ").")));
        }
        return out;
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        if (v == null || v.isBlank()) {
            return DEFAULT_DELAY_MS;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « delayMs » invalide : « " + v + " » (nombre de millisecondes attendu).");
        }
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
