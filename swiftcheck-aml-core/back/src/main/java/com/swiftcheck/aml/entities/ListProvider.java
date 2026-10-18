package com.swiftcheck.aml.entities;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.Setter;

/**
 * Organisme qui publie une ou plusieurs listes : Union européenne, ONU, OFAC, État luxembourgeois…
 * Un fournisseur peut avoir plusieurs parsers (ex. OFAC : liste SDN et liste hors SDN), voir {@link Parser}.
 */
@Getter
@Setter
@Entity
public class ListProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Code court et unique, ex. {@code EU}, {@code UN}, {@code OFAC}. */
    @Column(unique = true, nullable = false)
    private String code;

    private String name;

    private String description;

    /**
     * Pays de l'organisme (code ISO en majuscules, ex. {@code LU}, clé de {@link Country}),
     * vide pour une organisation internationale (UE, ONU).
     */
    @Column(name = "country_iso", length = 2)
    private String country;

    /** Nature des listes publiées : sanctions (AML), PEP, médias, criminalité. */
    @Enumerated(EnumType.STRING)
    private ListType listType;

    /** Site officiel de l'organisme (information pour l'utilisateur, pas l'URL de téléchargement). */
    private String websiteUrl;

    @Lob
    @Column(columnDefinition = "MEDIUMBLOB")
    private byte[] logo;

    private boolean enabled = true;

    private Instant createdAt = Instant.now();
}
