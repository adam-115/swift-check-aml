package com.swiftcheck.aml.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.Country;
import com.swiftcheck.aml.entities.PepCategory;
import com.swiftcheck.aml.entities.PepFunction;
import com.swiftcheck.aml.repositories.CountryRepository;
import com.swiftcheck.aml.repositories.PepCategoryRepository;
import com.swiftcheck.aml.repositories.PepFunctionRepository;

/**
 * Référentiel PEP : pays, catégories de la liste officielle et fonctions.
 * Rempli par Liquibase (002-pep-reference-data.xml), modifiable par un administrateur.
 */
@Service
public class PepReferenceService {

    private final CountryRepository countries;
    private final PepCategoryRepository categories;
    private final PepFunctionRepository functions;

    public PepReferenceService(CountryRepository countries, PepCategoryRepository categories,
            PepFunctionRepository functions) {
        this.countries = countries;
        this.categories = categories;
        this.functions = functions;
    }

    // ------------------------------------------------------------------ pays

    public List<Country> findCountries() {
        return countries.findAllByOrderByNameAsc();
    }

    public Country getCountry(String iso) {
        return countries.findById(normalizeIso(iso))
                .orElseThrow(() -> new IllegalArgumentException("Pays inconnu : " + iso));
    }

    /**
     * Modifie la base légale, la rétention et la référence de la liste officielle d'un pays
     * (le code et le nom ne changent pas).
     */
    public Country updateCountry(String iso, Country values) {
        if (values.getRetentionMonths() < 0) {
            throw new IllegalArgumentException("La rétention (mois) ne peut pas être négative.");
        }
        Country country = getCountry(iso);
        country.setLegalBasis(values.getLegalBasis());
        country.setRetentionMonths(values.getRetentionMonths());
        country.setOfficialListReference(values.getOfficialListReference());
        country.setOfficialListDate(values.getOfficialListDate());
        country.setOfficialListUrl(values.getOfficialListUrl());
        return countries.save(country);
    }

    // ------------------------------------------------------------------ catégories

    public List<PepCategory> findCategories(String countryIso) {
        return categories.findByCountryIsoOrderBySortOrderAscIdAsc(normalizeIso(countryIso));
    }

    /** Crée ({@code id == 0}) ou modifie une catégorie. */
    public PepCategory saveCategory(long id, String countryIso, PepCategory values) {
        PepCategory category = id == 0 ? new PepCategory() : categories.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Catégorie introuvable : " + id));
        if (values.getCode() == null || values.getCode().isBlank()) {
            throw new IllegalArgumentException("Code de catégorie obligatoire, ex. LU-b.");
        }
        if (values.getLabel() == null || values.getLabel().isBlank()) {
            throw new IllegalArgumentException("Libellé de catégorie obligatoire.");
        }
        if (values.getKind() == null) {
            throw new IllegalArgumentException("Nature de la catégorie obligatoire (OFFICIAL, AMLR, RISK_BASED, EU, RCA).");
        }
        if (values.getRiskClass() < 1 || values.getRiskClass() > 4) {
            throw new IllegalArgumentException("La classe de risque va de 1 (la plus élevée) à 4.");
        }
        if (categories.existsByCodeAndIdNot(values.getCode().trim(), id)) {
            throw new IllegalArgumentException("Le code « " + values.getCode().trim() + " » est déjà utilisé.");
        }
        category.setCountry(getCountry(countryIso));
        category.setCode(values.getCode().trim());
        category.setLabel(values.getLabel().trim());
        category.setKind(values.getKind());
        category.setRiskClass(values.getRiskClass());
        category.setSortOrder(values.getSortOrder());
        category.setLegalReference(values.getLegalReference());
        return categories.save(category);
    }

    /** Supprime une catégorie vide ; refusé si elle a encore des fonctions. */
    public void deleteCategory(long id) {
        if (functions.existsByCategoryId(id)) {
            throw new IllegalStateException("Cette catégorie a encore des fonctions : supprimez-les d'abord.");
        }
        categories.deleteById(id);
    }

    // ------------------------------------------------------------------ fonctions

    /** Fonctions d'un pays, ou de tous les pays si {@code countryIso} est vide. */
    public List<PepFunction> findFunctions(String countryIso) {
        if (countryIso == null || countryIso.isBlank()) {
            return functions.findAllByOrderByCategoryCountryIsoAscCategorySortOrderAscIdAsc();
        }
        return functions.findByCategoryCountryIsoOrderByCategorySortOrderAscIdAsc(normalizeIso(countryIso));
    }

    /**
     * Fonctions désignées par leurs identifiants (formulaire du parser).
     *
     * @throws IllegalArgumentException si un identifiant est inconnu
     */
    public Set<PepFunction> findFunctionsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new HashSet<>();
        }
        Set<PepFunction> found = new HashSet<>(functions.findAllById(ids));
        if (found.size() != new HashSet<>(ids).size()) {
            throw new IllegalArgumentException("Fonction PEP inconnue dans la liste des fonctions couvertes.");
        }
        return found;
    }

    /** Crée ({@code id == 0}) ou modifie une fonction. */
    public PepFunction saveFunction(long id, long categoryId, PepFunction values) {
        PepFunction function = id == 0 ? new PepFunction() : functions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fonction introuvable : " + id));
        if (values.getLabel() == null || values.getLabel().isBlank()) {
            throw new IllegalArgumentException("Libellé de fonction obligatoire, ex. « Député ».");
        }
        if (values.getExpectedHolders() != null && values.getExpectedHolders() < 0) {
            throw new IllegalArgumentException("Le nombre de titulaires ne peut pas être négatif.");
        }
        function.setCategory(categories.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Catégorie introuvable : " + categoryId)));
        function.setLabel(values.getLabel().trim());
        function.setInstitution(values.getInstitution());
        function.setExpectedHolders(values.getExpectedHolders());
        return functions.save(function);
    }

    /** Supprime une fonction ; la base refuse si un parser la couvre encore. */
    public void deleteFunction(long id) {
        functions.deleteById(id);
    }

    /** {@code lu} → {@code LU}. */
    private static String normalizeIso(String iso) {
        return iso == null ? null : iso.trim().toUpperCase();
    }
}
