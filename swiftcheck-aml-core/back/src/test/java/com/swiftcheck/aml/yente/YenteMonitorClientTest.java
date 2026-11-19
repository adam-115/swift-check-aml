package com.swiftcheck.aml.yente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.yente.YenteModels.AuditEvent;
import com.swiftcheck.aml.yente.YenteModels.CatalogEntry;
import com.swiftcheck.aml.yente.YenteModels.IndexInfo;

/** Lecture de l'API Yente et de son Elasticsearch, sur des réponses enregistrées depuis un Yente 5.5.0. */
class YenteMonitorClientTest {

    private HttpServer yente;
    private HttpServer es;
    private final List<String> searchBodies = new CopyOnWriteArrayList<>();
    private volatile long lockAcquiredAt;
    private final YenteMonitorClient client = new YenteMonitorClient();
    private ApplicationConfigEntity config;

    @BeforeEach
    void start() throws IOException {
        yente = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        yente.createContext("/healthz", ex -> send(ex, 200, "{\"status\":\"ok\"}"));
        yente.createContext("/readyz", ex -> send(ex, 200, "{\"status\":\"ok\"}"));
        yente.createContext("/catalog", ex -> send(ex, 200, fixture("catalog.json")));
        yente.start();

        es = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        es.createContext("/_cat/indices/yente-entities-*", ex -> send(ex, 200, fixture("indices.json")));
        es.createContext("/_cat/aliases/yente-entities", ex -> send(ex, 200, fixture("aliases.json")));
        es.createContext("/_cluster/health", ex -> send(ex, 200, fixture("health.json")));
        es.createContext("/_cat/allocation", ex -> send(ex, 200, fixture("allocation.json")));
        es.createContext("/yente-audit-log-1/_search", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            searchBodies.add(body);
            if (body.contains("aggs")) {
                send(ex, 200, fixture("last-events.json"));
            } else if (body.contains("reindex_completed")) {
                send(ex, 200, "{\"hits\":{\"hits\":[{\"_source\":{\"dataset\":\"fr_pep_senate\",\"event_type\":\"reindex_completed\","
                        + "\"dataset_version\":\"20261118134343\",\"timestamp\":1795010051000,\"message\":\"ok\"}}]}}");
            } else {
                send(ex, 200, fixture("events.json"));
            }
        });
        es.createContext("/yente-locks/_search", ex -> send(ex, 200,
                "{\"hits\":{\"hits\":[{\"_source\":{\"acquired_at\":" + lockAcquiredAt + ",\"lock_session_id\":\"x\"}}]}}"));
        es.start();

        config = new ApplicationConfigEntity();
        config.setYenteUrl("http://127.0.0.1:" + yente.getAddress().getPort() + "/");
        config.setYenteIndexUrl("http://127.0.0.1:" + es.getAddress().getPort());
        config.setYenteIndexName("yente");
    }

    @AfterEach
    void stop() {
        yente.stop(0);
        es.stop(0);
    }

    @Test
    void indicesRattachesAuBonDataset() {
        Map<String, List<IndexInfo>> byDataset = client.indices(config).stream()
                .collect(java.util.stream.Collectors.groupingBy(IndexInfo::dataset));

        assertEquals(1, byDataset.get("nl_pep_parliament").size());
        assertEquals(1, byDataset.get("nl_pep_parliament_history").size());
        IndexInfo base = byDataset.get("nl_pep_parliament").get(0);
        assertEquals("20261105233755", base.version());
        assertEquals(658, base.docs());
        assertTrue(base.aliased());

        List<IndexInfo> senate = byDataset.get("fr_pep_senate");
        assertEquals(2, senate.size());
        IndexInfo building = senate.stream().filter(i -> i.version().equals("20261119000000")).findFirst().orElseThrow();
        assertFalse(building.aliased(), "version plus récente pas encore rattachée à l'alias");
        assertEquals(1200, building.docs());
    }

    @Test
    void catalogIgnoreLesCollections() {
        List<CatalogEntry> catalog = client.catalog(config);

        assertFalse(catalog.stream().anyMatch(c -> c.name().equals("default")));
        CatalogEntry senate = catalog.stream().filter(c -> c.name().equals("fr_pep_senate")).findFirst().orElseThrow();
        assertEquals("20261118134343", senate.version());
        assertEquals("20261118134343", senate.indexVersion());
        assertTrue(senate.indexCurrent());
        assertTrue(senate.load());
    }

    @Test
    void dernierEvenementIgnoreLeNettoyage() {
        Map<String, AuditEvent> last = client.lastEvents(config);

        assertTrue(searchBodies.get(0).contains("must_not") && searchBodies.get(0).contains("cleanup_index_deleted"),
                searchBodies.get(0));
        assertEquals("index_alias_rollover_complete", last.get("fr_pep_senate").type());
        assertEquals("reindex_failed", last.get("lu_chamber").type());
        assertNotNull(last.get("lu_chamber").at());
    }

    @Test
    void evenementsDuPlusRecentAuPlusAncien() {
        List<AuditEvent> events = client.events(config, "fr_pep_senate", 6);

        assertEquals(6, events.size());
        assertEquals("cleanup_index_deleted", events.get(0).type());
        for (int i = 1; i < events.size(); i++) {
            assertFalse(events.get(i).at().isAfter(events.get(i - 1).at()), "ordre décroissant");
        }
        assertTrue(searchBodies.get(0).contains("fr_pep_senate"));
    }

    @Test
    void verrouActifSeulementMoinsDeDixMinutes() {
        lockAcquiredAt = Instant.now().minus(Duration.ofMinutes(2)).toEpochMilli();
        assertTrue(client.activeLockSince(config).isPresent());

        lockAcquiredAt = Instant.now().minus(Duration.ofMinutes(11)).toEpochMilli();
        assertTrue(client.activeLockSince(config).isEmpty());
    }

    @Test
    void derniereFinDIndexation() {
        assertEquals(Instant.ofEpochMilli(1795010051000L), client.lastCompletedAt(config).orElseThrow());
    }

    @Test
    void clusterEtDisque() {
        YenteModels.ClusterInfo cluster = client.cluster(config);

        assertEquals("yellow", cluster.health());
        assertEquals(1005067476992L, cluster.diskAvailableBytes());
        assertEquals(7, cluster.diskUsedPercent());
    }

    @Test
    void healthNeLevePasQuandYenteEstArrete() {
        YenteModels.Health up = client.health(config);
        assertTrue(up.alive() && up.ready(), String.valueOf(up.error()));

        config.setYenteUrl("http://127.0.0.1:1");
        YenteModels.Health down = client.health(config);
        assertFalse(down.alive());
        assertFalse(down.ready());
        assertNotNull(down.error());
    }

    @Test
    void elasticsearchInjoignableLeveUneErreurClaire() {
        config.setYenteIndexUrl("http://127.0.0.1:1");
        var error = assertThrows(IllegalStateException.class, () -> client.indices(config));
        assertTrue(error.getMessage().startsWith("Elasticsearch injoignable"), error.getMessage());
    }

    private static String fixture(String name) {
        try (InputStream in = YenteMonitorClientTest.class.getResourceAsStream("/yente/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
