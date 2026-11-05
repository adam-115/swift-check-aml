package com.swiftcheck.aml.plugins.iepep.stateenterprises;

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
 * <b>ie_pep_state_enterprises</b> – membres du conseil d'administration d'entreprises détenues par l'État irlandais
 * (organes de direction d'entreprises publiques, article 37 du Criminal Justice Act 2010), d'après la page officielle du
 * conseil de chaque entreprise.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètre {@code entities} : {@code "clé|Nom de l'entreprise|adresse;…"}. La clé choisit
 * l'extracteur, car chaque site publie son conseil à sa façon :
 * <ul>
 *   <li>{@code esb} : {@code <span class="fn">Nom</span>} suivi de {@code <span class="note role">Fonction</span>} ;</li>
 *   <li>{@code uisce_eireann} : {@code <h6>Nom</h6><p>Fonction</p>} ;</li>
 *   <li>{@code bord_na_mona} : {@code <div class="title">Nom Fonction</div>} entre « Our board members » et la suite.</li>
 * </ul>
 * Aucune liste de noms n'est embarquée : chaque exécution télécharge les pages. Les pages qui bloquent le client automatique
 * (An Post : contrôle Radware ; RTÉ : 403) ne sont pas contournées. Un site dont la page ne donne plus aucun nom fait échouer
 * l'exécution avec le nom de l'entreprise.
 */
@Extension
public class IePepStateEnterprisesParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ie-pep-state-enterprises";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Irlande - entreprises d'Etat)";

    private static final Pattern ESB = Pattern.compile(
            "(?is)<span class=\"fn\">(.*?)</span>\\s*(?:<span class=\"note role\">(.*?)</span>)?");
    private static final Pattern UISCE = Pattern.compile("(?is)<h6>\\s*(.*?)\\s*</h6>\\s*<p>(.*?)</p>");
    private static final Pattern BNM_TITLE = Pattern.compile("(?is)<div class=\"title\">(.*?)</div>");
    private static final Pattern TRAILING_ROLE = Pattern.compile(
            "(?i)\\s+(Chairperson|Chairman|Chairwoman|Chair|Deputy Chairperson|Deputy Chair|CEO)$");

    /** Une entreprise configurée. */
    record Entity(String key, String name, String url) {
    }

    /** Un administrateur lu. */
    record Director(String company, String name, String role) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<Entity> entities = entities(p.get("entities"));
        download(httpFetcher(delayMs(p)), entities, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), entities, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque administrateur, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), entities(p.get("entities")), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** « clé|Nom|adresse;clé|Nom|adresse » → entreprises, dans l'ordre. */
    static List<Entity> entities(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            throw new IllegalStateException("Paramètre « entities » manquant dans la configuration du parser "
                    + "(« esb|ESB|https://….;uisce_eireann|Uisce Éireann|https://… », voir le README du plugin).");
        }
        List<Entity> out = new ArrayList<>();
        for (String item : parameter.split(";")) {
            String[] parts = item.split("\\|", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new IllegalStateException("Entreprise invalide dans « entities » : « " + item + " » (attendu : clé|nom|adresse).");
            }
            String key = parts[0].trim();
            if (!List.of("esb", "uisce_eireann", "bord_na_mona").contains(key)) {
                throw new IllegalStateException("Extracteur inconnu « " + key + " » (esb, uisce_eireann, bord_na_mona).");
            }
            out.add(new Entity(key, parts[1].trim(), parts[2].trim()));
        }
        return out;
    }

    static void download(Fetcher fetcher, List<Entity> entities, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        for (Entity e : entities) {
            Files.writeString(rawDir.resolve(e.key() + ".html"), fetcher.get(e.url()), StandardCharsets.UTF_8);
        }
    }

    /** Lit les pages de {@code rawDir} et écrit les administrateurs ; retourne le message de bilan. */
    String convert(Path rawDir, List<Entity> entities, FtmDatasetBuilder dataset) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entity e : entities) {
            Path file = rawDir.resolve(e.key() + ".html");
            if (!Files.exists(file)) {
                throw new IllegalStateException(file.getFileName() + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
            List<Director> directors = extract(e, Files.readString(file, StandardCharsets.UTF_8));
            if (directors.isEmpty()) {
                throw new IllegalStateException("Aucun administrateur lu pour « " + e.name() + " » (" + e.url() + ") : "
                        + "la page a peut-être changé.");
            }
            directors.forEach(d -> write(d, e.url(), dataset));
            counts.put(e.name(), directors.size());
        }
        return counts.values().stream().mapToInt(Integer::intValue).sum() + " administrateurs : " + counts
                + ". Non couverts : les entreprises dont la page est bloquée ou sans noms lisibles.";
    }

    /** Extrait les administrateurs de la page selon l'extracteur de l'entreprise. */
    static List<Director> extract(Entity e, String html) {
        List<Director> out = new ArrayList<>();
        switch (e.key()) {
            case "esb" -> {
                Matcher m = ESB.matcher(html);
                while (m.find()) {
                    add(out, e, Html.text(m.group(1)), m.group(2) == null ? null : Html.text(m.group(2)));
                }
            }
            case "uisce_eireann" -> {
                Matcher m = UISCE.matcher(html);
                while (m.find()) {
                    add(out, e, Html.text(m.group(1)), Html.text(m.group(2)));
                }
            }
            case "bord_na_mona" -> {
                int start = html.indexOf("Our board members");
                if (start < 0) {
                    return out;
                }
                int end = html.indexOf("Protecting our people", start);
                Matcher m = BNM_TITLE.matcher(html.substring(start, end < 0 ? html.length() : end));
                while (m.find()) {
                    String text = Html.text(m.group(1));
                    String role = null;
                    int comma = text.indexOf(',');
                    if (comma > 0) {
                        role = text.substring(comma + 1).trim();
                        text = text.substring(0, comma).trim();
                    } else {
                        Matcher r = TRAILING_ROLE.matcher(text);
                        if (r.find()) {
                            role = r.group(1);
                            text = text.substring(0, r.start()).trim();
                        }
                    }
                    add(out, e, text, role);
                }
            }
            default -> throw new IllegalStateException("Extracteur inconnu : " + e.key());
        }
        return out;
    }

    private static void add(List<Director> out, Entity e, String name, String role) {
        if (name != null && !name.isBlank() && name.split("\\s+").length >= 2) {
            out.add(new Director(e.name(), name.trim(), role == null || role.isBlank() ? null : role.trim()));
        }
    }

    private static void write(Director d, String url, FtmDatasetBuilder dataset) {
        String role = d.role() == null ? "Board member" : d.role();
        String label = role + ", " + d.company();
        String[] words = d.name().split("\\s+");

        // ID = entreprise + nom : les pages ne donnent pas d'identifiant
        FtmEntity person = dataset.newEntity("Person", d.company(), d.name());
        person.addValue("name", d.name());
        person.addValue("firstName", words[0]);
        person.addValue("lastName", d.name().substring(d.name().indexOf(' ') + 1));
        person.addValue("country", "ie");
        person.addValue("position", label);
        person.addValue("sourceUrl", url);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", label);
        position.addValue("name", label);
        position.addValue("country", "ie");
        position.addValue("organization", d.company());
        position.addValue("topics", "gov.soe");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", d.company(), d.name(), role);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", url);
        dataset.addEntity(occupancy);
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 1500 : Long.parseLong(v.trim());
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            IOException last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    Thread.sleep(delayMs);
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(45))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "en-IE,en-GB;q=0.9,en;q=0.8")
                            .GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() == 200) {
                        return response.body();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrompu", e);
                } catch (IOException e) {
                    last = e;
                }
                try {
                    String stealth = com.swiftcheck.aml.sdk.StealthBrowserFetcher.fetchHtml(url);
                    if (stealth != null && !stealth.isBlank()) {
                        return stealth;
                    }
                } catch (Exception ex) {
                    last = new IOException("Erreur de lecture (" + ex.getMessage() + ")", ex);
                }
            }
            throw last != null ? last : new IOException("Impossible de télécharger " + url);
        };
    }
}
