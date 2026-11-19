package com.swiftcheck.aml.yente;

import java.time.Instant;

/** Données brutes lues dans l'API Yente et dans son Elasticsearch (voir {@link YenteMonitorClient}). */
public final class YenteModels {

    private YenteModels() {
    }

    /** {@code /healthz} (le process répond) et {@code /readyz} (l'index est prêt). {@code error} : null si tout va bien. */
    public record Health(boolean alive, boolean ready, long responseMs, String error) {
    }

    /** Un dataset de {@code /catalog} (les collections, qui ont des {@code children}, sont écartées). */
    public record CatalogEntry(String name, boolean load, String version, String indexVersion, boolean indexCurrent) {
    }

    /** Un index {@code <nom>-entities-<dataset>-<hash>-<version>} ; {@code aliased} : rattaché à l'alias, donc servi. */
    public record IndexInfo(String index, String dataset, String version, long docs, boolean aliased) {
    }

    /** Santé du cluster (green, yellow, red) et disque du nœud. */
    public record ClusterInfo(String health, Long diskAvailableBytes, Integer diskUsedPercent) {
    }

    /** Un événement du journal d'indexation de Yente ({@code reindex_started}, {@code reindex_failed}…). */
    public record AuditEvent(String dataset, String type, String version, Instant at, String message) {
    }
}
