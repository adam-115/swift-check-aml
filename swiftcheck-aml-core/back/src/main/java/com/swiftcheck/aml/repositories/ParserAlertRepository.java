package com.swiftcheck.aml.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.AlertType;
import com.swiftcheck.aml.entities.ParserAlert;

@Repository
public interface ParserAlertRepository extends JpaRepository<ParserAlert, Long> {

    /** Alertes ouvertes, les plus récentes d'abord. */
    List<ParserAlert> findByResolvedAtIsNullOrderByRaisedAtDesc();

    /** Toutes les alertes (historique), les plus récentes d'abord. */
    Page<ParserAlert> findAllByOrderByRaisedAtDesc(Pageable pageable);

    long countByResolvedAtIsNull();

    /** Alerte ouverte d'un type donné sur un parser : une seule à la fois, pour ne pas en créer à chaque exécution. */
    Optional<ParserAlert> findFirstByParserIdAndTypeAndResolvedAtIsNull(long parserId, AlertType type);

    /** Alertes ouvertes d'un parser. */
    List<ParserAlert> findByParserIdAndResolvedAtIsNull(long parserId);
}
