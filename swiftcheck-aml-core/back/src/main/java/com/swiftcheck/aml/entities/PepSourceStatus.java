package com.swiftcheck.aml.entities;

/**
 * Décision sur une source PEP. « Implémentée » n'est pas un statut : une source l'est dès qu'un parser la lit
 * ({@code Parser.source}).
 */
public enum PepSourceStatus {
    /** Source identifiée, à exploiter. */
    CANDIDATE,
    /** Source active / exploitée. */
    ACTIVE,
    /** Source écartée (licence, qualité, accès…) : la raison est dans les remarques. */
    REJECTED
}
