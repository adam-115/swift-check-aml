package com.swiftcheck.aml.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.swiftcheck.aml.entities.PepCategory;

@Repository
public interface PepCategoryRepository extends JpaRepository<PepCategory, Long> {

    /** Catégories d'un pays, dans l'ordre de la liste officielle. */
    List<PepCategory> findByCountryIsoOrderBySortOrderAscIdAsc(String countryIso);

    boolean existsByCodeAndIdNot(String code, long id);
}
