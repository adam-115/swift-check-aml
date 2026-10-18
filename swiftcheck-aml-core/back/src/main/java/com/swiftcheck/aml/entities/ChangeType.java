package com.swiftcheck.aml.entities;

/** Nature d'un changement entre deux versions publiées d'une liste. */
public enum ChangeType {
    /** L'entité n'existait pas dans la version précédente. */
    ADDED,
    /** L'entité a disparu de la liste (ex. personne radiée). */
    REMOVED,
    /** L'entité existe dans les deux versions mais au moins une propriété a changé. */
    MODIFIED
}
