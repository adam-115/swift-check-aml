package com.swiftcheck.aml.plugins.skpep.government;

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
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>sk_pep_government</b> – président du gouvernement, vice-présidents et ministres du Gouvernement de la République slovaque,
 * personnes politiquement exposées au sens de la loi slovaque 297/2008 sur la protection contre le blanchiment de capitaux.
 *
 * <p>Source officielle : la page « Členovia vlády » de {@code www.vlada.gov.sk/vlada-sr/clenovia-vlady/} : pour chaque membre, trois
 * lignes « nom / fonction / ministère ». La page porte une date de mise à jour (« Aktualizované … ») reprise dans le message.
 * Une entrée dont le nom est coupé par le site (« T | omáš Drucker ») n'est pas lue : la personne figure déjà plus haut avec sa
 * première fonction. Ni naissance ni dates de fonction ; pas d'anciens membres. Le site n'a pas de robots.txt (404) ; un appel,
 * User-Agent générique.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"url": "https://www.vlada.gov.sk/vlada-sr/clenovia-vlady/"}
 * }</pre>
 */
@Extension
public class SkPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "sk-pep-government";
    static final String TOPIC = "role.pep";
    static final String FILE = "members.html";
    static final String DEFAULT_URL = "https://www.vlada.gov.sk/vlada-sr/clenovia-vlady/";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Slovaquie - Gouvernement)";
    private static final Pattern NAME = Pattern.compile("^\\p{Lu}[\\p{L}'’.-]*(?: \\p{Lu}[\\p{L}'’.-]*)+$");
    private static final Pattern ROLE = Pattern.compile("(?iu)^(predsed|podpredsed|minister|ministerk|poveren).*");
    private static final Pattern UPDATED = Pattern.compile("^Aktualizované (\\d{2}\\.\\d{2}\\.\\d{4})$");

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
        String html = Files.readString(file, StandardCharsets.UTF_8);
        List<Member> members = members(html);
        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        for (Member m : members) {
            FtmEntity person = dataset.newEntity("Person", m.name());
            person.addValue("name", m.name());
            person.addValue("country", "sk");
            person.addValue("nationality", "sk");
            person.addValue("position", m.role());
            person.addValue("sourceUrl", url);
            dataset.addEntity(person);

            FtmEntity position = positions.get(m.role());
            if (position == null) {
                position = dataset.newEntity("Position", m.role());
                position.addValue("name", m.role());
                position.addValue("country", "sk");
                position.addValue("organization", "Vláda Slovenskej republiky (Gouvernement de la République slovaque)");
                position.addValue("topics", "gov.national");
                position.addValue("topics", "gov.executive");
                if (m.role().startsWith("Predseda vlády")) {
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
        String updated = lines(html).stream().map(UPDATED::matcher).filter(java.util.regex.Matcher::matches)
                .map(m -> m.group(1)).findFirst().orElse("date inconnue");
        return members.size() + " membres du Gouvernement (page mise à jour le " + updated + "). Source : Úrad vlády SR, www.vlada.gov.sk.";
    }

    /** Les membres de la page : une ligne de nom suivie d'une ligne de fonction ; les doublons de nom sont écartés. */
    static List<Member> members(String html) {
        List<String> lines = lines(html);
        Map<String, Member> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (NAME.matcher(lines.get(i)).matches() && ROLE.matcher(lines.get(i + 1)).matches()) {
                out.putIfAbsent(lines.get(i), new Member(lines.get(i), lines.get(i + 1)));
                i++;
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page des membres du Gouvernement : aucun membre trouvé (la page a changé).");
        }
        return new ArrayList<>(out.values());
    }

    /** Lignes de texte non vides de la page, sans balises, entités décodées. */
    static List<String> lines(String html) {
        List<String> out = new ArrayList<>();
        String body = html.replaceAll("(?s)<svg.*?</svg>", "").replaceAll("(?s)<script.*?</script>", "")
                .replaceAll("(?s)<style.*?</style>", "");
        for (String raw : body.replaceAll("<[^>]+>", "\n").split("\n")) {
            String line = raw.replace("&nbsp;", " ").replace("&amp;", "&").replace(' ', ' ').replaceAll("\\s+", " ").trim();
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
