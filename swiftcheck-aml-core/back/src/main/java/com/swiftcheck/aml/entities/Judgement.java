package com.swiftcheck.aml.entities;

/** Décision sur deux entités de datasets différents. */
public enum Judgement {
    /** Même personne. */
    POSITIVE,
    /** Personnes différentes. */
    NEGATIVE,
    /** Impossible de trancher avec les informations disponibles. */
    UNSURE
}
