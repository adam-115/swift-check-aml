package com.swiftcheck.aml.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.PepSource;

@Repository
public interface PepSourceRepository extends JpaRepository<PepSource, Long> {

    /** Sources d'un pays, par nom. */
    List<PepSource> findByCountryIsoOrderByNameAsc(String countryIso);

    /** Toutes les sources, par pays puis par nom (formulaire du parser). */
    List<PepSource> findAllByOrderByCountryIsoAscNameAsc();
}
