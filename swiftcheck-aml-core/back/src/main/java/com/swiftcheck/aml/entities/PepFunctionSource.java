package com.swiftcheck.aml.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * Lien entre une fonction PEP et une source qui permet d'en récupérer les titulaires.
 * Ex. « Membre du Conseil d'État » : conseil-etat.public.lu (complète, en fonction seulement) et Legilux
 * (partielle, avec les anciens membres).
 */
@Getter
@Setter
@Entity
public class PepFunctionSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne
    private PepFunction function;

    @ManyToOne
    private PepSource source;

    @Enumerated(EnumType.STRING)
    private Completeness completeness;

    /** Vrai si la source donne aussi les anciens titulaires (vigilance après la fin des fonctions). */
    private boolean includesFormer;
}
