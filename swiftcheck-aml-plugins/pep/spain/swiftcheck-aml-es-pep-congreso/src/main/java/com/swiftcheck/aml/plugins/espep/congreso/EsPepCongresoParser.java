package com.swiftcheck.aml.plugins.espep.congreso;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>es_pep_congreso</b> – députés du Congreso de los Diputados, en fonction et sortis depuis moins de 24 mois
 * (bloc ES-b ; loi 10/2010, art. 14.2 ; JO C/2023/724).
 *
 * <p>Source : open data du Congreso ({@code congreso.es/es/opendata/diputados}). La page publie deux fichiers JSON
 * dont le nom change à chaque mise à jour ({@code DiputadosActivos__AAAAMMJJhhmmss.json} et
 * {@code DiputadosDeBaja__…json}) : le plugin lit la page, trouve les liens et enregistre les deux fichiers dans
 * {@code rawDir}. Les fiches ne donnent ni date de naissance ni identifiant : la personne est identifiée par son nom
 * (voir {@link #personKey}). La biographie, qui peut citer la situation familiale, n'est pas reprise. Type
 * {@code PLUGIN_MANAGED}. Paramètres (facultatifs) :
 * <pre>{@code
 * {"pageUrl": "https://www.congreso.es/es/opendata/diputados", "monthsAfterEnd": "24"}
 * }</pre>
 */
@Extension
public class EsPepCongresoParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepCongresoParser.class);

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "es-pep-congreso";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_PAGE_URL = "https://www.congreso.es/es/opendata/diputados";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Espagne - Congreso)";
    static final int DEFAULT_MONTHS_AFTER_END = 24;

    static final String ACTIVE_FILE = "DiputadosActivos.json";
    static final String FORMER_FILE = "DiputadosDeBaja.json";
    private static final String POSITION = "Diputado al Congreso de los Diputados";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Un député tel que publié ; {@code end} {@code null} = en fonction. */
    record Deputy(String name, String firstName, String lastName, String party, String group, String constituency,
            LocalDate start, LocalDate end) {
    }

    record Report(int current, int former, int tooOld, int sameDay, LocalDate sameDayDate) {

        String message() {
            String m = String.format("%d députés en fonction, %d sortis depuis moins de 24 mois (%d sortis avant, écartés).",
                    current, former, tooOld);
            return sameDay >= 50 ? m + String.format(" %d départs le même jour (%s) : fin de législature ?", sameDay,
                    sameDayDate) : m;
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(p.getOrDefault("pageUrl", DEFAULT_PAGE_URL).trim(), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Lit la page, suit les deux liens JSON et les enregistre sous un nom fixe dans {@code rawDir}. */
    static void download(String pageUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        String page = new String(get(client, pageUrl), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("opendata-diputados.html"), page, StandardCharsets.UTF_8);
        Files.write(rawDir.resolve(ACTIVE_FILE), get(client, link(page, pageUrl, "DiputadosActivos")));
        Files.write(rawDir.resolve(FORMER_FILE), get(client, link(page, pageUrl, "DiputadosDeBaja")));
    }

    /** Adresse du fichier JSON dont le nom commence par {@code prefix}, dans la page. */
    static String link(String page, String pageUrl, String prefix) {
        Matcher m = Pattern.compile("href=\"([^\"]*/" + prefix + "__\\d+\\.json)\"").matcher(page);
        if (!m.find()) {
            throw new IllegalStateException("Lien " + prefix + "__….json introuvable sur " + pageUrl
                    + " : la page de l'open data a probablement changé.");
        }
        return URI.create(pageUrl).resolve(m.group(1)).toString();
    }

    private static byte[] get(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " pour " + url);
        }
        return response.body();
    }

    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws IOException {
        List<Deputy> active = read(rawDir.resolve(ACTIVE_FILE));
        List<Deputy> former = read(rawDir.resolve(FORMER_FILE));
        if (active.isEmpty()) {
            throw new IllegalStateException("Aucun député en fonction dans " + ACTIVE_FILE + " : réponse vide ou "
                    + "format changé.");
        }
        LocalDate oldestEnd = today.minusMonths(monthsAfterEnd);
        int keptFormer = 0;
        int tooOld = 0;
        Map<LocalDate, Integer> byEnd = new java.util.HashMap<>();
        List<Deputy> all = new ArrayList<>(active);
        for (Deputy d : former) {
            byEnd.merge(d.end(), 1, Integer::sum);
            if (d.end() != null && d.end().isBefore(oldestEnd)) {
                tooOld++;
            } else {
                keptFormer++;
                all.add(d);
            }
        }
        Map<String, FtmEntity> persons = new java.util.LinkedHashMap<>();
        for (Deputy d : all) {
            FtmEntity person = persons.computeIfAbsent(personKey(d.name()), k -> newPerson(d, dataset));
            boolean ended = d.end() != null && d.end().isBefore(today);
            person.addValue("position", (ended ? "Ancien : " : "") + POSITION);
            addMandate(d, ended, person, dataset);
        }
        persons.values().forEach(dataset::addEntity);
        var mostFrequent = byEnd.entrySet().stream().max(Map.Entry.comparingByValue());
        Report report = new Report(active.size(), keptFormer, tooOld, mostFrequent.map(Map.Entry::getValue).orElse(0),
                mostFrequent.map(Map.Entry::getKey).orElse(null));
        log.info(report.message());
        return report;
    }

    static List<Deputy> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(file.getFileName() + " absent de " + file.getParent()
                    + " : le téléchargement n'a pas eu lieu.");
        }
        List<Deputy> list = new ArrayList<>();
        for (JsonNode n : JSON.readTree(file.toFile())) {
            String full = text(n, "NOMBRE");
            if (full == null) {
                continue;
            }
            String[] parts = full.split(",", 2);
            String last = parts[0].trim();
            String first = parts.length > 1 ? parts[1].trim() : null;
            String name = first == null ? last : first + " " + last;
            list.add(new Deputy(name, first, parts.length > 1 ? last : null, text(n, "FORMACIONELECTORAL"),
                    text(n, "GRUPOPARLAMENTARIO"), text(n, "CIRCUNSCRIPCION"), date(text(n, "FECHAALTA")),
                    date(text(n, "FECHABAJA"))));
        }
        return list;
    }

    private static FtmEntity newPerson(Deputy d, FtmDatasetBuilder dataset) {
        // ID = nom : l'open data ne donne ni identifiant ni date de naissance
        FtmEntity person = dataset.newEntity("Person", personKey(d.name()));
        person.addValue("name", d.name());
        person.addValue("firstName", d.firstName());
        person.addValue("lastName", d.lastName());
        person.addValue("country", "es");
        person.addValue("nationality", "es");
        person.addValue("political", d.party());
        person.addValue("sourceUrl", DEFAULT_PAGE_URL);
        return person;
    }

    private static void addMandate(Deputy d, boolean ended, FtmEntity person, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", "diputado");
        position.addValue("name", POSITION);
        position.addValue("country", "es");
        position.addValue("organization", "Congreso de los Diputados");
        position.addValue("topics", "gov.legislative");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", personKey(d.name()), d.start() == null ? "" : d.start().toString());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", d.start() == null ? null : d.start().toString());
        occupancy.addValue("endDate", d.end() == null ? null : d.end().toString());
        occupancy.addValue("status", ended ? "ended" : "current");
        occupancy.addValue("constituency", d.constituency());
        occupancy.addValue("description", d.group());
        occupancy.addValue("sourceUrl", DEFAULT_PAGE_URL);
        dataset.addEntity(occupancy);
    }

    /** Clé de personne : nom sans accents ni majuscules. */
    static String personKey(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        return v.asText().trim();
    }

    private static LocalDate date(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s, DAY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int monthsAfterEnd(Map<String, String> p) {
        try {
            return Integer.parseInt(p.getOrDefault("monthsAfterEnd", "").trim());
        } catch (NumberFormatException e) {
            return DEFAULT_MONTHS_AFTER_END;
        }
    }
}
