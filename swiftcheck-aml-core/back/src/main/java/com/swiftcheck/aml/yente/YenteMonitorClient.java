package com.swiftcheck.aml.yente;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.yente.YenteModels.AuditEvent;
import com.swiftcheck.aml.yente.YenteModels.CatalogEntry;
import com.swiftcheck.aml.yente.YenteModels.ClusterInfo;
import com.swiftcheck.aml.yente.YenteModels.Health;
import com.swiftcheck.aml.yente.YenteModels.IndexInfo;

/**
 * Lit l'état de Yente : son API ({@code /healthz}, {@code /readyz}, {@code /catalog}) et son Elasticsearch
 * (index, alias, journal d'indexation, verrou, santé, disque). Lecture seule.
 *
 * <p>Noms Elasticsearch, dérivés de {@code YENTE_INDEX_NAME} comme dans le code de Yente 5.5.0 : alias
 * {@code <nom>-entities}, index {@code <nom>-entities-<dataset>-<hash>-<version>}, journal {@code <nom>-audit-log-1},
 * verrou {@code <nom>-locks}.
 *
 * <p>Sauf {@link #health}, chaque méthode lève {@link IllegalStateException} avec un message affichable si le
 * service est injoignable ou répond une erreur.
 */
@Component
public class YenteMonitorClient {

    /** Durée de validité du verrou d'indexation de Yente ({@code LOCK_EXPIRATION_TIME}). */
    static final Duration LOCK_EXPIRATION = Duration.ofMinutes(10);
    private static final String CLEANUP = "cleanup_index_deleted";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // --- API Yente ---

    /** Ne lève jamais d'exception : un Yente arrêté donne {@code alive=false} et le message dans {@code error}. */
    public Health health(ApplicationConfigEntity config) {
        String base = trimSlash(config.getYenteUrl());
        long start = System.nanoTime();
        try {
            boolean alive = get(base + "/healthz").statusCode() < 300;
            boolean ready = alive && get(base + "/readyz").statusCode() < 300;
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new Health(alive, ready, ms, ready ? null : "Yente répond mais son index n'est pas prêt (/readyz).");
        } catch (Exception e) {
            interruptIfNeeded(e);
            return new Health(false, false, (System.nanoTime() - start) / 1_000_000,
                    "Yente injoignable (" + config.getYenteUrl() + ") : " + message(e));
        }
    }

    public List<CatalogEntry> catalog(ApplicationConfigEntity config) {
        JsonNode root = getJson(trimSlash(config.getYenteUrl()) + "/catalog", "Yente", config.getYenteUrl());
        List<CatalogEntry> entries = new ArrayList<>();
        for (JsonNode d : root.path("datasets")) {
            if (d.path("children").size() > 0) {
                continue;                                    // collection (« default ») : pas un dataset indexé
            }
            entries.add(new CatalogEntry(d.path("name").asText(), d.path("load").asBoolean(true),
                    text(d, "version"), text(d, "index_version"), d.path("index_current").asBoolean(false)));
        }
        return entries;
    }

    // --- Elasticsearch ---

    public List<IndexInfo> indices(ApplicationConfigEntity config) {
        String alias = alias(config);
        JsonNode indices = esGet(config, "/_cat/indices/" + alias + "-*?format=json&h=index,docs.count");
        JsonNode aliases = esGet(config, "/_cat/aliases/" + alias + "?format=json&h=index");
        Set<String> aliased = new HashSet<>();
        aliases.forEach(a -> aliased.add(a.path("index").asText()));

        List<IndexInfo> result = new ArrayList<>();
        for (JsonNode i : indices) {
            String index = i.path("index").asText();
            String rest = index.substring(alias.length() + 1);        // <dataset>-<hash>-<version>
            int firstDash = rest.indexOf('-');
            int lastDash = rest.lastIndexOf('-');
            if (firstDash < 0 || lastDash == firstDash) {
                continue;                                            // nom non conforme : Yente le supprimera
            }
            result.add(new IndexInfo(index, rest.substring(0, firstDash), rest.substring(lastDash + 1),
                    i.path("docs.count").asLong(0), aliased.contains(index)));
        }
        return result;
    }

    public ClusterInfo cluster(ApplicationConfigEntity config) {
        String health = esGet(config, "/_cluster/health").path("status").asText(null);
        Long available = null;
        Integer percent = null;
        for (JsonNode node : esGet(config, "/_cat/allocation?format=json&bytes=b")) {
            if (node.path("node").isTextual() && !"UNASSIGNED".equals(node.path("node").asText())) {
                available = node.path("disk.avail").isNull() ? null : node.path("disk.avail").asLong();
                percent = node.path("disk.percent").isNull() ? null : node.path("disk.percent").asInt();
                break;                                               // un seul nœud dans notre déploiement
            }
        }
        return new ClusterInfo(health, available, percent);
    }

    /**
     * Dernier événement de chaque dataset, <b>sans</b> {@code cleanup_index_deleted} : après chaque réindexation
     * réussie, Yente supprime l'ancien index, et ce nettoyage masquerait le vrai dernier état.
     */
    public Map<String, AuditEvent> lastEvents(ApplicationConfigEntity config) {
        ObjectNode body = json.createObjectNode();
        body.put("size", 0);
        body.putObject("query").putObject("bool").putObject("must_not").putObject("term")
                .put("event_type.keyword", CLEANUP);
        ObjectNode terms = body.putObject("aggs").putObject("d");
        terms.putObject("terms").put("field", "dataset.keyword").put("size", 5000);
        terms.putObject("aggs").putObject("last").putObject("top_hits").put("size", 1)
                .putArray("sort").addObject().put("timestamp", "desc");

        Map<String, AuditEvent> last = new LinkedHashMap<>();
        for (JsonNode bucket : esSearch(config, auditIndex(config), body).path("aggregations").path("d").path("buckets")) {
            JsonNode hit = bucket.path("last").path("hits").path("hits").path(0).path("_source");
            if (!hit.isMissingNode()) {
                last.put(bucket.path("key").asText(), event(hit));
            }
        }
        return last;
    }

    /** Historique d'un dataset, du plus récent au plus ancien, tous types d'événements. */
    public List<AuditEvent> events(ApplicationConfigEntity config, String dataset, int limit) {
        ObjectNode body = json.createObjectNode();
        body.put("size", limit);
        body.putArray("sort").addObject().put("timestamp", "desc");
        body.putObject("query").putObject("term").put("dataset.keyword", dataset);
        List<AuditEvent> events = new ArrayList<>();
        esSearch(config, auditIndex(config), body).path("hits").path("hits")
                .forEach(h -> events.add(event(h.path("_source"))));
        return events;
    }

    /** Date de prise du verrou d'indexation, s'il est encore valide (moins de 10 minutes). */
    public Optional<Instant> activeLockSince(ApplicationConfigEntity config) {
        ObjectNode body = json.createObjectNode().put("size", 1);
        JsonNode hit = esSearch(config, indexName(config) + "-locks", body).path("hits").path("hits").path(0).path("_source");
        if (!hit.path("acquired_at").isNumber()) {
            return Optional.empty();
        }
        Instant acquired = Instant.ofEpochMilli(hit.path("acquired_at").asLong());
        return acquired.isAfter(Instant.now().minus(LOCK_EXPIRATION)) ? Optional.of(acquired) : Optional.empty();
    }

    /** Fin de la dernière indexation de dataset, tous datasets confondus. */
    public Optional<Instant> lastCompletedAt(ApplicationConfigEntity config) {
        ObjectNode body = json.createObjectNode().put("size", 1);
        body.putArray("sort").addObject().put("timestamp", "desc");
        body.putObject("query").putObject("term").put("event_type.keyword", "reindex_completed");
        JsonNode hit = esSearch(config, auditIndex(config), body).path("hits").path("hits").path(0).path("_source");
        return hit.path("timestamp").isNumber() ? Optional.of(Instant.ofEpochMilli(hit.path("timestamp").asLong()))
                : Optional.empty();
    }

    // --- outils ---

    private static String indexName(ApplicationConfigEntity config) {
        String name = config.getYenteIndexName();
        return name == null || name.isBlank() ? "yente" : name.trim();
    }

    private static String alias(ApplicationConfigEntity config) {
        return indexName(config) + "-entities";
    }

    private static String auditIndex(ApplicationConfigEntity config) {
        return indexName(config) + "-audit-log-1";
    }

    private AuditEvent event(JsonNode source) {
        Instant at = source.path("timestamp").isNumber() ? Instant.ofEpochMilli(source.path("timestamp").asLong()) : null;
        return new AuditEvent(text(source, "dataset"), text(source, "event_type"), text(source, "dataset_version"), at,
                text(source, "message"));
    }

    private JsonNode esGet(ApplicationConfigEntity config, String path) {
        return getJson(trimSlash(config.getYenteIndexUrl()) + path, "Elasticsearch", config.getYenteIndexUrl());
    }

    private JsonNode esSearch(ApplicationConfigEntity config, String index, JsonNode body) {
        String url = trimSlash(config.getYenteIndexUrl()) + "/" + index + "/_search";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            return parse(http.send(request, HttpResponse.BodyHandlers.ofString()), "Elasticsearch", config.getYenteIndexUrl());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            interruptIfNeeded(e);
            throw new IllegalStateException("Elasticsearch injoignable (" + config.getYenteIndexUrl() + ") : " + message(e));
        }
    }

    private JsonNode getJson(String url, String service, String configuredUrl) {
        try {
            return parse(get(url), service, configuredUrl);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            interruptIfNeeded(e);
            throw new IllegalStateException(service + " injoignable (" + configuredUrl + ") : " + message(e));
        }
    }

    private JsonNode parse(HttpResponse<String> response, String service, String configuredUrl) throws Exception {
        if (response.statusCode() >= 300) {
            throw new IllegalStateException(service + " a répondu HTTP " + response.statusCode() + " (" + configuredUrl + ").");
        }
        return json.readTree(response.body());
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static void interruptIfNeeded(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Adresse non configurée (écran Configuration).");
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
