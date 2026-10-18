package com.swiftcheck.aml.entities;

/** Résultat d'un test de parser. */
public enum ParserTestStatus {
    /** Test en cours. */
    RUNNING,
    /** Tous les éléments de la source sont dans Yente, et (presque) tous retrouvés par leur nom. */
    PASSED,
    /** Des éléments manquent dans Yente, ou le test n'a pas pu aller au bout (voir le message). */
    FAILED
}
