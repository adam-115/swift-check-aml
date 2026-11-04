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
import java.time.LocalDate;
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
    static final String DEFAULT_FORMER_URL = "https://www.parlement-wallonie.be/pwpages?p=composition_mandataires";
    static final String DEFAULT_DETAIL_URL = "https://www.parlement-wallonie.be/pwpages?p=composition_dep_det&id={id}&t=old";
    /** La liste des anciens est paginée (60 par page) : une page par fichier, « -1 », « -2 »… */
    static final String FORMER_PREFIX = "parlement-wallonie-anciens-";
    static final int MAX_FORMER_PAGES = 30;
    static final int MIN_FORMER = 100;
    static final String FORMER_DETAIL_PREFIX = "parlement-wallonie-ancien-";
    static final int DEFAULT_RETENTION_MONTHS = 12;
    static final String POSITION = "Membre du Parlement de Wallonie";
    static final String PHOTO_LICENCE = "© Parlement de Wallonie";
    /** En dessous, le fichier a changé de format : le Parlement compte 75 députés. */
    static final int MIN_MEMBERS = 60;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - Parlement de Wallonie)";
    private static final ObjectMapper JSON = new ObjectMapper();

    record Report(int members, int bureau, int former) {

        Report(int members, int bureau) {
            this(members, bureau, 0);
        }

        String message() {
            String m = String.format("%d membres du Parlement de Wallonie, dont %d au bureau.", members, bureau);
            return former == 0 ? m : m + String.format(" %d anciens députés dont le mandat a pris fin pendant la durée de rétention.", former);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.getOrDefault("url", DEFAULT_URL);
        Files.createDirectories(ctx.rawDir());
        Files.write(ctx.rawDir().resolve(FILE), get(url == null || url.isBlank() ? DEFAULT_URL : url.trim()));
        int retention = Integer.parseInt(p.getOrDefault("retentionMonths", String.valueOf(DEFAULT_RETENTION_MONTHS)).trim());
        String formerNote = "";
        try {
            downloadFormer(BePepWalloonParliamentParser::getHtml, p.getOrDefault("formerUrl", DEFAULT_FORMER_URL),
                    p.getOrDefault("detailUrl", DEFAULT_DETAIL_URL), LocalDate.now().minusMonths(retention),
                    Long.parseLong(p.getOrDefault("delayMs", "300")), ctx.rawDir());
        } catch (IOException | RuntimeException e) {
            formerNote = " Anciens députés non lus : " + e.getMessage();
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), dataset, LocalDate.now(), retention);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message() + formerNote);
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
        return convert(rawDir, dataset, LocalDate.now(), DEFAULT_RETENTION_MONTHS);
    }

    Report convert(Path rawDir, FtmDatasetBuilder dataset, LocalDate today, int retentionMonths) throws IOException {
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
        int former = convertFormer(rawDir, dataset, today, retentionMonths);
        return new Report(deputies.size(), bureau, former);
    }

    /** Lecture d'une page HTML ; remplacée dans les tests. */
    interface HtmlFetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Dépose la liste des anciens, puis la fiche de ceux dont une période finit après la date limite. */
    static void downloadFormer(HtmlFetcher fetcher, String listUrl, String detailUrl, LocalDate cutoff, long delayMs,
            Path rawDir) throws IOException, InterruptedException {
        List<WalloonFormer.Entry> entries = new ArrayList<>();
        for (int page = 1; page <= MAX_FORMER_PAGES; page++) {
            String html = fetcher.get(page == 1 ? listUrl : listUrl + "&pg-nb=" + page);
            List<WalloonFormer.Entry> onPage = WalloonFormer.entries(html);
            if (onPage.isEmpty()) {
                break;
            }
            Files.writeString(rawDir.resolve(FORMER_PREFIX + page + ".html"), html);
            entries.addAll(onPage);
            Thread.sleep(delayMs);
        }
        if (entries.size() < MIN_FORMER) {
            throw new IOException("liste des anciens illisible (" + entries.size() + " entrées)");
        }
        for (WalloonFormer.Entry e : WalloonFormer.candidates(entries, cutoff)) {
            Files.writeString(rawDir.resolve(FORMER_DETAIL_PREFIX + e.id() + ".html"),
                    fetcher.get(detailUrl.replace("{id}", e.id())));
            Thread.sleep(delayMs);
        }
    }

    private static int convertFormer(Path rawDir, FtmDatasetBuilder dataset, LocalDate today, int retentionMonths)
            throws IOException {
        List<WalloonFormer.Entry> entries = new ArrayList<>();
        for (int page = 1; Files.exists(rawDir.resolve(FORMER_PREFIX + page + ".html")); page++) {
            entries.addAll(WalloonFormer.entries(Files.readString(rawDir.resolve(FORMER_PREFIX + page + ".html"))));
        }
        if (entries.isEmpty()) {
            return 0;
        }
        LocalDate cutoff = today.minusMonths(retentionMonths);
        int kept = 0;
        for (WalloonFormer.Entry e : WalloonFormer.candidates(entries, cutoff)) {
            Path detail = rawDir.resolve(FORMER_DETAIL_PREFIX + e.id() + ".html");
            if (!Files.exists(detail)) {
                continue;
            }
            WalloonFormer.Mandate m = WalloonFormer.mandate(e, Files.readString(detail));
            if (m == null || m.end().isBefore(cutoff) || m.end().isAfter(today)) {
                continue;
            }
            writeFormer(m, dataset);
            kept++;
        }
        return kept;
    }

    private static void writeFormer(WalloonFormer.Mandate m, FtmDatasetBuilder dataset) {
        WalloonFormer.Entry e = m.entry();
        String page = DEFAULT_DETAIL_URL.replace("{id}", e.id());
        FtmEntity person = dataset.newEntity("Person", e.id());
        person.addValue("name", joinNonBlank(" ", e.firstName(), e.lastName()));
        person.addValue("firstName", e.firstName());
        person.addValue("lastName", e.lastName());
        person.addValue("country", "be");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", page);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "member");
        position.addValue("name", POSITION);
        position.addValue("country", "be");
        position.addValue("subnationalArea", "Wallonie");
        position.addValue("organization", "Parlement de Wallonie");
        position.addValue("topics", "gov.state");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", e.id(), "member");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "ended");
        occupancy.addValue("startDate", m.start().toString());
        occupancy.addValue("endDate", m.end().toString());
        occupancy.addValue("constituency", e.area());
        occupancy.addValue("politicalGroup", e.party());
        occupancy.addValue("sourceUrl", page);
        dataset.addEntity(occupancy);
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

    private static String getHtml(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", USER_AGENT).header("Accept", "text/html").GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " : " + url);
        }
        return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
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
