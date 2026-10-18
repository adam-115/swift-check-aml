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
 * Un incident sur un parser. Ouverte à la première exécution en échec, elle est résolue automatiquement à la
 * publication réussie suivante, ou à la main depuis l'écran « Alertes ».
 */
@Getter
@Setter
@Entity
public class ParserAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne
    private Parser parser;

    @Enumerated(EnumType.STRING)
    private AlertType type;

    private Instant raisedAt;

    /** {@code null} tant que l'alerte est ouverte. */
    private Instant resolvedAt;

    @Column(columnDefinition = "LONGTEXT")
    private String message;
}
