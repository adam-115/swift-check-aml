package com.swiftcheck.aml.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.Parser;

@Repository
public interface ParserRepository extends JpaRepository<Parser, Long> {

    /** Parsers actifs, parcourus par le planificateur. */
    List<Parser> findByEnabledTrue();

    boolean existsByDatasetNameAndIdNot(String datasetName, long id);
}
