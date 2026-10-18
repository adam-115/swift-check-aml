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

/** Un élément du fichier source non retrouvé dans Yente lors d'un {@link ParserTest}. */
@Getter
@Setter
@Entity
public class ParserTestFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Test concerné (identifiant simple, sans relation JPA). */
    private long testId;

    /** ID FtM attendu, ex. {@code eu-fsd-13}. */
    private String entityId;

    /** Schéma FtM attendu ("schema" est un mot réservé en MySQL). */
    private String entitySchema;

    /** Nom tel qu'écrit dans la source. */
    @Column(length = 1024)
    private String name;

    /** Référence dans la source, ex. {@code logicalId=13, EU.27.28}. */
    @Column(length = 512)
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    private TestFailureReason reason;

    /** Précision : meilleur résultat renvoyé par Yente, code HTTP… */
    @Column(columnDefinition = "TEXT")
    private String detail;
}
