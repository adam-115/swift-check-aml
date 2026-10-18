package com.swiftcheck.aml.entities;

/** Conditions d'accès à une source PEP. */
public enum SourceAccess {
    /** Accès libre. */
    OPEN,
    /** Inscription, demande d'accès ou usage limité (ex. registre réservé aux entités assujetties). */
    RESTRICTED,
    /** Accès payant. */
    PAID
}
