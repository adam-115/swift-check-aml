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
 * Un fichier source utilisé par une exécution, signé et archivé : c'est la preuve des données utilisées.
 * Pour une source HTTP ou locale, il y en a un par exécution ; pour une source PLUGIN_MANAGED, un par
 * réponse brute déposée par le plugin.
 *
 * <p>Pour vérifier qu'une archive n'a pas été modifiée, il suffit de recalculer son SHA-256 et de le comparer
 * à {@link #sha256}.
 */
@Getter
@Setter
@Entity
public class SourceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Exécution concernée (identifiant simple, sans relation JPA, pour garder l'entité légère). */
    private long executionId;

    /** D'où vient le fichier : URL, chemin local, ou nom du fichier déposé par le plugin. */
    @Column(length = 2048)
    private String origin;

    /** Empreinte SHA-256 du contenu, en hexadécimal (64 caractères). */
    private String sha256;

    private long sizeBytes;

    /** Emplacement de la copie archivée. */
    @Column(length = 1024)
    private String archivePath;

    private Instant fetchedAt;
}
