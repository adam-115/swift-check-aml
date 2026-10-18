package com.swiftcheck.aml.entities;

/** Qui a lancé l'exécution. */
public enum ExecutionTriggerEnum {
    /** Un utilisateur, depuis l'écran ou l'API. */
    MANUAL,
    /** Le planificateur, selon {@code Parser.refreshHours}. */
    SCHEDULED
}
