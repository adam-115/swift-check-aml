package com.swiftcheck.aml.plugins.frpep.army;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.army.ChiefPage.Chief;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_army</b> – chef d'état-major des armées et chefs d'état-major de l'armée de Terre, de la Marine et de
 * l'armée de l'Air et de l'Espace (catégorie FR-f ; code de la défense, art. R*3121-1 et R*3121-25 ; CMF,
 * art. R. 561-18).
 *
 * <p>Source : la biographie de chaque chef sur defense.gouv.fr (voir {@link ChiefPage}). Type {@code PLUGIN_MANAGED} :
 * une page par chef, le plugin les télécharge et les dépose dans {@code rawDir}. Paramètres, une adresse par
 * fonction (une adresse vide = fonction non couverte) :
 * <pre>{@code
 * {"cemaUrl": "https://www.defense.gouv.fr/ema/chef-detat-major-armees",
 *  "cematUrl": "https://www.defense.gouv.fr/terre/chef-detat-major-larmee-terre/biographie-du-chef-detat-major-larmee-terre",
 *  "cemmUrl": "",
 *  "cemaaeUrl": "https://www.defense.gouv.fr/air/biographie-du-chef-detat-major-larmee-lair-lespace"}
 * }</pre>
 * Le site n'a pas de biographie à jour du chef d'état-major de la Marine (octobre 2026) : {@code cemmUrl} est vide.
 */
@Extension
public class FrPepArmyParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-army";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - chefs d'etat-major)";

    /** Paramètre d'adresse → fonction, dans l'ordre protocolaire. */
    static final Map<String, String> FUNCTIONS = new LinkedHashMap<>();
    static {
        FUNCTIONS.put("cema", "Chef d'état-major des armées");
        FUNCTIONS.put("cemat", "Chef d'état-major de l'armée de Terre");
        FUNCTIONS.put("cemm", "Chef d'état-major de la Marine");
        FUNCTIONS.put("cemaae", "Chef d'état-major de l'armée de l'Air et de l'Espace");
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(), p, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<String> covered = convert(ctx.rawDir(), p, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        List<String> missing = FUNCTIONS.keySet().stream().filter(k -> !covered.contains(k)).map(FUNCTIONS::get).toList();
        return dataset.toConversionResult().withMessage(covered.size() + " chefs d'état-major."
                + (missing.isEmpty() ? "" : " Non couverts (pas d'adresse configurée) : " + String.join(", ", missing) + "."));
    }

    /** Test du parser : chaque chef, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), p, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Télécharge la page de chaque fonction configurée sous {@code <fonction>.html}. */
    static void download(Fetcher fetcher, Map<String, String> p, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        if (FUNCTIONS.keySet().stream().allMatch(k -> url(p, k) == null)) {
            throw new IllegalStateException("Aucune adresse configurée : renseignez au moins un des paramètres "
                    + "cemaUrl, cematUrl, cemmUrl, cemaaeUrl.");
        }
        for (String key : FUNCTIONS.keySet()) {
            String url = url(p, key);
            if (url != null) {
                Files.writeString(rawDir.resolve(key + ".html"), fetcher.get(url));
            }
        }
    }

    /** Écrit un chef par page déposée. @return les fonctions couvertes */
    List<String> convert(Path rawDir, Map<String, String> p, FtmDatasetBuilder dataset) throws Exception {
        List<String> covered = new ArrayList<>();
        for (Map.Entry<String, String> function : FUNCTIONS.entrySet()) {
            Path page = rawDir.resolve(function.getKey() + ".html");
            if (url(p, function.getKey()) == null || !Files.exists(page)) {
                continue;
            }
            Chief chief;
            try {
                chief = ChiefPage.read(Files.readString(page));
            } catch (IllegalStateException e) {
                throw new IllegalStateException(function.getValue() + " (" + url(p, function.getKey()) + ") : " + e.getMessage(), e);
            }
            write(function.getKey(), function.getValue(), chief, url(p, function.getKey()), dataset);
            covered.add(function.getKey());
        }
        return covered;
    }

    private static void write(String key, String function, Chief c, String pageUrl, FtmDatasetBuilder dataset) {
        // ID = nom + date de naissance : la page ne donne pas d'identifiant
        FtmEntity person = dataset.newEntity("Person", c.name(), c.birthDate());
        String[] parts = c.name().split("\\s+", 2);
        person.addValue("name", c.name());
        person.addValue("firstName", parts[0]);
        person.addValue("lastName", parts.length > 1 ? parts[1] : null);
        person.addValue("title", c.rank());
        person.addValue("birthDate", c.birthDate());
        person.addValue("birthPlace", c.birthPlace());
        person.addValue("country", "fr");
        person.addValue("nationality", "fr");
        person.addValue("position", function);
        person.addValue("sourceUrl", pageUrl);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", key);
        position.addValue("name", function);
        position.addValue("country", "fr");
        position.addValue("organization", "Ministère des Armées");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.security");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", c.name(), c.birthDate(), key);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", c.startDate());
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", pageUrl);
        dataset.addEntity(occupancy);
    }

    private static String url(Map<String, String> p, String key) {
        String value = p.get(key + "Url");
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                    .header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
