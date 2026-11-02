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
 * Photo d'un PEP, téléchargée et archivée par le core.
 *
 * <ul>
 *   <li>{@code OFFICIAL} : déclarée par un plugin (page officielle de la source, ou Wikimedia Commons pour une personne
 *       identifiée par son QID), remplacée à chaque publication du dataset ;</li>
 *   <li>{@code WEB_VALIDATED} : trouvée par une recherche sur internet et retenue par un analyste ({@link #validatedBy}) ;
 *       jamais remplacée par une publication.</li>
 * </ul>
 * Une photo appartient au dataset qui l'a publiée, au plus une par entité et par origine : elle n'est jamais partagée
 * avec un autre dataset.
 */
@Getter
@Setter
@Entity
public class PepMedia {

    public static final String OFFICIAL = "OFFICIAL";
    public static final String WEB_VALIDATED = "WEB_VALIDATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** ID FtM de la personne dans son dataset (ex. {@code fr-pep-an-PA718838}). */
    private String entityId;
    private String datasetName;
    private String origin;

    @Column(length = 2048)
    private String imageUrl;
    /** Page où l'image est publiée : la preuve de son rattachement à la personne. */
    @Column(length = 2048)
    private String pageUrl;
    /** Licence ou mention affichée sous la photo (« © Assemblée nationale », « CC BY-SA 4.0 – auteur »…). */
    @Column(length = 1000)
    private String licence;
    private String contentType;
    private String sha256;
    private long sizeBytes;
    @Column(length = 1024)
    private String archivePath;

    /** Analyste qui a retenu une photo trouvée sur internet. */
    private String validatedBy;
    private Instant fetchedAt;
}
