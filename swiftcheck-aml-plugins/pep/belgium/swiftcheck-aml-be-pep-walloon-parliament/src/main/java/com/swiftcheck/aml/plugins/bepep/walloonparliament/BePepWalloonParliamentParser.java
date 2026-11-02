package com.swiftcheck.aml.plugins.bepep.walloonparliament;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pf4j.Extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>be_pep_walloon_parliament</b> – membres du Parlement de Wallonie (catégorie BE-b ; loi du 18 septembre 2017,
 * art. 4, 28°, b), Annexe IV, 2° : « le Président du Parlement, les membres du parlement »).
 *
 * <p>Source : open data du Parlement de Wallonie, un seul fichier JSON des 75 députés en fonction
 * ({@code opendata/composition_dep_od.php?f=json}). Type {@code PLUGIN_MANAGED}. Chaque député : identifiant
 * ({@code dep_id}), nom, prénom, civilité, parti, circonscription, fonction au bureau ({@code dep_fonction_bureau} :
 * « Président du Parlement de Wallonie »…) et portrait officiel ({@code dep_photo}). Paramètre :
 * <pre>{@code
 * {"url": "https://www.parlement-wallonie.be/opendata/composition_dep_od.php?f=json"}
 * }</pre>
 * Le fichier donne aussi l'adresse postale, le téléphone, l'e-mail et les réseaux sociaux de chaque député : ces
 * coordonnées ne sont <b>jamais</b> reprises. Il ne donne pas la date de naissance.
 */
@Extension
public class BePepWalloonParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "be-pep-walloon-parliament";
    static final String TOPIC = "role.pep";

    static final String DEFAULT_URL = "https://www.parlement-wallonie.be/opendata/composition_dep_od.php?f=json";
    static final String FILE = "parlement-wallonie-deputes.json";
    static final String POSITION = "Membre du Parlement de Wallonie";
    static final String PHOTO_LICENCE = "© Parlement de Wallonie";
    /** En dessous, le fichier a changé de format : le Parlement compte 75 députés. */
    static final int MIN_MEMBERS = 60;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - Parlement de Wallonie)";
    private static final ObjectMapper JSON = new ObjectMapper();

    record Report(int members, int bureau) {

        String message() {
            return String.format("%d membres du Parlement de Wallonie, dont %d au bureau.", members, bureau);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.getOrDefault("url", DEFAULT_URL);
        Files.createDirectories(ctx.rawDir());
        Files.write(ctx.rawDir().resolve(FILE), get(url == null || url.isBlank() ? DEFAULT_URL : url.trim()));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque député, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(), DEFAULT_URL));
            }
        }
        return records;
    }

    /** Lit le fichier de {@code rawDir} et écrit les députés. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(FILE);
        if (!Files.exists(file)) {
            throw new IllegalStateException(FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        JsonNode deputies = JSON.readTree(file.toFile()).path("deputes");
        if (deputies.size() < MIN_MEMBERS) {
            throw new IllegalStateException("Parlement de Wallonie : " + deputies.size() + " députés lus, au moins "
                    + MIN_MEMBERS + " attendus. Le format du fichier a probablement changé.");
        }
        int bureau = 0;
        for (JsonNode d : deputies) {
            if (write(d, dataset)) {
                bureau++;
            }
        }
        return new Report(deputies.size(), bureau);
    }

    /** @return vrai si le député a une fonction au bureau */
    private static boolean write(JsonNode d, FtmDatasetBuilder dataset) {
        String id = d.path("dep_id").asText();
        String firstName = text(d, "dep_prenom");
        String lastName = text(d, "dep_nom");
        String bureauFunction = text(d, "dep_fonction_bureau");

        // ID = identifiant du député au Parlement de Wallonie (« 1072 »)
        FtmEntity person = dataset.newEntity("Person", id);
        person.addValue("name", joinNonBlank(" ", firstName, lastName));
        person.addValue("firstName", firstName);
        person.addValue("lastName", lastName);
        person.addValue("gender", "F".equals(text(d, "dep_civ")) ? "female" : "M".equals(text(d, "dep_civ")) ? "male" : null);
        person.addValue("country", "be");
        person.addValue("position", POSITION);
        person.addValue("position", bureauFunction);
        person.addValue("sourceUrl", DEFAULT_URL);
        dataset.addEntity(person);

        String photo = text(d, "dep_photo");
        if (photo != null && photo.startsWith("https://")) {
            dataset.addMedia(person.getId(), photo, DEFAULT_URL, PHOTO_LICENCE);
        }

        occupancy(dataset, person, id, "member", POSITION, text(d, "dep_circons"), text(d, "dep_parti"));
        if (bureauFunction != null) {
            occupancy(dataset, person, id, bureauFunction, bureauFunction, null, null);
        }
        return bureauFunction != null;
    }

    private static void occupancy(FtmDatasetBuilder dataset, FtmEntity person, String id, String key, String title,
            String constituency, String group) {
        FtmEntity position = dataset.newEntity("Position", key);
        position.addValue("name", title);
        position.addValue("country", "be");
        position.addValue("subnationalArea", "Wallonie");
        position.addValue("organization", "Parlement de Wallonie");
        position.addValue("topics", "gov.state");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", id, key);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("constituency", constituency);
        occupancy.addValue("politicalGroup", group);
        occupancy.addValue("sourceUrl", DEFAULT_URL);
        dataset.addEntity(occupancy);
    }

    private static String text(JsonNode d, String field) {
        JsonNode v = d.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static byte[] get(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " : " + url);
        }
        return response.body();
    }
}
