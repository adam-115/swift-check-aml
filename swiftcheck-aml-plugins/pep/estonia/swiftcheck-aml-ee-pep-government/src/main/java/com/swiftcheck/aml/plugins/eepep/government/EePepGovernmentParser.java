package com.swiftcheck.aml.plugins.eepep.government;

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
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>ee_pep_government</b> – Premier ministre et ministres du Gouvernement de la République d'Estonie, personnes politiquement
 * exposées au sens de la loi estonienne sur la prévention du blanchiment de capitaux et du financement du terrorisme.
 *
 * <p>Source officielle : le site du Gouvernement {@code https://valitsus.ee/en}. La page d'accueil renvoie vers la fiche de chaque
 * membre ({@code /en/prime-minister-ministers/...}) ; chaque fiche donne le nom, la fonction avec sa date de prise de fonction
 * (« Minister of Finance of the Republic of Estonia from 23 July 2024 », « ... since 16 September 2026 » ou « Minister of Education and Research from 23 July 2024 ») et la date de naissance (« was born on 16 July 1959 »).
 * Le site ne publie que les membres en fonction : pas d'anciens ministres. Le robots.txt n'impose pas de délai ; le parser attend
 * 2 secondes entre deux pages et s'identifie par un User-Agent générique.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"homeUrl": "https://valitsus.ee/en", "delayMs": "2000"}
 * }</pre>
 */
@Extension
public class EePepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ee-pep-government";
    static final String TOPIC = "role.pep";
    static final String HOME_FILE = "home.html";
    static final String PAGE_PREFIX = "member-";
    static final String DEFAULT_HOME_URL = "https://valitsus.ee/en";
    static final String SITE = "https://valitsus.ee";
    static final long DEFAULT_DELAY_MS = 2000;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Estonie - Gouvernement)";
    private static final Pattern LINK = Pattern.compile("href=\"(/en/prime-minister-ministers/[^\"#?]+)\"");
    private static final Pattern H1 = Pattern.compile("<h1[^>]*>([\\s\\S]*?)</h1>");
    private static final Pattern ROLE = Pattern.compile("^((?:Prime )?Minister.*?)(?: of the Republic of Estonia)? (?:from|since) (\\d{1,2} [A-Za-z]+ \\d{4})");
    private static final Pattern BORN = Pattern.compile(
            "was born(?: in ([^.,]+?))?(?: on)? (\\d{1,2} [A-Za-z]+ \\d{4})(?: in ([^.,]+))?[.,]");

    /** Un membre du Gouvernement lu sur sa fiche. */
    record Member(String name, String role, LocalDate start, LocalDate birthDate, String birthPlace, String url) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(delayMs(p)), homeUrl(p), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
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

    /** Télécharge la page d'accueil puis la fiche de chaque membre qu'elle liste. */
    static void download(Fetcher fetcher, String homeUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String home = fetcher.get(homeUrl);
        Files.writeString(rawDir.resolve(HOME_FILE), home, StandardCharsets.UTF_8);
        URI base = URI.create(homeUrl);
        int i = 0;
        for (String link : links(home)) {
            Files.writeString(rawDir.resolve(PAGE_PREFIX + (++i) + ".html"),
                    fetcher.get(base.resolve(link).toString()), StandardCharsets.UTF_8);
        }
    }

    /** Lit les fiches de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        if (!Files.exists(rawDir.resolve(HOME_FILE))) {
            throw new IllegalStateException(HOME_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Path> pages;
        try (Stream<Path> listing = Files.list(rawDir)) {
            pages = listing.filter(f -> f.getFileName().toString().startsWith(PAGE_PREFIX)).sorted().toList();
        }
        List<String> links = links(Files.readString(rawDir.resolve(HOME_FILE), StandardCharsets.UTF_8));
        List<Member> members = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Path page : pages) {
            String html = Files.readString(page, StandardCharsets.UTF_8);
            Member m = member(html, sourceUrl(links, page.getFileName().toString()));
            if (m == null) {
                skipped.add(page.getFileName().toString());
            } else {
                members.add(m);
            }
        }
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre du Gouvernement lu dans " + rawDir
                    + " : la page d'accueil ou les fiches ont peut-être changé.");
        }
        for (Member m : members) {
            FtmEntity person = dataset.newEntity("Person", m.name(), String.valueOf(m.birthDate()));
            person.addValue("name", m.name());
            person.addValue("birthDate", m.birthDate() == null ? null : m.birthDate().toString());
            person.addValue("birthPlace", m.birthPlace());
            person.addValue("country", "ee");
            person.addValue("nationality", "ee");
            person.addValue("position", m.role());
            person.addValue("sourceUrl", m.url());
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", m.role());
            position.addValue("name", m.role() + " (Estonie)");
            position.addValue("country", "ee");
            position.addValue("organization", "Vabariigi Valitsus (Gouvernement de la République d'Estonie)");
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.executive");
            if (m.role().startsWith("Prime Minister")) {
                position.addValue("topics", "gov.head");
            }
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", m.name(), String.valueOf(m.birthDate()), m.role());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", m.start() == null ? null : m.start().toString());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", m.url());
            dataset.addEntity(occupancy);
        }
        return members.size() + " membres du Gouvernement en fonction"
                + (skipped.isEmpty() ? "" : " (" + skipped.size() + " fiche(s) illisible(s) : " + String.join(", ", skipped) + ")")
                + ". Source : Gouvernement de la République d'Estonie, valitsus.ee.";
    }

    /** Adresse de la fiche {@code member-N.html} : le N-ième lien de la page d'accueil (même ordre qu'au téléchargement). */
    static String sourceUrl(List<String> links, String fileName) {
        try {
            int n = Integer.parseInt(fileName.substring(PAGE_PREFIX.length(), fileName.length() - ".html".length()));
            return n >= 1 && n <= links.size() ? SITE + links.get(n - 1) : fileName;
        } catch (NumberFormatException e) {
            return fileName;
        }
    }

    /** Adresses relatives des fiches (premier ministre et ministres), sans doublon, dans l'ordre de la page. */
    static List<String> links(String html) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = LINK.matcher(html);
        while (m.find()) {
            out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    /** Une fiche : nom (titre de la page), fonction et date de prise de fonction, naissance ; {@code null} si illisible. */
    static Member member(String html, String source) {
        Matcher h1 = H1.matcher(html);
        if (!h1.find()) {
            return null;
        }
        String name = clean(h1.group(1).replaceAll("<[^>]+>", " "));
        int main = html.indexOf("<main");
        String body = (main < 0 ? html : html.substring(main)).replaceAll("(?s)<script.*?</script>", "").replaceAll("(?s)<style.*?</style>", "");
        String role = null;
        LocalDate start = null;
        LocalDate birth = null;
        String place = null;
        for (String raw : body.replaceAll("<[^>]+>", "\n").split("\n")) {
            String line = clean(raw);
            if (role == null) {
                Matcher r = ROLE.matcher(line);
                if (r.find()) {
                    role = r.group(1);
                    start = date(r.group(2));
                }
            }
            if (birth == null && line.contains("was born")) {
                Matcher b = BORN.matcher(line);
                if (b.find()) {
                    birth = date(b.group(2));
                    place = b.group(1) != null ? b.group(1).trim() : b.group(3) == null ? null : b.group(3).trim();
                }
            }
        }
        if (name.isEmpty() || role == null) {
            return null;
        }
        return new Member(name, role, start, birth, place, source);
    }

    /** Texte sans entités ni espaces insécables ni espaces multiples. */
    static String clean(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static LocalDate date(String v) {
        try {
            String[] p = v.trim().split(" ");
            return LocalDate.of(Integer.parseInt(p[2]), Month.valueOf(p[1].toUpperCase(Locale.ROOT)), Integer.parseInt(p[0]));
        } catch (Exception e) {
            return null;
        }
    }

    private static String homeUrl(Map<String, String> p) {
        String v = p.get("homeUrl");
        return v == null || v.isBlank() ? DEFAULT_HOME_URL : v.trim();
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
