package com.swiftcheck.aml.plugins.espep.boe;

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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * API de données ouvertes du BOE : un sommaire par jour ({@code /datosabiertos/api/boe/sumario/AAAAMMJJ}, JSON).
 *
 * <p>Le plugin garde dans {@code rawDir} un fichier par mois, {@code sumario-AAAA-MM.json}, avec les articles des
 * sections demandées (identifiant, section, département, titre) : c'est la preuve des données utilisées, et les jours
 * déjà lus ne sont plus demandés. Un jour sans BOE (404) est noté {@code NO_BOE}. Les textes complets ne sont
 * téléchargés que pour les décrets qui nomment plusieurs personnes à la fois ({@code doc-ID.xml}).
 */
final class BoeApi {

    private static final Logger log = LoggerFactory.getLogger(BoeApi.class);

    static final String DEFAULT_SUMMARY_URL = "https://www.boe.es/datosabiertos/api/boe/sumario/";
    static final String DEFAULT_DOCUMENT_URL = "https://www.boe.es/diario_boe/xml.php?id=";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Espagne - BOE)";
    static final String SUMMARY_PREFIX = "sumario-";
    static final String DOCUMENT_PREFIX = "doc-";

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Après autant d'échecs de suite (hors 404), on arrête sans insister. */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** Un article du sommaire. */
    record Item(String id, String section, String department, String title) {
    }

    /** Réponse HTTP, remplacée dans les tests. */
    record Response(int status, byte[] body) {
    }

    interface Fetcher {
        Response get(String url, String accept) throws IOException, InterruptedException;
    }

    /** Bilan du téléchargement. */
    record Download(int fetched, int cached, int noBoe, List<String> failedDays) {
    }

    private BoeApi() {
    }

    static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return (url, accept) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", USER_AGENT).header("Accept", accept).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.body());
        };
    }

    /**
     * Télécharge les sommaires de {@code from} à {@code to} (inclus) qui ne sont pas déjà dans {@code rawDir}. Les
     * deux derniers jours sont toujours relus : le sommaire du jour peut paraître après notre passage.
     */
    static Download download(Fetcher fetcher, String summaryUrl, LocalDate from, LocalDate to, Set<String> sections,
            long delayMillis, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        int fetched = 0;
        int cached = 0;
        int noBoe = 0;
        int consecutiveFailures = 0;
        List<String> failed = new ArrayList<>();
        LocalDate recent = to.minusDays(1);

        ObjectNode month = null;
        YearMonth monthKey = null;
        boolean dirty = false;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            YearMonth ym = YearMonth.from(day);
            if (!ym.equals(monthKey)) {
                if (month != null && dirty) {
                    write(rawDir, monthKey, month);
                }
                monthKey = ym;
                month = readMonth(rawDir, ym);
                dirty = false;
            }
            String key = DAY.format(day);
            ObjectNode days = (ObjectNode) month.get("days");
            if (days.has(key) && day.isBefore(recent)) {
                cached++;
                continue;
            }
            Response r;
            try {
                Thread.sleep(delayMillis);
                r = fetcher.get(summaryUrl + key, "application/json");
            } catch (IOException e) {
                r = new Response(-1, new byte[0]);
                log.warn("BOE {} : {}", key, e.getMessage());
            }
            if (r.status() == 200) {
                days.set(key, day(r.body(), sections));
                fetched++;
                consecutiveFailures = 0;
                dirty = true;
            } else if (r.status() == 404) {
                days.set(key, JSON.createObjectNode().put("status", "NO_BOE"));
                noBoe++;
                consecutiveFailures = 0;
                dirty = true;
            } else {
                failed.add(key + " (HTTP " + r.status() + ")");
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("BOE : {} échecs de suite, arrêt du téléchargement au {}", consecutiveFailures, key);
                    break;
                }
            }
        }
        if (month != null && dirty) {
            write(rawDir, monthKey, month);
        }
        return new Download(fetched, cached, noBoe, failed);
    }

    /** Les articles des sections demandées dans la réponse de l'API. */
    static ObjectNode day(byte[] body, Set<String> sections) throws IOException {
        JsonNode root = JSON.readTree(body);
        ArrayNode items = JSON.createArrayNode();
        for (JsonNode diary : each(root.path("data").path("sumario").path("diario"))) {
            for (JsonNode section : each(diary.path("seccion"))) {
                String code = section.path("codigo").asText();
                if (!sections.contains(code)) {
                    continue;
                }
                for (JsonNode department : each(section.path("departamento"))) {
                    String name = department.path("nombre").asText();
                    collect(department.path("item"), code, name, items);
                    for (JsonNode epigraph : each(department.path("epigrafe"))) {
                        collect(epigraph.path("item"), code, name, items);
                    }
                }
            }
        }
        ObjectNode day = JSON.createObjectNode().put("status", "OK");
        day.set("items", items);
        return day;
    }

    private static void collect(JsonNode node, String section, String department, ArrayNode out) {
        for (JsonNode item : each(node)) {
            ObjectNode o = JSON.createObjectNode();
            o.put("id", item.path("identificador").asText());
            o.put("section", section);
            o.put("department", department);
            o.put("title", item.path("titulo").asText());
            out.add(o);
        }
    }

    /** Un nœud JSON qui peut être un objet seul ou un tableau, comme dans les réponses du BOE. */
    private static Iterable<JsonNode> each(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            return node;
        }
        return List.of(node);
    }

    private static ObjectNode readMonth(Path rawDir, YearMonth ym) throws IOException {
        Path file = rawDir.resolve(monthFile(ym));
        if (Files.isRegularFile(file)) {
            JsonNode node = JSON.readTree(file.toFile());
            if (node instanceof ObjectNode o && o.get("days") instanceof ObjectNode) {
                return o;
            }
        }
        ObjectNode month = JSON.createObjectNode();
        month.put("month", ym.toString());
        month.set("days", JSON.createObjectNode());
        return month;
    }

    private static void write(Path rawDir, YearMonth ym, ObjectNode month) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(rawDir.resolve(monthFile(ym)).toFile(), month);
    }

    static String monthFile(YearMonth ym) {
        return SUMMARY_PREFIX + ym + ".json";
    }

    /** Tous les articles enregistrés, par jour de publication. */
    static Map<LocalDate, List<Item>> read(Path rawDir) throws IOException {
        Map<LocalDate, List<Item>> byDay = new TreeMap<>();
        List<Path> files;
        try (var stream = Files.list(rawDir)) {
            files = stream.filter(p -> p.getFileName().toString().startsWith(SUMMARY_PREFIX)
                    && p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            JsonNode days = JSON.readTree(file.toFile()).path("days");
            Iterator<Map.Entry<String, JsonNode>> it = days.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!"OK".equals(e.getValue().path("status").asText())) {
                    continue;
                }
                List<Item> items = new ArrayList<>();
                for (JsonNode i : e.getValue().path("items")) {
                    items.add(new Item(i.path("id").asText(), i.path("section").asText(),
                            i.path("department").asText(), i.path("title").asText()));
                }
                byDay.put(LocalDate.parse(e.getKey(), DAY), items);
            }
        }
        return byDay;
    }

    /** Texte complet d'un article, enregistré dans {@code rawDir} ({@code doc-ID.xml}) ; {@code null} si illisible. */
    static String document(Fetcher fetcher, String documentUrl, String id, long delayMillis, Path rawDir)
            throws IOException, InterruptedException {
        Path file = rawDir.resolve(DOCUMENT_PREFIX + id + ".xml");
        if (Files.isRegularFile(file)) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        Thread.sleep(delayMillis);
        Response r = fetcher.get(documentUrl + id, "application/xml");
        if (r.status() != 200) {
            log.warn("BOE {} : texte non lu (HTTP {})", id, r.status());
            return null;
        }
        Files.write(file, r.body());
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    /** Pour les journaux et les tests. */
    static Map<String, Integer> countBySection(Map<LocalDate, List<Item>> byDay) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        byDay.values().forEach(items -> items.forEach(i -> counts.merge(i.section(), 1, Integer::sum)));
        return counts;
    }
}
