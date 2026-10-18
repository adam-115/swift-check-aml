package com.swiftcheck.aml.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;

@Repository
public interface ParserExecutionRepository extends JpaRepository<ParserExecution, Long> {

    /** Historique d'un parser, du plus récent au plus ancien. */
    Page<ParserExecution> findByParserIdOrderByStartedAtDesc(long parserId, Pageable pageable);

    /** Toutes les exécutions, de la plus récente à la plus ancienne. */
    Page<ParserExecution> findAllByOrderByStartedAtDesc(Pageable pageable);

    /** Dernière exécution d'un parser (quel que soit son statut). */
    Optional<ParserExecution> findFirstByParserIdOrderByStartedAtDesc(long parserId);

    /** Dernière exécution d'un parser avec un statut donné, ex. la dernière publication réussie. */
    Optional<ParserExecution> findFirstByParserIdAndStatusOrderByStartedAtDesc(long parserId,
            ParserExecutionStatus status);

    /** Dernière exécution d'un parser parmi plusieurs statuts, ex. la dernière publication (SUCCESS ou UNCHANGED). */
    Optional<ParserExecution> findFirstByParserIdAndStatusInOrderByStartedAtDesc(long parserId,
            Collection<ParserExecutionStatus> statuses);

    /** Vrai si le parser a déjà une exécution avec ce statut (sert à refuser deux exécutions simultanées). */
    boolean existsByParserIdAndStatus(long parserId, ParserExecutionStatus status);

    /** Exécutions dans un statut donné (au démarrage : celles restées RUNNING après un arrêt brutal). */
    List<ParserExecution> findByStatus(ParserExecutionStatus status);
}
