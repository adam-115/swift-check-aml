package com.swiftcheck.aml.entities;

import java.time.Instant;

import jakarta.persistence.Column;
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
 * Une exécution d'un parser : de la récupération des données à la publication
 * dans Yente.
 * C'est l'historique consulté pour savoir quelle version d'une liste était
 * utilisée à une date donnée.
 *
 * <p>
 * Les fichiers récupérés sont dans {@link SourceFile}, le détail des
 * changements dans {@link DatasetChange}.
 */
@Getter
@Setter
@Entity
public class ParserExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne
    private Parser parser;

    @Enumerated(EnumType.STRING)
    private ExecutionTriggerEnum triggerType;

    @Enumerated(EnumType.STRING)
    private ParserExecutionStatus status;

    private Instant startedAt;

    private Instant finishedAt;

    /**
     * Empreinte SHA-256 des données sources (si plusieurs fichiers : empreinte de
     * leurs empreintes).
     * Si elle est identique à celle de la dernière exécution réussie, l'exécution
     * s'arrête en UNCHANGED.
     */
    private String inputSha256;

    /** Nombre d'entités dans le fichier produit par le plugin (compté par le core, sans la rétention). */
    private int entityCount;

    /** Nombre de personnes (schéma {@code Person}) produites par le plugin : affiché dans la couverture PEP. */
    private int personCount;

    /** Entités disparues de la source mais encore publiées grâce à la rétention ({@link RetainedEntity}). */
    private int retainedCount;

    /** Nombre de valeurs refusées par le plugin (pays ou date illisible…). */
    private int rejectedCount;

    /**
     * Entités ajoutées, retirées et modifiées par rapport à la version publiée
     * précédente.
     */
    private int addedCount;
    private int removedCount;
    private int changedCount;

    /**
     * Résumé lisible : bilan en cas de succès, raison du blocage ou de l'échec
     * sinon.
     */
    @Column(columnDefinition = "LONGTEXT")
    private String message;
}
