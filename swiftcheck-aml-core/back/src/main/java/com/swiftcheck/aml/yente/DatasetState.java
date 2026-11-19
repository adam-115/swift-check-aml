package com.swiftcheck.aml.yente;

/**
 * État d'un dataset dans Yente, du plus grave au moins grave (ordre de tri de l'écran ; {@code ORPHAN} en dernier).
 * Voir {@link DatasetStateResolver} pour les règles.
 */
public enum DatasetState {
    /** Parser actif qui n'a encore jamais publié (information, hors pourcentage). */
    NOT_PUBLISHED,
    /** Parser actif ayant publié, mais dataset absent du manifest. */
    NOT_IN_MANIFEST,
    /** Dans le manifest, mais pas encore dans le catalog de Yente (manifest pas encore relu). */
    NOT_LOADED,
    /** Dernière indexation en échec ({@code reindex_failed}). */
    FAILED,
    /** Aucun index rattaché à l'alias : introuvable par le criblage. */
    NOT_SERVED,
    /** Indexation en cours. */
    INDEXING,
    /** Nouvelle version du fichier, pas encore indexée et aucune indexation en cours (en attente du prochain passage). */
    OUTDATED,
    /** Servi à jour, mais le nombre de documents diffère du nombre de lignes du fichier publié. */
    COUNT_MISMATCH,
    /** Servi à jour. */
    OK,
    /** Servi par Yente, mais ni parser actif ni entrée dans le manifest (information, hors pourcentage). */
    ORPHAN
}
