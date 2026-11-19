package com.swiftcheck.aml.dto;

import java.time.Instant;
import java.util.List;

/**
 * État complet de Yente pour l'écran « Yente » ({@code GET /api/yente/status}), en lecture seule.
 *
 * @param yenteError     null si Yente répond et est prêt
 * @param indexError     null si Elasticsearch répond
 * @param manifestError  null si manifest.yml a pu être lu
 * @param indexing       une indexation est en cours (verrou actif ou dataset en cours d'indexation)
 * @param lockSince      prise du verrou d'indexation, s'il est actif
 * @param expectedCount  datasets attendus (hors « jamais publié » et « orphelin »)
 * @param okCount        datasets attendus servis à jour
 * @param percent        okCount / expectedCount en %, null si aucun dataset attendu
 */
public record YenteStatusDTO(
        Instant checkedAt,
        boolean yenteAlive,
        boolean yenteReady,
        long yenteResponseMs,
        String yenteError,
        String clusterHealth,
        Long diskAvailableBytes,
        Integer diskUsedPercent,
        String indexError,
        String manifestError,
        boolean indexing,
        Instant lockSince,
        Instant lastCompletedAt,
        int okCount,
        int expectedCount,
        Integer percent,
        List<YenteDatasetStatusDTO> datasets) {
}
