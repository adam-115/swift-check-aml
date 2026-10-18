package com.swiftcheck.aml.entities;

/** Part des titulaires d'une fonction qu'une source permet de récupérer. */
public enum Completeness {
    /** Tous les titulaires en fonction. */
    COMPLETE,
    /** Une partie seulement (ex. nominations publiées depuis 2021, une partie des institutions). */
    PARTIAL
}
