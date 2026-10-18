package com.swiftcheck.aml.entities;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/**
 * Pays couvert (UE + Suisse). Donnée de référence remplie par Liquibase.
 * {@link #retentionMonths} est la durée minimale pendant laquelle une personne reste PEP après la fin de ses
 * fonctions ; c'est la valeur proposée par défaut pour la rétention des parsers PEP du pays.
 */
@Getter
@Setter
@Entity
public class Country {

    /** Code ISO 3166-1 alpha-2 en majuscules, ex. {@code LU}. */
    @Id
    @Column(length = 2)
    private String iso;

    private String name;

    private boolean euMember;

    /** Texte de loi qui définit les PEP dans ce pays. */
    private String legalBasis;

    private int retentionMonths;

    /**
     * Liste officielle des fonctions PEP déclarée par le pays à la Commission (art. 20 bis de la directive
     * 2015/849), ex. {@code JO C/2023/724} : la version sur laquelle repose la couverture.
     */
    private String officialListReference;

    private LocalDate officialListDate;

    @Column(length = 1024)
    private String officialListUrl;
}
