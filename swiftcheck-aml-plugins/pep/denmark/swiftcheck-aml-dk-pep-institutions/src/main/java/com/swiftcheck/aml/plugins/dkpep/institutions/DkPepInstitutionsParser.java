package com.swiftcheck.aml.plugins.dkpep.institutions;

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
 * <b>dk_pep_institutions</b> – direction de la Rigsrevisionen (cour des comptes danoise) : le Rigsrevisor et le vice-Rigsrevisor.
 *
 * <p>Source officielle : la page {@code https://www.rigsrevisionen.dk/om-os/organisation}, rubrique « Direktionen » : « Rigsrevisor
 * Birgitte Hansen », « Vicerigsrevisor og afdelingschef Lene Schmidt ». Les chefs de département qui suivent ne sont pas repris
 * (pas des personnes politiquement exposées). La page est téléchargée à chaque exécution. Type {@code PLUGIN_MANAGED}.
 * Pas de paramètre obligatoire ; {@code url} remplace l'adresse de la page.
 */
@Extension
public class DkPepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "dk-pep-institutions";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String FILE = "rigsrevisionen.html";
    static final String URL = "https://www.rigsrevisionen.dk/om-os/organisation";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Danemark - Rigsrevisionen)";
    private static final Pattern HEAD = Pattern.compile("^(Rigsrevisor|Vicerigsrevisor)(?: og [\\p{L} ]+?)? (\\p{Lu}[\\p{L}'’.-]*(?: \\p{Lu}[\\p{L}'’.-]*){1,4})$");

    /** Un dirigeant lu. */
    record Head(String name, String title, boolean deputy) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.get("url") == null || p.get("url").isBlank() ? URL : p.get("url").trim();
        download(httpFetcher(), url, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int n = convert(ctx.rawDir(), url, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(n + " dirigeants de la Rigsrevisionen.");
    }

    /** Test du parser : chaque dirigeant, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), URL, dataset);
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

    /** Lit la page de {@code rawDir} et écrit les dirigeants ; renvoie leur nombre. */
    int convert(Path rawDir, String url, FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(FILE);
        if (!Files.exists(file)) {
            throw new IllegalStateException(FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Head> heads = read(Files.readString(file, StandardCharsets.UTF_8));
        for (Head h : heads) {
            String role = h.deputy() ? "Vice-Rigsrevisor (vicerigsrevisor, Rigsrevisionen)" : "Rigsrevisor (auditeur général, Rigsrevisionen)";
            FtmEntity person = dataset.newEntity("Person", h.name());
            person.addValue("name", h.name());
            person.addValue("firstName", h.name().substring(0, h.name().indexOf(' ')));
            person.addValue("lastName", h.name().substring(h.name().lastIndexOf(' ') + 1));
            person.addValue("country", "dk");
            person.addValue("position", role);
            person.addValue("sourceUrl", url);
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", role);
            position.addValue("name", role);
            position.addValue("country", "dk");
            position.addValue("organization", "Rigsrevisionen");
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.financial");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", h.name(), role);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", "Titre publié : " + h.title());
            occupancy.addValue("sourceUrl", url);
            dataset.addEntity(occupancy);
        }
        return heads.size();
    }

    /** Les dirigeants sous « Direktionen » : « Rigsrevisor X » et « Vicerigsrevisor og afdelingschef Y ». */
    static List<Head> read(String html) {
        List<String> lines = lines(html);
        int start = lines.indexOf("Direktionen");
        List<Head> out = new ArrayList<>();
        if (start < 0) {
            throw new IllegalStateException("Rubrique « Direktionen » introuvable : la page a peut-être changé.");
        }
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("Organisation pr."); i++) {
            Matcher m = HEAD.matcher(lines.get(i));
            if (m.matches()) {
                out.add(new Head(m.group(2), lines.get(i).substring(0, lines.get(i).length() - m.group(2).length()).trim(),
                        m.group(1).equals("Vicerigsrevisor")));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun Rigsrevisor reconnu sous « Direktionen » : la page a peut-être changé.");
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
        return sb.toString().replace("&nbsp;", " ").replace("&amp;", "&").replace("&oslash;", "ø").replace("&aelig;", "æ")
                .replace("&aring;", "å").replace("&Oslash;", "Ø").replace("&Aelig;", "Æ").replace("&Aring;", "Å");
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
