package com.swiftcheck.aml.plugins.bepep.federalgovernment;

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
 * <b>be_pep_federal_government</b> – membres du gouvernement fédéral : Premier ministre, vice-Premiers ministres,
 * ministres et secrétaires d'État (catégorie BE-a ; loi du 18 septembre 2017, art. 4, 28°, a), Annexe IV, 1°, b)).
 *
 * <p>Source : page « Composition et répartition des compétences du gouvernement fédéral » de belgium.be. Type
 * {@code PLUGIN_MANAGED} : le plugin télécharge la page et la dépose dans {@code rawDir}. Chaque membre y est écrit
 * ainsi :
 * <pre>
 * &lt;p&gt;&lt;img src="/sites/default/files/De%20Wever.jpg" alt="Bart De Wever" …&gt;&lt;/p&gt;
 * &lt;p&gt;&lt;a href="/fr/bart_de_wever"&gt;Monsieur Bart De Wever, Premier ministre&lt;/a&gt;&lt;/p&gt;
 * </pre>
 * Paramètre : {@code {"url": "https://www.belgium.be/fr/la_belgique/pouvoirs_publics/autorites_federales/gouvernement_federal/composition_gouvernement"}}.
 * premier.be refuse les clients automatiques (HTTP 403) : il n'est pas utilisé.
 */
@Extension
public class BePepFederalGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "be-pep-federal-government";
    static final String TOPIC = "role.pep";

    static final String SITE = "https://www.belgium.be";
    static final String DEFAULT_URL = SITE
            + "/fr/la_belgique/pouvoirs_publics/autorites_federales/gouvernement_federal/composition_gouvernement";
    static final String FILE = "gouvernement-federal.html";
    static final String PHOTO_LICENCE = "© Belgium.be";
    /** En dessous, la page a changé de format : un gouvernement fédéral compte au moins une dizaine de membres. */
    static final int MIN_MEMBERS = 8;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - gouvernement federal)";
    private static final Pattern MEMBER = Pattern.compile(
            "<img[^>]*src=\"([^\"]+)\"[^>]*alt=\"([^\"]+)\"[^>]*>\\s*</p>\\s*<p>\\s*<a href=\"([^\"]+)\">\\s*"
                    + "(Monsieur|Madame)\\s+([^,<]+?)\\s*,\\s*([^<]+?)\\s*</a>");

    /** Un membre, tel qu'écrit sur la page. */
    record Member(String name, String function, String gender, String pageUrl, String photoUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.get("url");
        Files.createDirectories(ctx.rawDir());
        Files.write(ctx.rawDir().resolve(FILE), get(url == null || url.isBlank() ? DEFAULT_URL : url.trim()));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<Member> members = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(members.size() + " membres du gouvernement fédéral.");
    }

    /** Test du parser : chaque membre, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Lit la page de {@code rawDir} et écrit les membres. Séparé de {@link #parse} pour les tests. */
    List<Member> convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(FILE);
        if (!Files.exists(file)) {
            throw new IllegalStateException(FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Member> members = members(Files.readString(file, StandardCharsets.UTF_8));
        if (members.size() < MIN_MEMBERS) {
            throw new IllegalStateException("Gouvernement fédéral : " + members.size() + " membres lus, au moins "
                    + MIN_MEMBERS + " attendus. Le format de la page a probablement changé.");
        }
        members.forEach(m -> write(m, dataset));
        return members;
    }

    static List<Member> members(String html) {
        List<Member> members = new ArrayList<>();
        Matcher m = MEMBER.matcher(html);
        while (m.find()) {
            String name = text(m.group(5));
            members.add(new Member(name, capitalize(text(m.group(6))), "Madame".equals(m.group(4)) ? "female" : "male",
                    absolute(m.group(3)), absolute(m.group(1))));
        }
        return members;
    }

    private static void write(Member m, FtmDatasetBuilder dataset) {
        String[] words = m.name().split("\\s+", 2);

        // ID = nom : la page ne donne ni identifiant ni date de naissance
        FtmEntity person = dataset.newEntity("Person", m.name());
        person.addValue("name", m.name());
        person.addValue("firstName", words[0]);
        person.addValue("lastName", words.length > 1 ? words[1] : null);
        person.addValue("gender", m.gender());
        person.addValue("country", "be");
        person.addValue("position", m.function());
        person.addValue("sourceUrl", m.pageUrl());
        dataset.addEntity(person);
        if (m.photoUrl() != null) {
            dataset.addMedia(person.getId(), m.photoUrl(), m.pageUrl(), PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", m.function());
        position.addValue("name", m.function());
        position.addValue("country", "be");
        position.addValue("organization", "Gouvernement fédéral");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.name(), m.function());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", DEFAULT_URL);
        dataset.addEntity(occupancy);
    }

    private static String absolute(String href) {
        return href.startsWith("http") ? href.replace("http://", "https://") : SITE + href;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String text(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#039;", "'").replace(' ', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    private static byte[] get(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", USER_AGENT).header("Accept", "text/html,*/*;q=0.8").GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " : " + url);
        }
        return response.body();
    }
}
