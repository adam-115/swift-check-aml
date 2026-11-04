package com.swiftcheck.aml.plugins.bepep.flemishparliament;

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
 * <b>be_pep_flemish_parliament</b> – membres du Parlement flamand (catégorie BE-b ; loi du 18 septembre 2017,
 * art. 4, 28°, b), Annexe IV, 2°).
 *
 * <p>Source : API open data du Parlement flamand (Gratis Open Data Licentie Vlaanderen), documentée sur
 * {@code ws.vlpar.be/e/opendata/api}. Type {@code PLUGIN_MANAGED} : le plugin lit la liste des membres en fonction
 * ({@code vv/huidige}), puis la fiche de chacun ({@code vv/{id}}), en JSON, et les dépose dans {@code rawDir}. La
 * fiche donne la date et le lieu de naissance, le genre, le groupe ({@code huidigefractie}), la circonscription et
 * les mandats ({@code mandaat-vlaams-parlement}, avec {@code datumvan} et {@code datumtot}). Paramètres :
 * <pre>{@code
 * {"listUrl": "https://ws.vlpar.be/e/opendata/vv/huidige", "detailUrl": "https://ws.vlpar.be/e/opendata/vv/{id}",
 *  "delayMs": "200"}
 * }</pre>
 * Les coordonnées (adresse du bureau, e-mail, site) ne sont pas reprises. Le portrait officiel est déclaré comme photo.
 */
@Extension
public class BePepFlemishParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "be-pep-flemish-parliament";
    static final String TOPIC = "role.pep";

    static final String DEFAULT_LIST_URL = "https://ws.vlpar.be/e/opendata/vv/huidige";
    static final String DEFAULT_DETAIL_URL = "https://ws.vlpar.be/e/opendata/vv/{id}";
    static final String DEFAULT_FORMER_URL = "https://ws.vlpar.be/e/opendata/vv/gewezen";
    static final int DEFAULT_RETENTION_MONTHS = 12;
    static final String LIST_FILE = "vlaams-parlement-leden.json";
    static final String FORMER_LIST_FILE = "vlaams-parlement-gewezen.json";
    static final String DETAIL_PREFIX = "vlaams-parlement-lid-";
    static final String POSITION = "Membre du Parlement flamand";
    static final String PHOTO_LICENCE = "© Vlaams Parlement";
    /** Fiche du membre dans l'API (vlaamsparlement.be refuse les clients automatiques : HTTP 403). */
    static final String PAGE_URL = "https://ws.vlpar.be/e/opendata/vv/";
    /** En dessous, la liste a changé de format : le Parlement compte 124 membres. */
    static final int MIN_MEMBERS = 100;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - Parlement flamand)";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    record Report(int members, int withBirthDate, List<String> withoutDetail, int former) {

        Report(int members, int withBirthDate, List<String> withoutDetail) {
            this(members, withBirthDate, withoutDetail, 0);
        }

        String message() {
            String m = String.format("%d membres du Parlement flamand, dont %d avec leur date de naissance.", members,
                    withBirthDate);
            if (former > 0) {
                m += String.format(" %d anciens membres dont le mandat a pris fin pendant la durée de rétention.", former);
            }
            return withoutDetail.isEmpty() ? m
                    : m + String.format(" %d fiches absentes : %s.", withoutDetail.size(), String.join(", ", withoutDetail));
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int retention = Integer.parseInt(param(p, "retentionMonths", String.valueOf(DEFAULT_RETENTION_MONTHS)));
        download(httpFetcher(), param(p, "listUrl", DEFAULT_LIST_URL), param(p, "formerUrl", DEFAULT_FORMER_URL),
                param(p, "detailUrl", DEFAULT_DETAIL_URL), Long.parseLong(param(p, "delayMs", "200")), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), dataset, LocalDate.now(), retention);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
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

    /** Dépose la liste puis la fiche de chaque membre dans {@code rawDir}. */
    static void download(Fetcher fetcher, String listUrl, String detailUrl, long delayMs, Path rawDir)
            throws IOException, InterruptedException {
        download(fetcher, listUrl, null, detailUrl, delayMs, rawDir);
    }

    /**
     * Dépose la liste des membres en fonction, puis, si {@code formerUrl} est renseigné, celle des anciens membres
     * ({@code vv/gewezen}), puis la fiche de chacun. Les anciens sont lus pour garder ceux dont le mandat a pris
     * fin pendant la durée de rétention.
     */
    static void download(Fetcher fetcher, String listUrl, String formerUrl, String detailUrl, long delayMs, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        byte[] list = fetcher.get(listUrl);
        Files.write(rawDir.resolve(LIST_FILE), list);
        List<JsonNode> all = new ArrayList<>(members(JSON.readTree(list)));
        if (formerUrl != null && !formerUrl.isBlank()) {
            byte[] former = fetcher.get(formerUrl);
            Files.write(rawDir.resolve(FORMER_LIST_FILE), former);
            all.addAll(members(JSON.readTree(former)));
        }
        for (JsonNode member : all) {
            String id = member.path("id").asText();
            try {
                Files.write(rawDir.resolve(DETAIL_PREFIX + id + ".json"), fetcher.get(detailUrl.replace("{id}", id)));
            } catch (IOException e) {
                // Fiche absente : le membre est publié sans sa date de naissance, et cité dans le message
            }
            Thread.sleep(delayMs);
        }
    }

    /** Lit les fichiers de {@code rawDir} et écrit les membres. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        return convert(rawDir, dataset, LocalDate.now(), DEFAULT_RETENTION_MONTHS);
    }

    Report convert(Path rawDir, FtmDatasetBuilder dataset, LocalDate today, int retentionMonths) throws IOException {
        Path listFile = rawDir.resolve(LIST_FILE);
        if (!Files.exists(listFile)) {
            throw new IllegalStateException(LIST_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<JsonNode> members = members(JSON.readTree(listFile.toFile()));
        if (members.size() < MIN_MEMBERS) {
            throw new IllegalStateException("Parlement flamand : " + members.size() + " membres lus, au moins "
                    + MIN_MEMBERS + " attendus. Le format de l'API a probablement changé.");
        }
        int withBirth = 0;
        List<String> withoutDetail = new ArrayList<>();
        for (JsonNode m : members) {
            Path file = rawDir.resolve(DETAIL_PREFIX + m.path("id").asText() + ".json");
            JsonNode detail = Files.exists(file) ? JSON.readTree(file.toFile()) : null;
            if (detail != null && detail.has("volksvertegenwoordiger")) {
                detail = detail.path("volksvertegenwoordiger");
            }
            String birth = detail == null ? null : date(detail.path("geboortedatum").asText(null));
            if (birth == null) {
                withoutDetail.add(joinNonBlank(" ", m.path("voornaam").asText(), m.path("naam").asText()));
            } else {
                withBirth++;
            }
            write(m, detail, birth, dataset);
        }
        int former = 0;
        Path formerFile = rawDir.resolve(FORMER_LIST_FILE);
        if (Files.exists(formerFile)) {
            LocalDate cutoff = today.minusMonths(retentionMonths);
            for (JsonNode m : members(JSON.readTree(formerFile.toFile()))) {
                Path file = rawDir.resolve(DETAIL_PREFIX + m.path("id").asText() + ".json");
                if (!Files.exists(file)) {
                    continue;
                }
                JsonNode detail = JSON.readTree(file.toFile());
                if (detail.has("volksvertegenwoordiger")) {
                    detail = detail.path("volksvertegenwoordiger");
                }
                String end = lastMandateEnd(detail);
                if (end == null || LocalDate.parse(end).isBefore(cutoff) || LocalDate.parse(end).isAfter(today)) {
                    continue;
                }
                write(m, detail, date(detail.path("geboortedatum").asText(null)), dataset, end);
                former++;
            }
        }
        return new Report(members.size(), withBirth, withoutDetail, former);
    }

    /** Fin du dernier mandat de député (hors titre honoraire) : la plus récente {@code datumtot}, ou null. */
    static String lastMandateEnd(JsonNode detail) {
        String end = null;
        for (JsonNode mandate : detail.path("mandaat-vlaams-parlement")) {
            if (isHonorary(mandate)) {
                continue;
            }
            String to = date(mandate.path("datumtot").asText(null));
            if (to == null) {
                return null;
            }
            if (end == null || to.compareTo(end) > 0) {
                end = to;
            }
        }
        return end;
    }

    private static boolean isHonorary(JsonNode mandate) {
        return mandate.path("mandaat").asText("").toLowerCase().startsWith("ere-");
    }

    /** Membres de la liste : {@code {"items": [{"volksvertegenwoordiger": {…}}]}}. */
    static List<JsonNode> members(JsonNode root) {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            JsonNode m = item.has("volksvertegenwoordiger") ? item.path("volksvertegenwoordiger") : item;
            if (!m.path("id").asText().isEmpty()) {
                list.add(m);
            }
        }
        return list;
    }

    private static void write(JsonNode m, JsonNode detail, String birth, FtmDatasetBuilder dataset) {
        write(m, detail, birth, dataset, null);
    }

    private static void write(JsonNode m, JsonNode detail, String birth, FtmDatasetBuilder dataset, String endDate) {
        String id = m.path("id").asText();
        String firstName = m.path("voornaam").asText(null);
        String lastName = m.path("naam").asText(null);
        String pageUrl = PAGE_URL + id;

        // ID = identifiant du Parlement flamand (« 4383 »), stable d'une législature à l'autre
        FtmEntity person = dataset.newEntity("Person", id);
        person.addValue("name", joinNonBlank(" ", firstName, lastName));
        person.addValue("firstName", firstName);
        person.addValue("lastName", lastName);
        person.addValue("country", "be");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", pageUrl);
        if (detail != null) {
            person.addValue("birthDate", birth);
            person.addValue("birthPlace", detail.path("geboorteplaats").asText(null));
            person.addValue("gender", gender(detail.path("geslacht").asText("")));
        }
        dataset.addEntity(person);

        String photo = m.path("fotowebpath").asText("");
        if (photo.startsWith("https://")) {
            dataset.addMedia(person.getId(), photo, pageUrl, PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", "member");
        position.addValue("name", POSITION);
        position.addValue("country", "be");
        position.addValue("subnationalArea", "Flandre");
        position.addValue("organization", "Parlement flamand");
        position.addValue("topics", "gov.state");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        String start = detail == null ? null : (endDate == null ? currentMandateStart(detail) : endedMandateStart(detail, endDate));
        FtmEntity occupancy = dataset.newEntity("Occupancy", id, start);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", endDate == null ? "current" : "ended");
        occupancy.addValue("startDate", start);
        occupancy.addValue("endDate", endDate);
        occupancy.addValue("constituency", m.path("kieskring").asText("").replaceFirst("^Kieskring\\s+", ""));
        occupancy.addValue("politicalGroup", m.path("fractie").path("naam").asText(null));
        occupancy.addValue("sourceUrl", pageUrl);
        dataset.addEntity(occupancy);
    }

    /** Début du mandat en cours : le mandat sans {@code datumtot} le plus récent. */
    static String currentMandateStart(JsonNode detail) {
        String start = null;
        for (JsonNode mandate : detail.path("mandaat-vlaams-parlement")) {
            if (!isHonorary(mandate) && (mandate.path("datumtot").isMissingNode() || mandate.path("datumtot").isNull())) {
                String from = date(mandate.path("datumvan").asText(null));
                if (from != null && (start == null || from.compareTo(start) > 0)) {
                    start = from;
                }
            }
        }
        return start;
    }

    /** Début du mandat terminé à la date donnée. */
    static String endedMandateStart(JsonNode detail, String endDate) {
        for (JsonNode mandate : detail.path("mandaat-vlaams-parlement")) {
            if (!isHonorary(mandate) && endDate.equals(date(mandate.path("datumtot").asText(null)))) {
                return date(mandate.path("datumvan").asText(null));
            }
        }
        return null;
    }

    /** « 1985-03-04T00:00:00+0100 » → « 1985-03-04 ». */
    static String date(String raw) {
        return raw == null || raw.length() < 10 ? null : raw.substring(0, 10);
    }

    /** « M » (man) → male, « V » (vrouw) → female. */
    static String gender(String raw) {
        return switch (raw.trim().toUpperCase()) {
            case "M" -> "male";
            case "V", "F" -> "female";
            default -> null;
        };
    }

    private static String param(Map<String, String> p, String key, String fallback) {
        String value = p.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                    .header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
