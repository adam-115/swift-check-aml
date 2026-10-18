package com.swiftcheck.aml.entities;

/** Pourquoi un élément du fichier source n'a pas été retrouvé dans Yente. Indique où chercher le problème. */
public enum TestFailureReason {
    /** Présent dans la source, absent du NDJSON publié : le plugin l'a perdu (bug de conversion). */
    NOT_CONVERTED,
    /** Dans le NDJSON publié, mais Yente ne le connaît pas ({@code GET /entities/{id}} → 404) : indexation. */
    NOT_INDEXED,
    /** Indexé, mais une recherche par son nom ({@code /match}) ne le renvoie pas : nom ou matching. */
    NOT_MATCHED,
    /** Yente n'a pas répondu (erreur réseau, HTTP 5xx) : l'élément n'a pas pu être vérifié. */
    YENTE_ERROR
}
