package com.swiftcheck.aml.yente;

import com.swiftcheck.aml.dto.YenteDatasetStatusDTO;
import com.swiftcheck.aml.yente.YenteModels.AuditEvent;
import com.swiftcheck.aml.yente.YenteModels.CatalogEntry;
import com.swiftcheck.aml.yente.YenteModels.IndexInfo;

/**
 * Calcule l'état d'un dataset à partir de ce qu'on sait de lui (fonction pure, sans appel réseau). La première
 * règle qui s'applique l'emporte, dans l'ordre de {@link DatasetState}.
 */
public final class DatasetStateResolver {

    private DatasetStateResolver() {
    }

    /**
     * Ce qu'on sait d'un dataset. Les champs objet sont null quand l'information manque.
     *
     * @param activeParser     un parser actif publie ce dataset
     * @param published        ce parser a au moins une publication réussie
     * @param inManifest       présent dans {@code manifest.yml}
     * @param catalog          entrée de {@code /catalog}
     * @param served           index rattaché à l'alias
     * @param building         index plus récent, pas encore rattaché (indexation en cours)
     * @param lastEvent        dernier événement du journal, hors nettoyage
     * @param expectedEntities lignes du fichier publié
     * @param lockActive       le verrou d'indexation de Yente est tenu : un {@code reindex_started} sans fin n'est une
     *                         indexation en cours que dans ce cas (sinon Yente a été arrêté pendant l'indexation)
     */
    public record DatasetInputs(String dataset, Long parserId, String parserName, boolean activeParser,
            boolean published, boolean inManifest, CatalogEntry catalog, IndexInfo served, IndexInfo building,
            AuditEvent lastEvent, Long expectedEntities, boolean lockActive) {

        /** Verrou supposé tenu. */
        public DatasetInputs(String dataset, Long parserId, String parserName, boolean activeParser,
                boolean published, boolean inManifest, CatalogEntry catalog, IndexInfo served, IndexInfo building,
                AuditEvent lastEvent, Long expectedEntities) {
            this(dataset, parserId, parserName, activeParser, published, inManifest, catalog, served, building,
                    lastEvent, expectedEntities, true);
        }
    }

    public static YenteDatasetStatusDTO resolve(DatasetInputs in) {
        DatasetState state = state(in);
        Long expected = in.expectedEntities();
        Integer progress = switch (state) {
            case OK, COUNT_MISMATCH -> 100;
            case INDEXING -> in.building() != null && expected != null && expected > 0
                    ? (int) Math.min(99, in.building().docs() * 100 / expected)
                    : null;
            default -> null;
        };
        AuditEvent last = in.lastEvent();
        return new YenteDatasetStatusDTO(in.dataset(), in.parserId(), in.parserName(), state.name(), expected != null,
                expected, in.served() != null ? in.served().docs() : null,
                in.building() != null ? in.building().docs() : null,
                in.catalog() != null ? in.catalog().version() : null,
                in.served() != null ? in.served().version() : null,
                progress,
                last != null ? last.type() : null, last != null ? last.at() : null, last != null ? last.message() : null);
    }

    private static DatasetState state(DatasetInputs in) {
        if (in.activeParser() && !in.published()) {
            return DatasetState.NOT_PUBLISHED;
        }
        if (in.activeParser() && !in.inManifest()) {
            return DatasetState.NOT_IN_MANIFEST;
        }
        if (in.inManifest() && in.catalog() == null) {
            return DatasetState.NOT_LOADED;
        }
        if (isEvent(in, "reindex_failed")) {
            return DatasetState.FAILED;
        }
        boolean indexing = in.building() != null || (in.lockActive() && isEvent(in, "reindex_started"));
        if (in.served() == null && !indexing) {
            return DatasetState.NOT_SERVED;
        }
        if (indexing) {
            return DatasetState.INDEXING;
        }
        if (in.catalog() != null && !in.catalog().indexCurrent()) {
            return DatasetState.OUTDATED;
        }
        if (!in.activeParser() && !in.inManifest()) {
            return DatasetState.ORPHAN;
        }
        if (in.expectedEntities() != null && in.served().docs() != in.expectedEntities()) {
            return DatasetState.COUNT_MISMATCH;
        }
        return DatasetState.OK;
    }

    private static boolean isEvent(DatasetInputs in, String type) {
        return in.lastEvent() != null && type.equals(in.lastEvent().type());
    }
}
