package com.swiftcheck.aml.repositories;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.ResolutionJudgement;

@Repository
public interface ResolutionJudgementRepository extends JpaRepository<ResolutionJudgement, Long> {

    Page<ResolutionJudgement> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Décision déjà prise sur ce couple (rangé dans l'ordre alphabétique). */
    Optional<ResolutionJudgement> findByLeftEntityIdAndRightEntityId(String leftEntityId, String rightEntityId);
}
