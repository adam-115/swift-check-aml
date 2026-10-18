package com.swiftcheck.aml.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * Source où l'on peut récupérer les titulaires de fonctions PEP : fichier open data, API, journal officiel,
 * page web, registre de déclarations… Les fonctions qu'elle couvre sont dans {@link PepFunctionSource}.
 *
 * <p>Une source sans parser est une piste de travail ; elle est implémentée dès qu'un parser la lit
 * ({@link Parser#getSource()}). Règle : un parser lit une seule source.
 */
@Getter
@Setter
@Entity
public class PepSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne
    @JoinColumn(name = "country_iso")
    private Country country;

    /** Nom lisible, ex. « Chambre des Députés – députés en fonction ». */
    private String name;

    /** Adresse de la source (fichier, API, page) ; vide si elle n'a pas d'adresse unique (ex. sites des partis). */
    @Column(length = 2048)
    private String url;

    /** Type T1 à T7 : dit quel parser il faudra écrire. */
    @Enumerated(EnumType.STRING)
    private ParserKind parserKind;

    private String licence;

    @Enumerated(EnumType.STRING)
    private SourceAccess access;

    @Enumerated(EnumType.STRING)
    private PepSourceStatus status;

    @Column(columnDefinition = "TEXT")
    private String notes;
}
