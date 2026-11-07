package com.swiftcheck.aml.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.ParserTest;
import com.swiftcheck.aml.entities.ParserTestStatus;

@Repository
public interface ParserTestRepository extends JpaRepository<ParserTest, Long> {

    Page<ParserTest> findAllByOrderByStartedAtDesc(Pageable pageable);

    Page<ParserTest> findByParserIdOrderByStartedAtDesc(long parserId, Pageable pageable);

    /** Dernier test de chacun des parsers donnés, en une seule requête (liste des parsers). */
    @org.springframework.data.jpa.repository.Query("select t from ParserTest t where t.parser.id in :ids and t.id = (select max(x.id) from ParserTest x where x.parser.id = t.parser.id)")
    List<ParserTest> findLastOfParsers(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    /** Dernier test d'un parser, affiché dans la liste des parsers. */
    Optional<ParserTest> findFirstByParserIdOrderByStartedAtDesc(long parserId);

    /** Vrai si un test de ce parser est déjà en cours (deux tests simultanés sont refusés). */
    boolean existsByParserIdAndStatus(long parserId, ParserTestStatus status);

    /** Au démarrage : tests restés RUNNING après un arrêt brutal. */
    List<ParserTest> findByStatus(ParserTestStatus status);
}
