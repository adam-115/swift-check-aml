package com.swiftcheck.aml.dto;

import java.time.Instant;

/**
 * État d'un dataset dans Yente (écran « Yente »), en lecture seule.
 *
 * @param state            nom de {@code DatasetState}
 * @param countVerified    vrai si le nombre d'entités attendu est connu (fichier publié lisible)
 * @param buildingEntities documents déjà écrits dans l'index en construction (indexation en cours)
 * @param progressPercent  avancement : 0–99 pendant l'indexation, 100 une fois servi ; null si inconnu
 */
public record YenteDatasetStatusDTO(
        String dataset,
        Long parserId,
        String parserName,
        String state,
        boolean countVerified,
        Long expectedEntities,
        Long indexedEntities,
        Long buildingEntities,
        String fileVersion,
        String servedVersion,
        Integer progressPercent,
        String lastEventType,
        Instant lastEventAt,
        String lastEventMessage) {
}
