package com.swiftcheck.aml.plugins.cypep.parliament;

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
 * <b>cy_pep_parliament</b> – députés de la Chambre des représentants de Chypre en fonction, personnes politiquement exposées au
 * sens de la loi chypriote sur la prévention et la répression du blanchiment de capitaux (loi 188(I)/2007).
 *
 * <p>Source officielle : la page « Members of the House / Biographical notes » de {@code www.parliament.cy} : une seule requête
 * (robots.txt : tout autorisé), qui donne les 56 députés écrits « NOM Prénom » avec le lien de leur fiche. Les fiches individuelles
 * (naissance, circonscription, parti) ne sont <b>pas</b> lues : le site est protégé par Cloudflare, qui affiche une vérification de
 * navigateur à partir de quelques requêtes successives ; elle n'est pas contournée. Si la page de liste elle-même affiche cette
 * vérification, le parser s'arrête avec un message clair. Le site ne publie pas encore les députés de la législature 2021-2026
 * (page « Former members » arrêtée à 2021) : les députés sortis depuis moins de 12 mois ne sont pas couverts.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"url": "https://www.parliament.cy/en/general-information/composition/members-of-the-house/biographical-notes"}
 * }</pre>
 */
@Extension
public class CyPepParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "cy-pep-parliament";
    static final String TOPIC = "role.pep";
    static final String FILE = "members.html";
    static final String SEAT = "Member of the House of Representatives (Βουλευτής)";
    static final String DEFAULT_URL = "https://www.parliament.cy/en/general-information/composition/members-of-the-house/biographical-notes";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Chypre - Chambre des représentants)";
    private static final Pattern MEMBER = Pattern.compile(
            "<a href=\"([^\"]*biographical-notes/([^\"/]+))\"[^>]*>\\s*<h6>\\s*([^<]+?)\\s*</h6>", Pattern.DOTALL);

    /** Un député : identifiant de fiche (fin de l'adresse), nom d'usage « Prénom Nom » et nom publié « NOM Prénom ». */
    record Member(String slug, String name, String published, String path) {
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

        FtmEntity position = dataset.newEntity("Position", "house-of-representatives");
        position.addValue("name", SEAT);
        position.addValue("country", "cy");
        position.addValue("organization", "Chambre des représentants de Chypre (Βουλή των Αντιπροσώπων)");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        for (Member m : members) {
            FtmEntity person = dataset.newEntity("Person", m.slug());
            person.addValue("name", m.name());
            for (String alias : aliases(m)) {
                person.addValue("alias", alias);
            }
            person.addValue("country", "cy");
            person.addValue("nationality", "cy");
            person.addValue("position", SEAT);
            person.addValue("sourceUrl", "https://www.parliament.cy" + m.path());
            dataset.addEntity(person);

            FtmEntity occupancy = dataset.newEntity("Occupancy", m.slug(), "house-of-representatives");
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", url);
            dataset.addEntity(occupancy);
        }
        return members.size() + " députés en fonction. Source : Βουλή των Αντιπροσώπων, www.parliament.cy.";
    }

    /** Les députés de la page de liste ; s'arrête si la page est la vérification Cloudflare ou si le format a changé. */
    static List<Member> members(String html) {
        if (html.contains("Protected by Cloudflare") || html.contains("Έλεγχος Ασφαλείας") || html.contains("Enable JavaScript and cookies to continue")) {
            throw new IllegalStateException("www.parliament.cy affiche la vérification de sécurité Cloudflare au lieu de la liste : "
                    + "elle n'est pas contournée, réessayer plus tard.");
        }
        List<Member> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = MEMBER.matcher(html);
        while (m.find()) {
            String published = m.group(3).replaceAll("\\s+", " ").trim();
            String slug = m.group(2);
            if (seen.add(slug)) {
                out.add(new Member(slug, usualName(published), published, m.group(1)));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page « Biographical notes » : aucun député trouvé (la page a changé).");
        }
        return out;
    }

    /** « PAMBORIDIS George P. » → « George P. Pamboridis » : les mots tout en capitales du début sont le nom de famille. */
    static String usualName(String published) {
        String[] words = published.split(" ");
        int surname = 0;
        while (surname < words.length - 1 && isUpper(words[surname])) {
            surname++;
        }
        surname = Math.max(surname, 1);
        String family = String.join(" ", titleCase(String.join(" ", java.util.Arrays.copyOfRange(words, 0, surname))));
        String given = String.join(" ", java.util.Arrays.copyOfRange(words, surname, words.length));
        return given.isEmpty() ? family : given + " " + family;
    }

    /** Nom publié en casse normale, en alias : « Pamboridis George P. ». */
    static Set<String> aliases(Member m) {
        Set<String> out = new LinkedHashSet<>();
        String[] words = m.published().split(" ");
        int surname = 0;
        while (surname < words.length - 1 && isUpper(words[surname])) {
            surname++;
        }
        surname = Math.max(surname, 1);
        String family = titleCase(String.join(" ", java.util.Arrays.copyOfRange(words, 0, surname)));
        String given = String.join(" ", java.util.Arrays.copyOfRange(words, surname, words.length));
        if (!given.isEmpty()) {
            out.add(family + " " + given);
        }
        out.remove(m.name());
        return out;
    }

    private static boolean isUpper(String word) {
        int letters = 0;
        for (char c : word.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (!Character.isUpperCase(c)) {
                    return false;
                }
            }
        }
        return letters > 1;                 // « K. » ou « P. » sont des initiales de prénom
    }

    /** « HADJIGEORGIOU » → « Hadjigeorgiou » ; tiret et apostrophe ouvrent une majuscule. */
    static String titleCase(String text) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char c : text.toCharArray()) {
            out.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = c == ' ' || c == '-' || c == '\'' || c == '’';
        }
        return out.toString();
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
