package com.swiftcheck.aml.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.swiftcheck.aml.entities.PepFunctionSource;

@Repository
public interface PepFunctionSourceRepository extends JpaRepository<PepFunctionSource, Long> {

    /** Fonctions couvertes par une source. */
    List<PepFunctionSource> findBySourceIdOrderByFunctionCategorySortOrderAscFunctionIdAsc(long sourceId);

    /** Tous les liens fonction-source d'un pays (page « Couverture PEP »). */
    List<PepFunctionSource> findBySourceCountryIso(String countryIso);

    /**
     * Supprime les liens d'une source, tout de suite en base : les nouveaux liens peuvent ensuite être
     * enregistrés sans heurter la contrainte d'unicité (fonction, source).
     */
    @Modifying
    @Transactional
    @Query("delete from PepFunctionSource l where l.source.id = :sourceId")
    void deleteBySourceId(@Param("sourceId") long sourceId);
}
