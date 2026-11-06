package com.swiftcheck.aml.plugins.lvpep.government;

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
import java.util.stream.Stream;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>lv_pep_government</b> – Premier ministre et ministres du Conseil des ministres de la République de Lettonie, personnes
 * politiquement exposées au sens de la loi lettonne sur la prévention du blanchiment de capitaux.
 *
 * <p>Source officielle : le site du Conseil des ministres {@code mk.gov.lv}. La page « Ministru kabineta sastāvs » liste les
 * membres ({@code /lv/darbinieks/nom-prénom} avec leur titre) ; chaque fiche donne le nom (titre de la page) et la fonction (titre
 * de niveau 1). Le site ne publie ni naissance ni dates de fonction, ni anciens ministres. Le {@code robots.txt} n'impose pas de
 * délai ; le parser attend 2 secondes entre deux pages, avec un User-Agent générique.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"compositionUrl": "https://www.mk.gov.lv/lv/ministru-kabineta-sastavs", "delayMs": "2000"}
 * }</pre>
 */
@Extension
public class LvPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "lv-pep-government";
    static final String TOPIC = "role.pep";
    static final String COMPOSITION_FILE = "composition.html";
    static final String PAGE_PREFIX = "member-";
    static final String DEFAULT_COMPOSITION_URL = "https://www.mk.gov.lv/lv/ministru-kabineta-sastavs";
    static final String SITE = "https://www.mk.gov.lv";
    static final long DEFAULT_DELAY_MS = 2000;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Lettonie - Conseil des ministres)";
    private static final Pattern LINK = Pattern.compile("<a[^>]*href=\"(/lv/darbinieks/[^\"#?]+)\"[^>]*>([\\s\\S]*?)</a>");
    private static final Pattern TITLE = Pattern.compile("<title>([^<]*)</title>");
    private static final Pattern H1 = Pattern.compile("<h1[^>]*>([\\s\\S]*?)</h1>");

    /** Un membre du Conseil des ministres : adresse relative de sa fiche et titre listé. */
    record Listed(String path, String role) {
    }

    /** Un membre lu sur sa fiche. */
    record Member(String name, String role, String url) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(delayMs(p)), compositionUrl(p), ctx.rawDir());
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

    /** Télécharge la page de composition puis la fiche de chaque membre qu'elle liste. */
    static void download(Fetcher fetcher, String compositionUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String composition = fetcher.get(compositionUrl);
        Files.writeString(rawDir.resolve(COMPOSITION_FILE), composition, StandardCharsets.UTF_8);
        URI base = URI.create(compositionUrl);
        int i = 0;
        for (Listed l : listed(composition)) {
            Files.writeString(rawDir.resolve(PAGE_PREFIX + (++i) + ".html"), fetcher.get(base.resolve(l.path()).toString()),
                    StandardCharsets.UTF_8);
        }
    }

    /** Lit les fiches de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Path compositionFile = rawDir.resolve(COMPOSITION_FILE);
        if (!Files.exists(compositionFile)) {
            throw new IllegalStateException(COMPOSITION_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Listed> listed = listed(Files.readString(compositionFile, StandardCharsets.UTF_8));
        List<Path> pages;
        try (Stream<Path> listing = Files.list(rawDir)) {
            pages = listing.filter(f -> f.getFileName().toString().startsWith(PAGE_PREFIX)).toList();
        }
        List<Member> members = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Path page : pages) {
            String file = page.getFileName().toString();
            int n = Integer.parseInt(file.substring(PAGE_PREFIX.length(), file.length() - ".html".length()));
            Listed l = n >= 1 && n <= listed.size() ? listed.get(n - 1) : null;
            Member m = l == null ? null : member(Files.readString(page, StandardCharsets.UTF_8), l);
            if (m == null) {
                skipped.add(file);
            } else {
                members.add(m);
            }
        }
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre du Conseil des ministres lu dans " + rawDir
                    + " : la page de composition ou les fiches ont peut-être changé.");
        }
        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        for (Member m : members) {
            FtmEntity person = dataset.newEntity("Person", m.name());
            person.addValue("name", m.name());
            person.addValue("country", "lv");
            person.addValue("nationality", "lv");
            person.addValue("position", m.role());
            person.addValue("sourceUrl", m.url());
            dataset.addEntity(person);

            FtmEntity position = positions.get(m.role());
            if (position == null) {
                position = dataset.newEntity("Position", m.role());
                position.addValue("name", m.role() + " (Lettonie)");
                position.addValue("country", "lv");
                position.addValue("organization", "Ministru kabinets (Conseil des ministres de Lettonie)");
                position.addValue("topics", "gov.national");
                position.addValue("topics", "gov.executive");
                if (m.role().equals("Ministru prezidents") || m.role().equals("Ministru prezidente")) {
                    position.addValue("topics", "gov.head");
                }
                dataset.addEntity(position);
                positions.put(m.role(), position);
            }
            FtmEntity occupancy = dataset.newEntity("Occupancy", m.name(), m.role());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", m.url());
            dataset.addEntity(occupancy);
        }
        return members.size() + " membres du Conseil des ministres"
                + (skipped.isEmpty() ? "" : " (" + skipped.size() + " fiche(s) illisible(s) : " + String.join(", ", skipped) + ")")
                + ". Source : Ministru kabinets, mk.gov.lv.";
    }

    /** Membres listés sur la page de composition, sans doublon, dans l'ordre de la page. */
    static List<Listed> listed(String html) {
        Map<String, Listed> out = new LinkedHashMap<>();
        Matcher m = LINK.matcher(html);
        while (m.find()) {
            String role = clean(m.group(2).replaceAll("<[^>]+>", " "));
            out.putIfAbsent(m.group(1), new Listed(m.group(1), role));
        }
        return new ArrayList<>(out.values());
    }

    /** Une fiche : nom = titre de la page (« Nom Prénom | Ministru kabinets »), fonction = titre de niveau 1 ou titre listé. */
    static Member member(String html, Listed listed) {
        Matcher t = TITLE.matcher(html);
        if (!t.find()) {
            return null;
        }
        String name = clean(t.group(1).split("\\|")[0]);
        Matcher h = H1.matcher(html);
        String role = h.find() ? clean(h.group(1).replaceAll("<[^>]+>", " ")) : listed.role();
        if (name.isEmpty() || role.isEmpty()) {
            return null;
        }
        return new Member(name, role, SITE + listed.path());
    }

    static String clean(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String compositionUrl(Map<String, String> p) {
        String v = p.get("compositionUrl");
        return v == null || v.isBlank() ? DEFAULT_COMPOSITION_URL : v.trim();
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
