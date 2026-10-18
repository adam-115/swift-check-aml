package com.swiftcheck.aml.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/**
 * Une entité ajoutée, retirée ou modifiée lors d'une publication, ex. « Ivan PETROV ajouté à la liste UE ».
 * Permet d'afficher ce qui a changé dans une liste sans comparer les fichiers à la main.
 */
@Getter
@Setter
@Entity
public class DatasetChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Exécution qui a publié le changement. */
    private long executionId;

    /** Identifiant FtM de l'entité, ex. {@code eu-fsd-13}. */
    private String entityId;

    /** Schéma FtM : Person, Organization, Sanction… ("schema" est un mot réservé en MySQL). */
    private String entitySchema;

    /** Nom lisible (premier {@code name} de l'entité). */
    @Column(length = 1024)
    private String caption;

    @Enumerated(EnumType.STRING)
    private ChangeType changeType;

    /** Pour MODIFIED : propriétés modifiées, séparées par des virgules (ex. {@code alias, birthDate}). */
    @Column(columnDefinition = "TEXT")
    private String changedProperties;
}
