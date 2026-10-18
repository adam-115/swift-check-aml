package com.swiftcheck.aml.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.swiftcheck.aml.entities.RetainedEntity;

@Repository
public interface RetainedEntityRepository extends JpaRepository<RetainedEntity, Long> {

    /** Entités gardées d'un parser, les plus récemment retirées d'abord. */
    List<RetainedEntity> findByParserIdOrderByRemovedAtDesc(long parserId);

    /** Rétention désactivée sur un parser : on oublie ses entités gardées. */
    @Transactional
    void deleteByParserId(long parserId);
}
