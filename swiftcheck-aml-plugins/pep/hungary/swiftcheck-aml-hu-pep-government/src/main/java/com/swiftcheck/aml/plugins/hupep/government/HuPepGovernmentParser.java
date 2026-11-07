package com.swiftcheck.aml.plugins.hupep.government;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>hu_pep_government</b> – Premier ministre et ministres du Gouvernement hongrois, personnes politiquement exposées au sens de
 * la loi hongroise sur la prévention du blanchiment de capitaux (Pmt.).
 *
 * <p>Source officielle : la page « Kormányzat » de {@code kormany.hu/kormanyzat}. Sous « A kormány tagjai », chaque membre est un
 * triplet de lignes « Minisztérium megtekintése / nom / fonction » ; la liste s'arrête aux porte-parole (« Kormányszóvivők »,
 * non repris). Les noms sont écrits « Nom Prénom » : l'ordre inverse est ajouté en alias. Ni naissance ni dates de fonction ; pas
 * d'anciens ministres. robots.txt : seul /publicapi est interdit ; un appel, User-Agent générique.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"url": "https://kormany.hu/kormanyzat"}
 * }</pre>
 */
@Extension
public class HuPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "hu-pep-government";
    static final String TOPIC = "role.pep";
    static final String FILE = "members.html";
    static final String DEFAULT_URL = "https://kormany.hu/kormanyzat";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Hongrie - Gouvernement)";
    private static final String MARKER = "Minisztérium megtekintése";
    private static final Pattern NAME = Pattern.compile("^\\p{Lu}[\\p{L}'’.-]*(?: \\p{Lu}[\\p{L}'’.-]*)+$");

    /** Un membre du Gouvernement : nom et fonction telle que publiée. */
    record Member(String name, String role) {
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
        List<Member> members = members(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        for (Member m : members) {
            FtmEntity person = dataset.newEntity("Person", m.name());
            person.addValue("name", m.name());
            for (String alias : aliases(m.name())) {
                person.addValue("alias", alias);
            }
            person.addValue("country", "hu");
            person.addValue("nationality", "hu");
            person.addValue("position", m.role());
            person.addValue("sourceUrl", url);
            dataset.addEntity(person);

            FtmEntity position = positions.get(m.role());
            if (position == null) {
                position = dataset.newEntity("Position", m.role());
                position.addValue("name", m.role() + " (Hongrie)");
                position.addValue("country", "hu");
                position.addValue("organization", "Magyarország Kormánya (Gouvernement de la Hongrie)");
                position.addValue("topics", "gov.national");
                position.addValue("topics", "gov.executive");
                if (m.role().equals("miniszterelnök")) {
                    position.addValue("topics", "gov.head");
                }
                dataset.addEntity(position);
                positions.put(m.role(), position);
            }
            FtmEntity occupancy = dataset.newEntity("Occupancy", m.name(), m.role());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", url);
            dataset.addEntity(occupancy);
        }
        return members.size() + " membres du Gouvernement. Source : Magyarország Kormánya, kormany.hu.";
    }

    /** Les triplets « Minisztérium megtekintése / nom / fonction » sous « A kormány tagjai », jusqu'aux porte-parole. */
    static List<Member> members(String html) {
        List<String> lines = lines(html);
        int from = lines.lastIndexOf("A kormány tagjai");
        if (from < 0) {
            throw new IllegalStateException("Page « Kormányzat » : section « A kormány tagjai » introuvable (la page a changé).");
        }
        List<Member> out = new ArrayList<>();
        for (int i = from + 1; i + 2 < lines.size() && MARKER.equals(lines.get(i)); i += 3) {
            if (!NAME.matcher(lines.get(i + 1)).matches()) {
                break;
            }
            out.add(new Member(lines.get(i + 1), lines.get(i + 2)));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page « Kormányzat » : aucun membre trouvé (la page a changé).");
        }
        return out;
    }

    /** « Magyar Péter » → « Péter Magyar » ; ordre inverse du nom publié, « Dr. » ignoré. */
    static Set<String> aliases(String name) {
        List<String> tokens = new ArrayList<>(List.of(name.split(" ")));
        tokens.removeIf(t -> t.equals("Dr.") || t.isBlank());
        Set<String> out = new LinkedHashSet<>();
        if (tokens.size() >= 2) {
            out.add(String.join(" ", tokens.subList(1, tokens.size())) + " " + tokens.get(0));
            out.add(tokens.get(tokens.size() - 1) + " " + String.join(" ", tokens.subList(0, tokens.size() - 1)));
            out.remove(name);
        }
        return out;
    }

    /** Lignes de texte non vides de la page, sans balises, entités décodées. */
    static List<String> lines(String html) {
        List<String> out = new ArrayList<>();
        String body = html.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<svg.*?</svg>", "").replaceAll("(?s)<script.*?</script>", "")
                .replaceAll("(?s)<style.*?</style>", "");
        for (String raw : body.replaceAll("<[^>]+>", "\n").split("\n")) {
            String line = raw.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace(' ', ' ')
                    .replaceAll("\\s+", " ").trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
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
