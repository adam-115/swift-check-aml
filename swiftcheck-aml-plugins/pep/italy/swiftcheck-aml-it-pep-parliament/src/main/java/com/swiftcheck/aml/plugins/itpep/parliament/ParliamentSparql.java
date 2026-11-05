package com.swiftcheck.aml.plugins.itpep.parliament;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Accès aux points SPARQL officiels de la Chambre des députés ({@code dati.camera.it}) et du Sénat
 * ({@code dati.senato.it}). Les requêtes sont paginées (LIMIT/OFFSET sur un ordre stable) pour éviter les délais
 * d'attente de Virtuoso ; chaque page de réponse est déposée telle quelle dans {@code rawDir}.
 */
final class ParliamentSparql {

    private ParliamentSparql() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Accès réseau, remplaçable dans les tests. */
    interface Endpoint {
        /** Corps JSON (application/sparql-results+json) de la réponse. */
        String query(String endpointUrl, String sparql) throws IOException, InterruptedException;
    }

    /** Une ligne de résultat : nom de variable → valeur. */
    record Row(Map<String, String> values) {
        String get(String variable) {
            return values.get(variable);
        }
    }

    /**
     * Exécute {@code query} page par page. La requête doit se terminer par un {@code ORDER BY} stable et ne pas contenir
     * LIMIT ni OFFSET.
     *
     * @return le nombre de lignes lues
     */
    static int download(Endpoint endpoint, String url, String query, String filePrefix, int pageSize, long delayMs,
            Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        int total = 0;
        for (int page = 0; ; page++) {
            String body = endpoint.query(url, query + " LIMIT " + pageSize + " OFFSET " + (page * pageSize));
            JsonNode bindings = JSON.readTree(body).path("results").path("bindings");
            if (!bindings.isArray()) {
                throw new IllegalStateException("Réponse SPARQL inattendue de " + url + " (pas de results.bindings).");
            }
            Files.writeString(rawDir.resolve(String.format("%s-%03d.json", filePrefix, page)), body, StandardCharsets.UTF_8);
            total += bindings.size();
            if (bindings.size() < pageSize) {
                return total;
            }
            Thread.sleep(delayMs);
        }
    }

    /** Relit toutes les pages d'un préfixe dans l'ordre. */
    static List<Row> read(Path rawDir, String filePrefix) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(rawDir)) {
            files = s.filter(p -> p.getFileName().toString().startsWith(filePrefix + "-")
                    && p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        List<Row> rows = new ArrayList<>();
        for (Path file : files) {
            for (JsonNode binding : JSON.readTree(Files.readString(file, StandardCharsets.UTF_8))
                    .path("results").path("bindings")) {
                Map<String, String> values = new HashMap<>();
                binding.fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue().path("value").asText()));
                rows.add(new Row(values));
            }
        }
        return rows;
    }

    /** Client HTTP : User-Agent générique, nouvelles tentatives lentes sur 429/5xx, aucune protection contournée. */
    static Endpoint httpEndpoint(String userAgent) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return (url, sparql) -> {
            String full = url + (url.contains("?") ? "&" : "?") + "format="
                    + URLEncoder.encode("application/sparql-results+json", StandardCharsets.UTF_8) + "&query="
                    + URLEncoder.encode(sparql, StandardCharsets.UTF_8);
            IOException last = null;
            for (int attempt = 1; attempt <= 4; attempt++) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(full))
                        .timeout(Duration.ofSeconds(180)).header("User-Agent", userAgent)
                        .header("Accept", "application/sparql-results+json").GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = response.statusCode();
                if (code == 200) {
                    return response.body();
                }
                last = new IOException("HTTP " + code + " pour " + url);
                if (code < 500 && code != 429) {
                    break;
                }
                Thread.sleep(10_000L * attempt);
            }
            throw last;
        };
    }
}
