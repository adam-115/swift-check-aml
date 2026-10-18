package com.swiftcheck.aml.service;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.Judgement;
import com.swiftcheck.aml.entities.ResolutionJudgement;
import com.swiftcheck.aml.repositories.ResolutionJudgementRepository;

/**
 * Rapprochements entre entités de datasets différents (même personne, personnes différentes, incertain).
 * Première brique de la résolution d'entités : les décisions sont enregistrées ; leur utilisation pour
 * fusionner les profils dans Yente viendra ensuite.
 */
@Service
public class ResolutionJudgementService {

    private final ResolutionJudgementRepository judgements;

    public ResolutionJudgementService(ResolutionJudgementRepository judgements) {
        this.judgements = judgements;
    }

    public Page<ResolutionJudgement> findAll(Pageable pageable) {
        return judgements.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * Enregistre une décision. Si le couple a déjà été jugé, la décision est remplacée.
     * Le couple est rangé dans l'ordre alphabétique : (a, b) et (b, a) sont le même rapprochement.
     */
    public ResolutionJudgement decide(String firstId, String secondId, Judgement judgement, String decidedBy) {
        if (firstId == null || firstId.isBlank() || secondId == null || secondId.isBlank()) {
            throw new IllegalArgumentException("Les deux identifiants d'entité sont obligatoires.");
        }
        String first = firstId.trim();
        String second = secondId.trim();
        if (first.equals(second)) {
            throw new IllegalArgumentException("Une entité ne peut pas être rapprochée d'elle-même.");
        }
        if (judgement == null) {
            throw new IllegalArgumentException("Décision obligatoire : POSITIVE, NEGATIVE ou UNSURE.");
        }
        String left = first.compareTo(second) < 0 ? first : second;
        String right = first.compareTo(second) < 0 ? second : first;

        ResolutionJudgement entity = judgements.findByLeftEntityIdAndRightEntityId(left, right)
                .orElseGet(ResolutionJudgement::new);
        entity.setLeftEntityId(left);
        entity.setRightEntityId(right);
        entity.setJudgement(judgement);
        entity.setDecidedBy(decidedBy);
        entity.setCreatedAt(Instant.now());
        return judgements.save(entity);
    }

    public void delete(long id) {
        judgements.deleteById(id);
    }
}
