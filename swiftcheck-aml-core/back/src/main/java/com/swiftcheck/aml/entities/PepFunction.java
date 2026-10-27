package com.swiftcheck.aml.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.OneToMany;
import lombok.Getter;
import lombok.Setter;

/**
 * Une fonction précise d'une catégorie PEP, ex. « Député » à la Chambre des Députés.
 * Les parsers qui la couvrent sont dans {@link #parsers} (table pep_function_parser).
 */
@Getter
@Setter
@Entity
public class PepFunction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne
    private PepCategory category;

    private String label;

    /** Institution où s'exerce la fonction, ex. « Chambre des Députés ». */
    private String institution;

    /** Nombre approximatif de titulaires en fonction ; {@code null} si inconnu. */
    private Integer expectedHolders;

    /** Parsers qui couvrent la fonction, avec leur complétude. */
    @JsonIgnore
    @OneToMany(mappedBy = "function")
    private List<PepFunctionParser> parsers = new ArrayList<>();
}
