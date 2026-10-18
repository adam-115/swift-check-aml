package com.swiftcheck.aml.entities;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/**
 * Entité disparue de la source mais encore publiée dans Yente pendant {@code Parser.retentionMonths} mois.
 * Ex. un ministre qui quitte le gouvernement disparaît de gouvernement.lu, mais doit rester PEP au moins
 * 12 mois : sa ligne FtM est gardée ici et ajoutée au fichier publié à chaque exécution.
 */
@Getter
@Setter
@Entity
public class RetainedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long parserId;

    /** Identifiant FtM, ex. {@code lu-pep-government-luc-frieden}. */
    private String entityId;

    private String entitySchema;

    @Column(length = 1024)
    private String caption;

    /** Ligne FtM complète, telle qu'elle était publiée avant sa disparition de la source. */
    @Column(columnDefinition = "LONGTEXT")
    private String json;

    /** Première exécution où l'entité n'était plus dans la source. */
    private Instant removedAt;
}
