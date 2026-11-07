package com.swiftcheck.aml.plugins.hupep.parliament;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <b>hu_pep_parliament</b> – députés de l'Assemblée nationale hongroise (Országgyűlés) en fonction ou dont le mandat s'est terminé
 * depuis moins de 12 mois, personnes politiquement exposées au sens de la loi hongroise sur la prévention du blanchiment de
 * capitaux (Pmt.).
 *
 * <p>Source officielle : le registre des représentants de l'Office électoral national ({@code vtr.valasztas.hu/kepviselo}), dont les
 * fichiers JSON publics sont lus directement (le site du Parlement, parlament.hu, est protégé par un CAPTCHA : non utilisé) :
 * <ul>
 *   <li>{@code Ciklusok.json} : les cycles électoraux ; le cycle « Országgyűlési » en cours est choisi par ses dates ;</li>
 *   <li>{@code <cycle>/config.json} : la version des données ;</li>
 *   <li>{@code <cycle>/<version>/AktivKepv.json} : les députés en fonction (nom, parti, circonscription ou liste, début de mandat) ;</li>
 *   <li>{@code <cycle>/<version>/MegszuntKepv.json} : les mandats terminés (date et motif : démission, décès…).</li>
 * </ul>
 * L'identifiant {@code szId} est celui de la personne. Le registre ne publie pas de naissance et ne donne pas les députés de la
 * législature précédente (2022-2026, terminée le 8 mai 2026) : seuls ceux qui ont quitté leur siège dans la législature en cours
 * figurent dans la liste des mandats terminés. Les noms sont écrits « Nom Prénom » : l'ordre inverse est ajouté en alias. Le
 * robots.txt n'interdit rien ; 1 seconde entre les appels, User-Agent générique.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"baseUrl": "https://vtr.valasztas.hu/kepviselo/data", "monthsAfterEnd": "12", "delayMs": "1000"}
 * }</pre>
 */
@Extension
public class HuPepParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "hu-pep-parliament";
    static final String TOPIC = "role.pep";
    static final String SEAT = "Député à l'Assemblée nationale (országgyűlési képviselő)";
    static final String DEFAULT_BASE_URL = "https://vtr.valasztas.hu/kepviselo/data";
    static final int DEFAULT_MONTHS_AFTER_END = 12;
    static final long DEFAULT_DELAY_MS = 1000;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Hongrie - registre des représentants)";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Un mandat lu dans le registre : {@code end == null} = en cours. */
    record Mandate(String id, String name, String party, String kind, String district, LocalDate start, LocalDate end, String endReason,
            boolean titled) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        LocalDate today = LocalDate.now();
        download(httpFetcher(delayMs(p)), baseUrl(p), today, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), today, monthsAfterEnd(p), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Télécharge les cycles, la version du cycle parlementaire en cours puis les députés en fonction et les mandats terminés. */
    static void download(Fetcher fetcher, String baseUrl, LocalDate today, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String cycles = fetcher.get(base + "/Ciklusok.json");
        Files.writeString(rawDir.resolve("cycles.json"), cycles, StandardCharsets.UTF_8);
        String cycle = currentCycle(cycles, today);
        String config = fetcher.get(base + "/" + cycle + "/config.json");
        Files.writeString(rawDir.resolve("config.json"), config, StandardCharsets.UTF_8);
        String version = MAPPER.readTree(config).path("version").asText();
        Files.writeString(rawDir.resolve("active.json"), fetcher.get(base + "/" + cycle + "/" + version + "/AktivKepv.json"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("ended.json"), fetcher.get(base + "/" + cycle + "/" + version + "/MegszuntKepv.json"), StandardCharsets.UTF_8);
    }

    /** Identifiant du cycle « Országgyűlési » dont les dates contiennent {@code today} (sinon le plus récemment commencé). */
    static String currentCycle(String cyclesJson, LocalDate today) throws IOException {
        String best = null;
        LocalDate bestStart = null;
        for (JsonNode c : MAPPER.readTree(cyclesJson).path("list")) {
            if (!"Országgyűlési".equals(c.path("tipus_nev").asText())) {
                continue;
            }
            LocalDate start = date(c.path("cikl_kezd").asText(null));
            if (start != null && !start.isAfter(today) && (bestStart == null || start.isAfter(bestStart))) {
                bestStart = start;
                best = c.path("cikl_id").asText();
            }
        }
        if (best == null) {
            throw new IllegalStateException("Aucun cycle « Országgyűlési » en cours dans Ciklusok.json : le format a changé.");
        }
        return best;
    }

    /** Lit les fichiers de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws IOException {
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        Path active = rawDir.resolve("active.json");
        Path ended = rawDir.resolve("ended.json");
        if (!Files.exists(active) || !Files.exists(ended)) {
            throw new IllegalStateException("active.json ou ended.json absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Mandate> mandates = new ArrayList<>(mandates(Files.readString(active, StandardCharsets.UTF_8), false));
        int activeCount = mandates.size();
        int endedRead = 0;
        int outside = 0;
        for (Mandate m : mandates(Files.readString(ended, StandardCharsets.UTF_8), true)) {
            endedRead++;
            if (m.end() != null && m.end().isBefore(cutoff)) {
                outside++;
                continue;
            }
            mandates.add(m);
        }
        if (activeCount == 0) {
            throw new IllegalStateException("Aucun député en fonction dans active.json : le format a peut-être changé.");
        }

        FtmEntity position = dataset.newEntity("Position", "orszaggyules");
        position.addValue("name", SEAT);
        position.addValue("country", "hu");
        position.addValue("organization", "Országgyűlés");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        for (Mandate m : mandates) {
            FtmEntity person = persons.get(m.id());
            if (person == null) {
                person = dataset.newEntity("Person", m.id());
                person.addValue("name", m.name());
                for (String alias : aliases(m.name())) {
                    person.addValue("alias", alias);
                }
                person.addValue("country", "hu");
                person.addValue("nationality", "hu");
                person.addValue("position", SEAT);
                person.addValue("sourceUrl", "https://vtr.valasztas.hu/kepviselo");
                dataset.addEntity(person);
                persons.put(m.id(), person);
            }
            FtmEntity occupancy = dataset.newEntity("Occupancy", m.id(), "orszaggyules", String.valueOf(m.start()));
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", m.start() == null ? null : m.start().toString());
            occupancy.addValue("endDate", m.end() == null ? null : m.end().toString());
            occupancy.addValue("status", m.end() == null ? "current" : "ended");
            List<String> parts = new ArrayList<>();
            if (m.party() != null) {
                parts.add("Parti : " + m.party());
            }
            if (m.kind() != null) {
                parts.add(m.kind());
            }
            if (m.district() != null) {
                parts.add(m.district());
            }
            if (m.endReason() != null) {
                parts.add("Fin de mandat : " + m.endReason());
            }
            occupancy.addValue("description", String.join(" ; ", parts));
            occupancy.addValue("sourceUrl", "https://vtr.valasztas.hu/kepviselo");
            dataset.addEntity(occupancy);
        }
        return String.format("%d députés en fonction, %d mandats terminés lus (%d antérieurs à la fenêtre de %d mois écartés), %d personnes gardées. "
                + "Source : Nemzeti Választási Iroda, vtr.valasztas.hu.", activeCount, endedRead, outside, monthsAfterEnd, persons.size());
    }

    /** Les mandats d'un fichier du registre. */
    static List<Mandate> mandates(String json, boolean ended) throws IOException {
        List<Mandate> out = new ArrayList<>();
        for (JsonNode n : MAPPER.readTree(json).path("list")) {
            String id = text(n, "szId");
            String name = text(n, "neve");
            if (id == null || name == null) {
                continue;
            }
            out.add(new Mandate(id, name, text(n, "jcs_nev"), text(n, "mdtip_megnev"),
                    text(n, "evk_nev") != null ? text(n, "evk_nev") : text(n, "megye_nev"),
                    date(text(n, "mandtol")), ended ? date(text(n, "mandig")) : null, ended ? text(n, "megszunok_megnev") : null,
                    "Dr.".equals(text(n, "dr_jelzo"))));
        }
        return out;
    }

    /**
     * Ordre « Prénom Nom » en alias d'un nom écrit « Nom Prénom » : premier mot déplacé à la fin, et dernier mot placé en tête
     * (pour les noms à plusieurs prénoms ou à nom composé) ; « Dr. » ignoré.
     */
    static Set<String> aliases(String name) {
        List<String> tokens = new ArrayList<>(List.of(name.split(" ")));
        tokens.removeIf(t -> t.equals("Dr.") || t.isBlank());
        Set<String> out = new LinkedHashSet<>();
        if (tokens.size() < 2) {
            return out;
        }
        out.add(String.join(" ", tokens.subList(1, tokens.size())) + " " + tokens.get(0));
        out.add(tokens.get(tokens.size() - 1) + " " + String.join(" ", tokens.subList(0, tokens.size() - 1)));
        out.remove(name);
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /** « 2026-04-12T00:00:00+02:00 » → 2026-04-12. */
    static LocalDate date(String v) {
        if (v == null || v.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(v.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    static int monthsAfterEnd(Map<String, String> p) {
        String v = p.get("monthsAfterEnd");
        if (v == null || v.isBlank()) {
            return DEFAULT_MONTHS_AFTER_END;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « monthsAfterEnd » invalide : « " + v + " » (nombre de mois attendu).");
        }
    }

    private static String baseUrl(Map<String, String> p) {
        String v = p.get("baseUrl");
        return v == null || v.isBlank() ? DEFAULT_BASE_URL : v.trim();
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        if (v == null || v.isBlank()) {
            return DEFAULT_DELAY_MS;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « delayMs » invalide : « " + v + " » (nombre de millisecondes attendu).");
        }
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
