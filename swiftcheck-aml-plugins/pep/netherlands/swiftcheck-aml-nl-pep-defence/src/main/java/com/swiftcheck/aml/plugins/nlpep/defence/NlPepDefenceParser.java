package com.swiftcheck.aml.plugins.nlpep.defence;

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
 * <b>nl_pep_defence</b> – commandants des forces armées néerlandaises (Commandant der Strijdkrachten, commandants des
 * forces terrestres, navales, aériennes et de la Maréchaussée), officiers de haut rang au sens de la Wwft.
 *
 * <p>Source : la page de chaque commandant sur {@code defensie.nl}. Type {@code PLUGIN_MANAGED}. Paramètre {@code pages} :
 * adresses séparées par des points-virgules. Chaque page donne sa fonction dans le titre et deux phrases :
 * <pre>
 * Sinds 8 maart 2024 is luitenant-generaal Jan Swillens de Commandant Landstrijdkrachten.
 * Curriculum vitae luitenant-generaal Jan Swillens
 * </pre>
 * Le grade et le nom viennent de la ligne « Curriculum vitae », la date de prise de fonctions de « Sinds … ». Une page sans
 * ces deux éléments fait échouer l'exécution avec son adresse. Les officiers sortis ne sont pas nommés (le core garde une
 * personne retirée de la source pendant la durée de rétention).
 * <pre>{@code
 * {"pages": "https://www.defensie.nl/defensieonderdelen/bestuursstaf/cds;https://www.defensie.nl/…/koninklijke-landmacht/c-las", "delayMs": "1500"}
 * }</pre>
 */
@Extension
public class NlPepDefenceParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "nl-pep-defence";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (lijst PEP Nederland - Defensie)";
    private static final Pattern TITLE = Pattern.compile("(?is)<title>(.*?)</title>");
    private static final Pattern CV = Pattern.compile("^Curriculum vitae (luitenant-generaal|generaal-majoor|brigade-generaal|"
            + "vice-?admiraal|schout-bij-nacht|commandeur|luchtvaartgeneraal|generaal|admiraal|kolonel) (.+)$");
    private static final Pattern SINCE = Pattern.compile("Sinds (?:Sinds )?(\\d{1,2}) ([a-zé]+) (\\d{4})");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("januari", 1), Map.entry("februari", 2),
            Map.entry("maart", 3), Map.entry("april", 4), Map.entry("mei", 5), Map.entry("juni", 6), Map.entry("juli", 7),
            Map.entry("augustus", 8), Map.entry("september", 9), Map.entry("oktober", 10), Map.entry("november", 11),
            Map.entry("december", 12));

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Un commandant lu sur sa page. */
    record Commander(String function, String rank, String name, LocalDate since, String url) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<String> pages = pages(p.get("pages"));
        download(httpFetcher(delayMs(p)), pages, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(ctx.rawDir(), pages, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(count + " commandants des forces armées.");
    }

    /** Test du parser : chaque commandant, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), pages(p.get("pages")), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    static List<String> pages(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            throw new IllegalStateException("Paramètre « pages » manquant dans la configuration du parser "
                    + "(adresses des pages des commandants sur defensie.nl séparées par « ; », voir le README du plugin).");
        }
        List<String> out = new ArrayList<>();
        for (String url : parameter.split(";")) {
            if (!url.isBlank()) {
                out.add(url.trim());
            }
        }
        return out;
    }

    static void download(Fetcher fetcher, List<String> pages, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        int i = 0;
        for (String url : pages) {
            Files.writeString(rawDir.resolve("page-" + i++ + ".html"), fetcher.get(url), StandardCharsets.UTF_8);
        }
    }

    /** Lit les pages de {@code rawDir} et écrit les commandants ; retourne leur nombre. */
    int convert(Path rawDir, List<String> pages, FtmDatasetBuilder dataset) throws IOException {
        int count = 0;
        for (int i = 0; i < pages.size(); i++) {
            Path file = rawDir.resolve("page-" + i + ".html");
            if (!Files.exists(file)) {
                throw new IllegalStateException(file.getFileName() + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
            write(read(Files.readString(file, StandardCharsets.UTF_8), pages.get(i)), dataset);
            count++;
        }
        return count;
    }

    /**
     * Lit la page d'un commandant.
     *
     * @throws IllegalStateException si la page ne contient pas la ligne « Curriculum vitae » ou la phrase « Sinds … »
     */
    static Commander read(String html, String url) {
        Matcher title = TITLE.matcher(html);
        String function = title.find() ? Html.text(title.group(1)) : null;
        List<String> lines = new ArrayList<>();
        for (String raw : Html.decode(html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", "\n")).split("\n")) {
            String line = raw.replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        Matcher cv = null;
        String since = null;
        for (String line : lines) {
            Matcher c = CV.matcher(line);
            if (cv == null && c.matches()) {
                cv = c;
            }
            Matcher s = SINCE.matcher(line);
            if (since == null && s.find() && MONTHS.containsKey(s.group(2))) {
                since = LocalDate.of(Integer.parseInt(s.group(3)), MONTHS.get(s.group(2)), Integer.parseInt(s.group(1))).toString();
            }
        }
        if (function == null || cv == null || since == null) {
            throw new IllegalStateException("La page " + url + " ne donne pas le commandant (ligne « Curriculum vitae <grade> <nom> » et "
                    + "phrase « Sinds <date> … ») : la page a peut-être changé.");
        }
        return new Commander(function, cv.group(1), cv.group(2).trim(), LocalDate.parse(since), url);
    }

    private static void write(Commander c, FtmDatasetBuilder dataset) {
        String[] words = c.name().split("\\s+");
        FtmEntity person = dataset.newEntity("Person", c.name());
        person.addValue("name", c.name());
        person.addValue("alias", c.rank() + " " + c.name());
        person.addValue("firstName", words[0]);
        person.addValue("lastName", words.length > 1 ? c.name().substring(c.name().indexOf(' ') + 1) : null);
        person.addValue("country", "nl");
        person.addValue("position", c.function());
        person.addValue("sourceUrl", c.url());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", c.function());
        position.addValue("name", c.function());
        position.addValue("country", "nl");
        position.addValue("organization", "Defensie");
        position.addValue("topics", "gov.security");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", c.name(), c.function());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", c.since().toString());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", "Grade : " + c.rank());
        occupancy.addValue("sourceUrl", c.url());
        dataset.addEntity(occupancy);
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 1500 : Long.parseLong(v.trim());
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
