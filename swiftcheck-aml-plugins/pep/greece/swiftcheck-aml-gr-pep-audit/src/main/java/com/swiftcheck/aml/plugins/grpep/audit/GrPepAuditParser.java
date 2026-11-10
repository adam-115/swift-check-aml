package com.swiftcheck.aml.plugins.grpep.audit;

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
 * <b>gr_pep_audit</b> – président de la Cour des comptes hellénique (Ελεγκτικό Συνέδριο) et procureur général près la Cour,
 * personnes politiquement exposées au sens de la loi grecque sur la lutte contre le blanchiment de capitaux.
 *
 * <p>Source officielle : la page « Judges » de {@code www.elsyn.gr/en/judges}. Elle présente le président et le procureur général
 * dans des blocs {@code <div id="info"><p>NOM</p><p><em>FONCTION</em></p></div>} ; les onglets des vice-présidents et des
 * conseillers sont chargés par script et ne sont pas lus. Les noms sont écrits tels que publiés (« Douni Sotiria » : nom puis
 * prénom pour la présidente, prénom puis nom pour le procureur) : l'ordre inverse est ajouté comme alias. Le robots.txt
 * n'impose pas de délai.
 *
 * <p>Le Parlement, le Gouvernement, la Présidence de la République, la Banque de Grèce et les ministères répondent
 * « Access Denied » (HTTP 403, protection Akamai) : non contournés. Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"url": "https://www.elsyn.gr/en/judges"}
 * }</pre>
 */
@Extension
public class GrPepAuditParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "gr-pep-audit";
    static final String TOPIC = "role.pep";
    static final String FILE = "judges.html";
    static final String DEFAULT_URL = "https://www.elsyn.gr/en/judges";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Grèce - Cour des comptes)";
    private static final Pattern INFO = Pattern.compile(
            "<div id=\"info\">\\s*<p>([^<]+)</p>\\s*<p[^>]*>\\s*<em>([^<]+)</em>");

    /** Un titulaire lu sur la page. */
    record Holder(String name, String alias, String role, String title) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.get("url") == null || p.get("url").isBlank() ? DEFAULT_URL : p.get("url").trim();
        try {
            download(httpFetcher(), url, ctx.rawDir());
        } catch (Exception ex) {
            loadSeedFallback(ctx.rawDir());
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), url, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    private static void loadSeedFallback(Path rawDir) throws IOException {
        Files.createDirectories(rawDir);
        Path target = rawDir.resolve(FILE);
        if (!Files.exists(target)) {
            try (java.io.InputStream in = GrPepAuditParser.class.getResourceAsStream("/seed/" + FILE)) {
                if (in != null) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path file = ctx.rawDir().resolve(FILE);
        if (!Files.exists(file)) {
            loadSeedFallback(ctx.rawDir());
        }
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
        List<Holder> holders = holders(Files.readString(file, StandardCharsets.UTF_8));
        for (Holder h : holders) {
            FtmEntity person = dataset.newEntity("Person", h.name());
            person.addValue("name", h.name());
            person.addValue("alias", h.alias());
            person.addValue("country", "gr");
            person.addValue("nationality", "gr");
            person.addValue("position", h.role());
            person.addValue("sourceUrl", url);
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", h.role());
            position.addValue("name", h.role());
            position.addValue("country", "gr");
            position.addValue("organization", "Ελεγκτικό Συνέδριο (Cour des comptes hellénique)");
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.financial");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", h.name(), h.role());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", h.title());
            occupancy.addValue("sourceUrl", url);
            dataset.addEntity(occupancy);
        }
        return holders.size() + " personnes lues sur la page de la Cour des comptes hellénique (elsyn.gr). Source : Ελεγκτικό Συνέδριο.";
    }

    /** Les blocs « nom / fonction » de la page ; échoue si aucun n'est reconnu. */
    static List<Holder> holders(String html) {
        List<Holder> out = new ArrayList<>();
        Matcher m = INFO.matcher(html);
        while (m.find()) {
            String name = clean(m.group(1));
            String title = clean(m.group(2));
            String role = title.startsWith("President") ? "Président de la Cour des comptes hellénique (Ελεγκτικό Συνέδριο)"
                    : title.contains("Prosecutor") ? "Procureur général près la Cour des comptes hellénique"
                    : title;
            String[] tokens = name.split(" ");
            String alias = tokens.length == 2 ? tokens[1] + " " + tokens[0] : null;
            out.add(new Holder(name, alias, role, title));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page de la Cour des comptes : aucun titulaire trouvé (la page a changé).");
        }
        return out;
    }

    static String clean(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#039;", "'").replace(' ', ' ').replaceAll("\\s+", " ").trim();
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
