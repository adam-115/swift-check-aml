package com.swiftcheck.aml.plugins.cypep.institutions;

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
 * <b>cy_pep_institutions</b> – gouverneur et membres du conseil d'administration de la Banque centrale de Chypre, personnes
 * politiquement exposées au sens de la loi chypriote sur la prévention et la répression du blanchiment de capitaux.
 *
 * <p>Source officielle : la page « Board of Directors » de {@code www.centralbank.cy} (robots.txt : seuls /cgi-bin/ et /console sont
 * interdits ; une requête, User-Agent générique). Elle donne trois groupes sous des titres : « Governor », « Executive Members »,
 * « Non-Executive Members ». Pas de naissance ni de dates de fonction. Les autres institutions chypriotes (présidence, Service
 * d'information, Service d'audit : pare-feu Azure WAF qui répond 403 ; Cour suprême et ministères : injoignables) ne sont pas lues.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"url": "https://www.centralbank.cy/en/the-bank/board-of-directors"}
 * }</pre>
 */
@Extension
public class CyPepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "cy-pep-institutions";
    static final String TOPIC = "role.pep";
    static final String FILE = "board.html";
    static final String DEFAULT_URL = "https://www.centralbank.cy/en/the-bank/board-of-directors";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Chypre - Banque centrale)";
    private static final Pattern BLOCK = Pattern.compile("<h([35])[^>]*>(.*?)</h\\1>", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("^\\p{Lu}[\\p{L}'’.-]+(?: \\p{Lu}[\\p{L}'’.-]+){1,3}$");
    private static final Map<String, String> ROLES = Map.of(
            "governor", "Governor of the Central Bank of Cyprus",
            "executive members", "Executive member of the Board of Directors of the Central Bank of Cyprus",
            "non-executive members", "Non-executive member of the Board of Directors of the Central Bank of Cyprus");

    /** Un membre du conseil : nom et fonction. */
    record Member(String name, String role, boolean governor) {
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
            person.addValue("country", "cy");
            person.addValue("nationality", "cy");
            person.addValue("position", m.role());
            person.addValue("sourceUrl", url);
            dataset.addEntity(person);

            FtmEntity position = positions.get(m.role());
            if (position == null) {
                position = dataset.newEntity("Position", m.role());
                position.addValue("name", m.role());
                position.addValue("country", "cy");
                position.addValue("organization", "Central Bank of Cyprus (Κεντρική Τράπεζα της Κύπρου)");
                position.addValue("topics", "gov.national");
                position.addValue("topics", "gov.financial");
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
        return members.size() + " membres du conseil de la Banque centrale de Chypre. Source : www.centralbank.cy.";
    }

    /** Les membres sous leurs titres « Governor », « Executive Members » et « Non-Executive Members ». */
    static List<Member> members(String html) {
        List<Member> out = new ArrayList<>();
        String role = null;
        boolean seenGovernor = false;
        Matcher m = BLOCK.matcher(html);
        while (m.find()) {
            String text = clean(m.group(2));
            if (m.group(1).equals("3")) {
                String known = ROLES.get(text.toLowerCase());
                if (known != null) {
                    role = known;
                    seenGovernor = seenGovernor || text.equalsIgnoreCase("Governor");
                } else if (seenGovernor) {
                    role = null;                    // titre d'une autre rubrique : fin de la liste
                }
            } else if (role != null && NAME.matcher(text).matches()) {
                out.add(new Member(text, role, role.startsWith("Governor")));
            }
        }
        if (out.stream().noneMatch(Member::governor)) {
            throw new IllegalStateException("Page « Board of Directors » : gouverneur introuvable (la page a changé).");
        }
        return out;
    }

    /** « Christodoulos Patsalides » → « Patsalides Christodoulos ». */
    static Set<String> aliases(String name) {
        String[] words = name.split(" ");
        Set<String> out = new LinkedHashSet<>();
        if (words.length >= 2) {
            out.add(words[words.length - 1] + " " + String.join(" ", java.util.Arrays.copyOfRange(words, 0, words.length - 1)));
        }
        out.remove(name);
        return out;
    }

    static String clean(String html) {
        return html.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replace(' ', ' ').replaceAll("\\s+", " ").trim();
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
