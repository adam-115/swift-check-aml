package com.swiftcheck.aml.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.Country;

@Repository
public interface CountryRepository extends JpaRepository<Country, String> {

    /** Pays triés par nom, pour les listes déroulantes. */
    List<Country> findAllByOrderByNameAsc();
}
