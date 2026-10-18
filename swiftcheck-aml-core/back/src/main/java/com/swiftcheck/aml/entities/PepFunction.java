package com.swiftcheck.aml.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * Une fonction précise d'une catégorie PEP, ex. « Député » à la Chambre des Députés.
 * Un parser déclare les fonctions qu'il couvre ({@link Parser#getCoveredFunctions()}).
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
}
