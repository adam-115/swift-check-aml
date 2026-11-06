package com.swiftcheck.aml.plugins.sepep.institutions;

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
 * <b>se_pep_institutions</b> – juges (justitieråd) de la Cour suprême et de la Cour administrative suprême, direction de la
 * Riksbank et Roi de Suède.
 *
 * <p>Sources officielles, pages publiques téléchargées à chaque exécution :
 * <ul>
 *   <li>{@code hd} : {@code https://www.domstol.se/hogsta-domstolen/justitierad/} – chaque juge : nom puis « Ledamot av
 *       Högsta domstolen sedan AAAA », éventuellement « Ordförande i Högsta domstolen sedan AAAA » ;</li>
 *   <li>{@code hfd} : {@code https://www.domstol.se/hogsta-forvaltningsdomstolen/justitierad/} – même gabarit ;</li>
 *   <li>{@code riksbank} : {@code https://www.riksbank.se/sv/om-riksbanken/organisation/direktionen/} – rubrique
 *       « Direktionsledamöter » : nom puis titre (riksbankschef, vice riksbankschef) ;</li>
 *   <li>{@code king} : {@code https://www.kungahuset.se/kungl.-huset/h.m.-konungen} – phrase « …, Sveriges Konung, född den …,
 *       uppsteg på tronen den … ».</li>
 * </ul>
 * Type {@code PLUGIN_MANAGED}. Paramètres (facultatifs) :
 * <pre>{@code
 * {"keys": "hd,hfd,riksbank,king", "delayMs": "1500"}
 * }</pre>
 */
@Extension
public class SePepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "se-pep-institutions";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Suede - hautes cours, Riksbank, Roi)";
    private static final Pattern NAME = Pattern.compile("^\\p{Lu}[\\p{L}'’.-]*(?: (?:\\p{Lu}[\\p{L}'’.-]*|von|van|de|af|la|le|der|den|di|zu)){1,4}$");
    private static final Pattern SEAT = Pattern.compile("^Ledamot av (Högsta (?:förvaltningsdomstolen|domstolen)) sedan (\\d{4})\\b(.*)$");
    private static final Pattern CHAIR = Pattern.compile("Ordförande i Högsta (?:[Ff]örvaltningsdomstolen|domstolen) sedan (\\d{4})");
    private static final Pattern KING = Pattern.compile(
            "^(.+?), Sveriges Konung, född den (\\d{1,2}) (\\p{L}+) (\\d{4}), uppsteg på tronen den (\\d{1,2}) (\\p{L}+) (\\d{4})");
    private static final List<String> MONTHS = List.of("januari", "februari", "mars", "april", "maj", "juni", "juli", "augusti",
            "september", "oktober", "november", "december");

    /** Une source : clé, nom lisible, nom du fichier brut et adresse. */
    record Source(String key, String name, String file, String url) {
    }

    static final List<Source> SOURCES = List.of(
            new Source("hd", "Högsta domstolen", "hd.html", "https://www.domstol.se/hogsta-domstolen/justitierad/"),
            new Source("hfd", "Högsta förvaltningsdomstolen", "hfd.html", "https://www.domstol.se/hogsta-forvaltningsdomstolen/justitierad/"),
            new Source("riksbank", "Sveriges riksbank", "riksbank.html", "https://www.riksbank.se/sv/om-riksbanken/organisation/direktionen/"),
            new Source("king", "Kungl. Huset", "king.html", "https://www.kungahuset.se/kungl.-huset/h.m.-konungen"));

    /** Une personne lue : nom, alias, fonction, naissance, début (année ou date), note, schéma de poste. */
    record Person(String name, String alias, String role, String birthDate, String start, String note) {
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
                people = switch (s.key()) {
                    case "hd", "hfd" -> readJustices(html, s.key().equals("hd") ? "Högsta domstolen" : "Högsta förvaltningsdomstolen");
                    case "riksbank" -> readRiksbank(html);
                    default -> readKing(html);
                };
            } catch (IllegalStateException e) {
                failures.add(s.name() + " (" + e.getMessage() + ")");
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
        FtmEntity person = dataset.newEntity("Person", s.key(), p.name());
        person.addValue("name", p.name());
        person.addValue("alias", p.alias());
        person.addValue("firstName", p.name().substring(0, p.name().indexOf(' ')));
        person.addValue("lastName", p.name().substring(p.name().lastIndexOf(' ') + 1));
        person.addValue("birthDate", p.birthDate());
        person.addValue("country", "se");
        person.addValue("position", p.role());
        person.addValue("sourceUrl", s.url());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", s.key(), p.role());
        position.addValue("name", p.role());
        position.addValue("country", "se");
        position.addValue("organization", s.name());
        position.addValue("topics", "gov.national");
        position.addValue("topics", switch (s.key()) {
            case "hd", "hfd" -> "gov.judicial";
            case "riksbank" -> "gov.financial";
            default -> "gov.head";
        });
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", s.key(), p.name(), p.role());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", p.start());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", p.note());
        occupancy.addValue("sourceUrl", s.url());
        dataset.addEntity(occupancy);
    }

    /** Les juges d'une cour : nom, puis « Ledamot av … sedan AAAA » avec, pour le président, « Ordförande i … sedan AAAA ». */
    static List<Person> readJustices(String html, String court) {
        List<String> lines = lines(html);
        List<Person> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            Matcher m = SEAT.matcher(lines.get(i));
            String name = lines.get(i - 1);
            if (!m.matches() || !m.group(1).equals(court) || !NAME.matcher(name).matches()) {
                continue;
            }
            // La mention de présidence est sur la même ligne (Högsta domstolen) ou sur la suivante (Högsta förvaltningsdomstolen).
            String extra = m.group(3) + (i + 1 < lines.size() && lines.get(i + 1).startsWith("Ordförande") ? " " + lines.get(i + 1) : "");
            Matcher chair = CHAIR.matcher(extra);
            boolean president = chair.find();
            String role = president ? "Président de la " + (court.equals("Högsta domstolen") ? "Cour suprême" : "Cour administrative suprême")
                    + " (ordförande, " + court + ")"
                    : "Juge de la " + (court.equals("Högsta domstolen") ? "Cour suprême" : "Cour administrative suprême") + " (justitieråd, " + court + ")";
            String note = "Membre de " + court + " depuis " + m.group(2)
                    + (president ? " ; président depuis " + chair.group(1) : extra.contains("Ordförande på avdelning") ? " ; président de section" : "");
            out.add(new Person(name, null, role, null, m.group(2), note));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun juge de " + court + " reconnu : la page a peut-être changé.");
        }
        return out;
    }

    /** La direction de la Riksbank : sous « Direktionsledamöter », une ligne de nom puis une ligne de titre. */
    static List<Person> readRiksbank(String html) {
        List<String> lines = lines(html);
        int start = lines.indexOf("Direktionsledamöter");
        List<Person> out = new ArrayList<>();
        if (start < 0) {
            throw new IllegalStateException("Rubrique « Direktionsledamöter » introuvable : la page a peut-être changé.");
        }
        for (int i = start + 1; i + 1 < lines.size() && !lines.get(i).equals("Relaterat innehåll"); i++) {
            String title = lines.get(i + 1);
            if (NAME.matcher(lines.get(i)).matches() && title.toLowerCase().contains("riksbankschef")) {
                String role = switch (title) {
                    case "Riksbankschef" -> "Gouverneur de la Riksbank (riksbankschef)";
                    case "Förste vice riksbankschef" -> "Premier sous-gouverneur de la Riksbank (förste vice riksbankschef)";
                    default -> "Sous-gouverneur de la Riksbank (vice riksbankschef)";
                };
                out.add(new Person(lines.get(i), null, role, null, null, "Membre de la direction (direktionen) de la Riksbank"));
                i++;
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun membre de la direction de la Riksbank reconnu : la page a peut-être changé.");
        }
        return out;
    }

    /** Le Roi : « CARL GUSTAF Folke Hubertus, Sveriges Konung, född den 30 april 1946, uppsteg på tronen den 15 september 1973 ». */
    static List<Person> readKing(String html) {
        for (String line : lines(html)) {
            Matcher m = KING.matcher(line);
            if (m.find()) {
                String raw = m.group(1);
                StringBuilder name = new StringBuilder();
                for (String w : raw.split(" ")) {
                    name.append(name.length() > 0 ? " " : "").append(w.equals(w.toUpperCase()) && w.length() > 1
                            ? w.charAt(0) + w.substring(1).toLowerCase() : w);
                }
                String birth = date(m.group(4), m.group(3), m.group(2));
                String throne = date(m.group(7), m.group(6), m.group(5));
                if (birth == null || throne == null) {
                    break;
                }
                return List.of(new Person(name.toString(), "Carl XVI Gustaf", "Roi de Suède (Konung), chef de l'État", birth, throne,
                        "Chef de l'État depuis son accession au trône"));
            }
        }
        throw new IllegalStateException("Phrase « Sveriges Konung, född den … » introuvable : la page a peut-être changé.");
    }

    private static String date(String year, String month, String day) {
        int mi = MONTHS.indexOf(month.toLowerCase());
        return mi < 0 ? null : LocalDate.of(Integer.parseInt(year), mi + 1, Integer.parseInt(day)).toString();
    }

    /** Lignes de texte de la page (partie principale), entités décodées, caractères invisibles retirés. */
    static List<String> lines(String html) {
        String body = html.contains("<main") ? html.substring(html.indexOf("<main")) : html;
        String text = decode(body.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<(br|/p|/div|/li|/h\\d|/a|/span)[^>]*>", "\n").replaceAll("<[^>]+>", " "));
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replaceAll("[\\u200B-\\u200F\\u2060\\uFEFF\\u00AD]", "").replaceAll("[\\u00A0\\u202F]", " ").replaceAll("\\s+", " ").trim();
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
                .replace("&aring;", "å").replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&shy;", "");
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
