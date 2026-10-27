package com.swiftcheck.aml.entities;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Une fonction PEP couverte par un parser, et à quel point (table {@code pep_function_parser}).
 * Un parser peut couvrir plusieurs fonctions (lu_pep_legilux en couvre 9) et une fonction peut être couverte
 * par plusieurs parsers (députés en fonction et anciens députés).
 */
@Getter
@Setter
@Entity
@Table(name = "pep_function_parser")
public class PepFunctionParser {

    /** Clé composée (fonction, parser). */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long functionId;
        private Long parserId;
    }

    @EmbeddedId
    private Key id = new Key();

    @ManyToOne(fetch = FetchType.EAGER)
    @MapsId("functionId")
    @JoinColumn(name = "function_id")
    private PepFunction function;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("parserId")
    @JoinColumn(name = "parser_id")
    private Parser parser;

    /** COMPLETE : tous les titulaires ; PARTIAL : une partie seulement (ex. nominations récentes). */
    @Enumerated(EnumType.STRING)
    private Completeness completeness = Completeness.COMPLETE;

    /** Le parser donne aussi les anciens titulaires (vigilance après la fin des fonctions). */
    private boolean includesFormer;

    public PepFunctionParser() {
    }

    public PepFunctionParser(PepFunction function, Completeness completeness, boolean includesFormer) {
        this.function = function;
        this.completeness = completeness == null ? Completeness.COMPLETE : completeness;
        this.includesFormer = includesFormer;
    }
}
