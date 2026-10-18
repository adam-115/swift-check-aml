package com.swiftcheck.aml.entities;

/** Résultat d'une exécution de parser. */
public enum ParserExecutionStatus {

    /** En cours. */
    RUNNING,

    /** Nouvelle version publiée dans Yente. */
    SUCCESS,

    /** Données identiques à la dernière publication (même empreinte SHA-256) : rien n'a été fait. */
    UNCHANGED,

    /** Contrôles qualité en échec : la nouvelle version n'est pas publiée, l'ancienne reste utilisée. */
    BLOCKED,

    /** Erreur technique (téléchargement, plugin introuvable, exception du plugin…). Rien n'est publié. */
    FAILED
}
