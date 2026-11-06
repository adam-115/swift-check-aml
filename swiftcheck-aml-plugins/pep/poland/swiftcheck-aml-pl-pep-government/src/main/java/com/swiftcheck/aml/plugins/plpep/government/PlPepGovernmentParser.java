package com.swiftcheck.aml.plugins.plpep.government;

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
 * <b>pl_pep_government</b> – Premier ministre, vice-présidents du Conseil des ministres et ministres du gouvernement polonais.
 *
 * <p>Source officielle : la page « Skład Rady Ministrów » de la Chancellerie du Premier ministre,
 * {@code https://www.gov.pl/web/premier/sklad-rady-ministrow}. Sous le titre « Skład Rady Ministrów » chaque membre est une ligne
 * de nom suivie d'une ligne de fonction (« Prezes Rady Ministrów », « Wiceprezes Rady Ministrów, Minister Obrony Narodowej »,
 * « Minister Zdrowia »…). La page ne donne ni naissance ni date de nomination. Type {@code PLUGIN_MANAGED}. Paramètre facultatif :
 * <pre>{@code
 * {"url": "https://www.gov.pl/web/premier/sklad-rady-ministrow"}
 * }</pre>
 */
@Extension
public class PlPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "pl-pep-government";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String FILE = "sklad-rady-ministrow.html";
    static final String URL = "https://www.gov.pl/web/premier/sklad-rady-ministrow";
    static final String START_MARK = "Skład Rady Ministrów";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Pologne - gouvernement)";
    private static final Pattern NAME = Pattern.compile("^\\p{Lu}[\\p{L}'’.-]*(?: \\p{Lu}[\\p{L}'’.-]*){1,3}$");
    private static final Pattern TITLE = Pattern.compile("^(Prezes Rady Ministrów|Wiceprezes Rady Ministrów|Minister|Ministra).*");

    /** Un membre du gouvernement. */
    record Member(String name, String title) {
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
        return dataset.toConversionResult().withMessage(n + " membres du Conseil des ministres.");
    }

    /** Test du parser : chaque membre, avec l'ID et le nom que Yente doit connaître (sans réseau). */
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

    /** Lit la page de {@code rawDir} et écrit les membres ; renvoie leur nombre. */
    int convert(Path rawDir, String url, FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(FILE);
        if (!Files.exists(file)) {
            throw new IllegalStateException(FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Member> members = read(Files.readString(file, StandardCharsets.UTF_8));
        for (Member m : members) {
            String role = role(m.title());
            FtmEntity person = dataset.newEntity("Person", m.name());
            person.addValue("name", m.name());
            person.addValue("firstName", m.name().substring(0, m.name().indexOf(' ')));
            person.addValue("lastName", m.name().substring(m.name().lastIndexOf(' ') + 1));
            person.addValue("country", "pl");
            person.addValue("position", role);
            person.addValue("sourceUrl", url);
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", m.title());
            position.addValue("name", role);
            position.addValue("country", "pl");
            position.addValue("organization", "Rada Ministrów");
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.executive");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", m.name(), m.title());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", "Fonction publiée : " + m.title());
            occupancy.addValue("sourceUrl", url);
            dataset.addEntity(occupancy);
        }
        return members.size();
    }

    static String role(String title) {
        if (title.startsWith("Prezes Rady Ministrów")) {
            return "Premier ministre (Prezes Rady Ministrów)";
        }
        if (title.startsWith("Wiceprezes Rady Ministrów")) {
            return "Vice-premier ministre : " + title;
        }
        return "Ministre : " + title;
    }

    /** Les membres sous « Skład Rady Ministrów » : une ligne de nom, puis une ligne de fonction. */
    static List<Member> read(String html) {
        List<String> lines = lines(html);
        int start = lines.indexOf(START_MARK);
        List<Member> out = new ArrayList<>();
        if (start < 0) {
            throw new IllegalStateException("Rubrique « " + START_MARK + " » introuvable : la page a peut-être changé.");
        }
        for (int i = start + 1; i + 1 < lines.size(); i++) {
            if (lines.get(i).startsWith("{") || lines.get(i).startsWith("stopka")) {
                break;
            }
            if (NAME.matcher(lines.get(i)).matches() && TITLE.matcher(lines.get(i + 1)).matches()) {
                out.add(new Member(lines.get(i), lines.get(i + 1)));
                i++;
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun membre du Conseil des ministres reconnu : la page a peut-être changé.");
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
