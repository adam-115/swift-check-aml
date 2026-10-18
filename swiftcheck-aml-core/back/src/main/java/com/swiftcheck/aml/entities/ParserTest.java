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
 * Un test de parser : chaque élément du fichier source de la dernière publication est cherché dans Yente.
 * Les éléments non retrouvés sont dans {@link ParserTestFailure}.
 *
 * <p>Total : {@code sourceCount = foundCount + notConvertedCount + notIndexedCount + notMatchedCount + errorCount}.
 */
@Getter
@Setter
@Entity
public class ParserTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne
    private Parser parser;

    /** Publication testée (exécution SUCCESS dont le fichier source archivé a été relu). */
    private Long executionId;

    @Enumerated(EnumType.STRING)
    private ParserTestStatus status;

    private Instant startedAt;

    private Instant finishedAt;

    /** Éléments lus dans le fichier source (personnes, organisations, navires…). */
    private int sourceCount;

    /** Retrouvés dans Yente par leur ID et par leur nom. */
    private int foundCount;

    private int notConvertedCount;

    private int notIndexedCount;

    private int notMatchedCount;

    private int errorCount;

    @Column(columnDefinition = "LONGTEXT")
    private String message;
}
