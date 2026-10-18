package com.swiftcheck.aml.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.PepFunction;

@Repository
public interface PepFunctionRepository extends JpaRepository<PepFunction, Long> {

    /** Fonctions d'un pays, rangées par catégorie. */
    List<PepFunction> findByCategoryCountryIsoOrderByCategorySortOrderAscIdAsc(String countryIso);

    /** Fonctions de toutes les catégories, rangées par pays puis par catégorie (formulaire du parser). */
    List<PepFunction> findAllByOrderByCategoryCountryIsoAscCategorySortOrderAscIdAsc();

    boolean existsByCategoryId(long categoryId);
}
