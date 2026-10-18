package com.swiftcheck.aml.entities;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/**
 * Rapprochement décidé par un analyste entre deux entités de datasets différents, ex. le député
 * {@code lu-pep-chamber-...} et le bourgmestre {@code lu-pep-municipal-...} sont la même personne.
 * La décision est gardée en base parce qu'elle doit survivre aux exécutions des parsers.
 * Le couple est rangé dans l'ordre alphabétique : (a, b) et (b, a) sont le même rapprochement.
 */
@Getter
@Setter
@Entity
public class ResolutionJudgement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String leftEntityId;

    private String rightEntityId;

    @Enumerated(EnumType.STRING)
    private Judgement judgement;

    /** Nom de l'utilisateur qui a décidé. */
    private String decidedBy;

    private Instant createdAt;
}
