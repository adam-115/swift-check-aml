package com.swiftcheck.aml.parsertest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swiftcheck.aml.plugin.SourceRecord;

/**
 * Les deux questions posées à Yente par le test d'un parser :
 * <ul>
 *   <li>« retrouve-t-on cet élément par son nom ? » : {@code POST /match/{dataset}}, plusieurs noms par requête,
 *       avec le même seuil que le criblage de l'application ;</li>
 *   <li>« Yente connaît-il cet ID ? » : {@code GET /entities/{id}}.</li>
 * </ul>
 * Sans état : peut servir à plusieurs threads en même temps.
 */
@Component
public class YenteLookup {

    /** Même seuil et même nombre de résultats que le criblage (AmlVerificationService). */
    static final String MATCH_PARAMS = "?threshold=0.5&limit=10";

    /** Meilleur résultat renvoyé par Yente pour un nom, affiché quand l'élément attendu n'est pas trouvé. */
    public record BestResult(String id, String caption, double score) {
        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "meilleur résultat : %s « %s » (score %.2f)", id, caption, score);
        }
    }

    /** Résultat d'une recherche par nom : l'élément attendu figure-t-il parmi les résultats ? */
    public record MatchOutcome(boolean found, BestResult best) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Cherche chaque nom dans le dataset et indique, pour chaque élément, si son ID est parmi les résultats.
     *
     * @return résultat par ID d'entité attendu
     * @throws IOException si Yente ne répond pas ou répond autre chose que 200
     */
    public Map<String, MatchOutcome> match(String yenteUrl, String dataset, List<SourceRecord> records)
            throws IOException, InterruptedException {
        ObjectNode queries = json.createObjectNode();
        for (int i = 0; i < records.size(); i++) {
            SourceRecord record = records.get(i);
            ObjectNode query = queries.putObject("q" + i);
            query.put("schema", record.schema());
            query.putObject("properties").putArray("name").add(record.name());
        }
        ObjectNode body = json.createObjectNode();
        body.set("queries", queries);

        HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(yenteUrl) + "/match/" + dataset + MATCH_PARAMS))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Yente /match a répondu HTTP " + response.statusCode());
        }

        JsonNode responses = json.readTree(response.body()).path("responses");
        Map<String, MatchOutcome> outcomes = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            SourceRecord record = records.get(i);
            JsonNode results = responses.path("q" + i).path("results");
            boolean found = false;
            BestResult best = null;
            for (JsonNode result : results) {
                String id = result.path("id").asText();
                if (best == null) {
                    best = new BestResult(id, result.path("caption").asText(), result.path("score").asDouble());
                }
                // L'ID attendu, ou une entité fusionnée qui le référence (Yente garde les anciens ID dans "referents").
                if (id.equals(record.entityId()) || contains(result.path("referents"), record.entityId())) {
                    found = true;
                    break;
                }
            }
            outcomes.put(record.entityId(), new MatchOutcome(found, best));
        }
        return outcomes;
    }

    /**
     * Code HTTP de {@code GET /entities/{id}} : 200 si Yente connaît l'entité, 404 sinon.
     *
     * @throws IOException si Yente est injoignable
     */
    public int entityStatus(String yenteUrl, String entityId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(yenteUrl) + "/entities/"
                        + URLEncoder.encode(entityId, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("URL de Yente non configurée (écran Configuration).");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
