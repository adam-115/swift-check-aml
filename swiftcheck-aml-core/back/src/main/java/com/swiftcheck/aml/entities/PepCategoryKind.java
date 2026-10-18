package com.swiftcheck.aml.entities;

/** Nature d'une catégorie de fonctions PEP. */
public enum PepCategoryKind {
    /** Catégorie de la liste officielle publiée par le pays (JO C/2023/724). */
    OFFICIAL,
    /** Fonction ajoutée par le règlement (UE) 2024/1624 (AMLR), applicable en juillet 2027. */
    AMLR,
    /** Fonction hors liste, reprise selon l'approche par les risques (ex. élus communaux). */
    RISK_BASED,
    /** Fonction dans une institution européenne exercée par un ressortissant du pays. */
    EU,
    /** Membres de la famille et proches associés d'une PEP. */
    RCA
}
