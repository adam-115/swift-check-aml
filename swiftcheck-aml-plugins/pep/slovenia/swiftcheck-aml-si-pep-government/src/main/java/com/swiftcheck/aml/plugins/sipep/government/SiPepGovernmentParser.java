package com.swiftcheck.aml.plugins.sipep.government;

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
 * <b>si_pep_government</b> – Premier ministre et ministres du Gouvernement de la République de Slovénie, personnes politiquement
 * exposées au sens de la loi slovène ZPPDFT-2 sur la prévention du blanchiment de capitaux.
 *
 * <p>Source officielle : la page « O vladi » de {@code www.gov.si/drzavni-organi/vlada/o-vladi/}. Le texte donne le Premier ministre
 * (« V 16. Vladi Republike Slovenije, ki jo vodi Janez Janša… ») et la date de nomination du Gouvernement par l'Assemblée nationale
 * (« Državni zbor jo je imenoval 4. junija 2026 »), et la page liste les ministres par des liens vers leur fiche
 * ({@code /drzavni-organi/ministrstva/<ministère>/o-ministrstvu/<nom>/}) dont le texte est le nom. La fonction est le ministère
 * tiré de l'adresse (« Ministrstvo za finance »). Ni naissance ni anciens membres. robots.txt : seuls /admin, /resources et
 * /pomoc sont interdits ; un appel, User-Agent générique.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"url": "https://www.gov.si/drzavni-organi/vlada/o-vladi/"}
 * }</pre>
 */
@Extension
public class SiPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "si-pep-government";
    static final String TOPIC = "role.pep";
    static final String FILE = "o-vladi.html";
    static final String DEFAULT_URL = "https://www.gov.si/drzavni-organi/vlada/o-vladi/";
    static final String SITE = "https://www.gov.si";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Slovénie - Gouvernement)";
    private static final Pattern MINISTER = Pattern.compile(
            "<a[^>]*href=\"(/drzavni-organi/ministrstva/([a-z0-9-]+)/o-ministrstvu/[^\"/]+/)\"[^>]*>([\\s\\S]*?)</a>");
    private static final Pattern PRIME_MINISTER = Pattern.compile("ki jo vodi (\\p{Lu}[\\p{L}'’-]*(?: \\p{Lu}[\\p{L}'’-]*)+)[,.]");
    private static final Pattern APPOINTED = Pattern.compile("imenoval (\\d{1,2})\\. (\\p{L}+) (\\d{4})");
    private static final List<String> MONTHS = List.of("januarja", "februarja", "marca", "aprila", "maja", "junija", "julija",
            "avgusta", "septembra", "oktobra", "novembra", "decembra");

    /** Un membre du Gouvernement : nom, fonction et adresse de sa fiche. */
    record Member(String name, String role, String url) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.get("url") == null || p.get("url").isBlank() ? DEFAULT_URL : p.get("url").trim();
        download(httpFetcher(), url, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), url, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), DEFAULT_URL, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    static void download(Fetcher fetcher, String url, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Files.writeString(rawDir.resolve(FILE), fetcher.get(url), StandardCharsets.UTF_8);
    }

    /** Lit la page de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, String url, FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(FILE);
        if (!Files.exists(file)) {
            throw new IllegalStateException(FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        String html = Files.readString(file, StandardCharsets.UTF_8);
        List<Member> members = members(html);
        LocalDate start = appointed(html);
        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        for (Member m : members) {
            FtmEntity person = dataset.newEntity("Person", m.name());
            person.addValue("name", m.name());
            person.addValue("country", "si");
            person.addValue("nationality", "si");
            person.addValue("position", m.role());
            person.addValue("sourceUrl", m.url());
            dataset.addEntity(person);

            FtmEntity position = positions.get(m.role());
            if (position == null) {
                position = dataset.newEntity("Position", m.role());
                position.addValue("name", m.role() + " (Slovénie)");
                position.addValue("country", "si");
                position.addValue("organization", "Vlada Republike Slovenije (Gouvernement de la République de Slovénie)");
                position.addValue("topics", "gov.national");
                position.addValue("topics", "gov.executive");
                if (m.role().equals("Predsednik vlade")) {
                    position.addValue("topics", "gov.head");
                }
                dataset.addEntity(position);
                positions.put(m.role(), position);
            }
            FtmEntity occupancy = dataset.newEntity("Occupancy", m.name(), m.role());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", start == null ? null : start.toString());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", m.url());
            dataset.addEntity(occupancy);
        }
        return members.size() + " membres du Gouvernement" + (start == null ? "" : " (nommé le " + start + ")")
                + ". Source : Vlada Republike Slovenije, www.gov.si.";
    }

    /** Le Premier ministre (phrase de présentation) puis les ministres (liens vers leur fiche), sans doublon. */
    static List<Member> members(String html) {
        Map<String, Member> out = new LinkedHashMap<>();
        String text = text(html.replaceAll("(?s)<script.*?</script>", "").replaceAll("(?s)<style.*?</style>", "").replaceAll("<[^>]+>", " "));
        Matcher pm = PRIME_MINISTER.matcher(text);
        if (pm.find()) {
            out.put(pm.group(1), new Member(pm.group(1), "Predsednik vlade", SITE + "/drzavni-organi/vlada/"));
        }
        Matcher m = MINISTER.matcher(html);
        while (m.find()) {
            String name = text(m.group(3).replaceAll("<[^>]+>", " "));
            if (name.isEmpty()) {
                continue;
            }
            String ministry = m.group(2).replace('-', ' ');
            String role = "Minister (" + Character.toUpperCase(ministry.charAt(0)) + ministry.substring(1) + ")";
            out.putIfAbsent(name, new Member(name, role, SITE + m.group(1)));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page « O vladi » : ni Premier ministre ni ministre trouvé (la page a changé).");
        }
        return new ArrayList<>(out.values());
    }

    /** « Državni zbor jo je imenoval 4. junija 2026 » → 2026-06-04 ; {@code null} si absent. */
    static LocalDate appointed(String html) {
        Matcher m = APPOINTED.matcher(text(html.replaceAll("<[^>]+>", " ")));
        if (!m.find()) {
            return null;
        }
        int month = MONTHS.indexOf(m.group(2)) + 1;
        if (month == 0) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1)));
        } catch (Exception e) {
            return null;
        }
    }

    static String text(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
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
