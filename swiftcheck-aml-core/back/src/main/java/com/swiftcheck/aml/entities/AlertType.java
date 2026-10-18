package com.swiftcheck.aml.entities;

/** Raison d'une alerte sur un parser. */
public enum AlertType {
    /** La dernière exécution a échoué : la liste publiée n'est plus mise à jour. */
    FAILED,
    /** La dernière exécution a été bloquée par les contrôles qualité. */
    BLOCKED,
    /** Aucune publication réussie depuis plus de deux fois la fréquence du parser. */
    STALE
}
