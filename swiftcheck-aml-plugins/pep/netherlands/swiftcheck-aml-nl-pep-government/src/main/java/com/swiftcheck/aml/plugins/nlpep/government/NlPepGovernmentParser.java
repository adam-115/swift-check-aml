package com.swiftcheck.aml.plugins.nlpep.government;

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
 * <b>nl_pep_government</b> – ministre-président, ministres et secrétaires d'État du gouvernement néerlandais, d'après les
 * pages officielles du gouvernement ({@code rijksoverheid.nl}).
 *
 * <p>La page « bewindspersonen » liste chaque membre (nom, fonction, lien vers sa fiche) ; la fiche donne la naissance
 * (« Geboren op 25 maart 1987 in Veghel »), le parti (« Partij: D66 ») et la date de nomination (rubrique « Loopbaan »).
 * Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"listUrl": "https://www.rijksoverheid.nl/regering/bewindspersonen", "delayMs": "1000"}
 * }</pre>
 * La page ne liste que les membres en fonction : les anciens ministres sont gardés par le core pendant la durée de
 * rétention du parser.
 */
@Extension
public class NlPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "nl-pep-government";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String MANIFEST = "manifest.tsv";
    static final String LIST_FILE = "list.html";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (lijst PEP Nederland - kabinet)";

    private static final Pattern CARD = Pattern.compile(
            "(?is)<a href=\"(/regering/bewindspersonen/[^\"/]+)\"[^>]*>(.*?)</a>");
    private static final Pattern CARD_NAME = Pattern.compile("(?is)<span class=\"link-plus__title\">(.*?)</span>");
    private static final Pattern CARD_ROLE = Pattern.compile("(?is)<p class=\"card__subtitle\">(.*?)</p>");
    private static final Pattern BIRTH = Pattern.compile(
            "Geboren op (\\d{1,2}) ([a-zé]+) (\\d{4}) in ([^,.<]+)");
    private static final Pattern PARTY = Pattern.compile("Partij:\\s*([^<\\n.]+)");
    private static final Pattern DUTCH_DATE = Pattern.compile("(\\d{1,2}) ([a-zé]+) (\\d{4})");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("januari", 1), Map.entry("februari", 2),
            Map.entry("maart", 3), Map.entry("april", 4), Map.entry("mei", 5), Map.entry("juni", 6), Map.entry("juli", 7),
            Map.entry("augustus", 8), Map.entry("september", 9), Map.entry("oktober", 10), Map.entry("november", 11),
            Map.entry("december", 12));

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Un membre du gouvernement tel que listé. */
    record Member(String slug, String name, String role, String url) {
    }

    /** Détails lus sur la fiche d'un membre. */
    record Details(String birthDate, String birthPlace, String party, String appointed) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(delayMs(p)), required(p, "listUrl"), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(count + " membres du gouvernement (ministres et secrétaires d'État).");
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
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Télécharge la liste puis la fiche de chaque membre dans {@code rawDir/pages}, et écrit le manifeste. */
    static void download(Fetcher fetcher, String listUrl, Path rawDir) throws IOException, InterruptedException {
        Path pages = rawDir.resolve("pages");
        Files.createDirectories(pages);
        String list = fetcher.get(listUrl);
        Files.writeString(rawDir.resolve(LIST_FILE), list, StandardCharsets.UTF_8);
        List<Member> members = members(list, listUrl);
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre du gouvernement dans " + listUrl + " (liens « /regering/bewindspersonen/… ») : "
                    + "la page a peut-être changé.");
        }
        StringBuilder manifest = new StringBuilder();
        int i = 0;
        for (Member m : members) {
            String file = String.format("page-%03d.html", i++);
            Files.writeString(pages.resolve(file), fetcher.get(m.url()), StandardCharsets.UTF_8);
            manifest.append(m.slug()).append('\t').append(file).append('\n');
        }
        Files.writeString(rawDir.resolve(MANIFEST), manifest.toString(), StandardCharsets.UTF_8);
    }

    /** Membres de la page de liste : nom, fonction et adresse de la fiche. */
    static List<Member> members(String html, String listUrl) {
        Map<String, Member> out = new LinkedHashMap<>();
        Matcher m = CARD.matcher(html);
        URI base = URI.create(listUrl);
        while (m.find()) {
            Matcher name = CARD_NAME.matcher(m.group(2));
            Matcher role = CARD_ROLE.matcher(m.group(2));
            if (!name.find() || !role.find()) {
                continue;
            }
            String path = m.group(1);
            String slug = path.substring(path.lastIndexOf('/') + 1);
            out.putIfAbsent(slug, new Member(slug, Html.text(name.group(1)), Html.text(role.group(1)), base.resolve(path).toString()));
        }
        return new ArrayList<>(out.values());
    }

    /** Naissance, parti et date de nomination d'une fiche (lue ligne par ligne : une balise ferme une ligne). */
    static Details details(String html) {
        String clean = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\1>", " ").replaceAll("<[^>]+>", "\n");
        List<String> lines = new ArrayList<>();
        for (String raw : Html.decode(clean).split("\n")) {
            String line = raw.replace(' ', ' ').replaceAll("\s+", " ").trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        String birthDate = null, birthPlace = null, party = null, appointed = null;
        for (String line : lines) {
            Matcher b = BIRTH.matcher(line);
            if (birthDate == null && b.find() && MONTHS.containsKey(b.group(2))) {
                birthDate = LocalDate.of(Integer.parseInt(b.group(3)), MONTHS.get(b.group(2)), Integer.parseInt(b.group(1))).toString();
                birthPlace = b.group(4).trim();
            }
            Matcher p = PARTY.matcher(line);
            if (party == null && p.find()) {
                party = p.group(1).trim();
            }
        }
        int career = lines.indexOf("Loopbaan");
        if (career >= 0 && career + 2 < lines.size() && lines.get(career + 2).startsWith("Benoeming")) {
            Matcher a = DUTCH_DATE.matcher(lines.get(career + 1));
            if (a.matches() && MONTHS.containsKey(a.group(2))) {
                appointed = LocalDate.of(Integer.parseInt(a.group(3)), MONTHS.get(a.group(2)), Integer.parseInt(a.group(1))).toString();
            }
        }
        return new Details(birthDate, birthPlace, party, appointed);
    }

    /** Lit le manifeste, les fiches et écrit les membres ; retourne leur nombre. */
    int convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Path manifest = rawDir.resolve(MANIFEST);
        Path listFile = rawDir.resolve(LIST_FILE);
        if (!Files.exists(manifest) || !Files.exists(listFile)) {
            throw new IllegalStateException(MANIFEST + " ou " + LIST_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        Map<String, Member> bySlug = new LinkedHashMap<>();
        for (Member m : members(Files.readString(listFile, StandardCharsets.UTF_8), "https://www.rijksoverheid.nl/regering/bewindspersonen")) {
            bySlug.put(m.slug(), m);
        }
        int count = 0;
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            Member m = bySlug.get(parts[0]);
            if (m == null) {
                continue;
            }
            write(m, details(Files.readString(rawDir.resolve("pages").resolve(parts[1]), StandardCharsets.UTF_8)), dataset);
            count++;
        }
        if (count == 0) {
            throw new IllegalStateException("Aucun membre du gouvernement lu dans " + rawDir + ".");
        }
        return count;
    }

    private static void write(Member m, Details d, FtmDatasetBuilder dataset) {
        String[] words = m.name().split("\\s+");
        FtmEntity person = dataset.newEntity("Person", m.slug());
        person.addValue("name", m.name());
        person.addValue("firstName", words[0]);
        person.addValue("lastName", words.length > 1 ? m.name().substring(m.name().indexOf(' ') + 1) : null);
        person.addValue("birthDate", d.birthDate());
        person.addValue("birthPlace", d.birthPlace());
        person.addValue("political", d.party());
        person.addValue("country", "nl");
        person.addValue("position", m.role());
        person.addValue("sourceUrl", m.url());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", m.role());
        position.addValue("name", m.role());
        position.addValue("country", "nl");
        position.addValue("organization", "Rijksoverheid");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.executive");
        if (m.role().toLowerCase().contains("minister-president")) {
            position.addValue("topics", "gov.head");
        }
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.slug(), m.role());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", d.appointed());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", d.party() == null ? null : "Parti : " + d.party());
        occupancy.addValue("sourceUrl", m.url());
        dataset.addEntity(occupancy);
    }

    static String required(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Paramètre « " + key + " » manquant dans la configuration du parser (voir le README du plugin).");
        }
        return v.trim();
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 1000 : Long.parseLong(v.trim());
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
