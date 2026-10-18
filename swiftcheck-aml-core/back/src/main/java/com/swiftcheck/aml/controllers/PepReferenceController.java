package com.swiftcheck.aml.controllers;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.dto.CountryDTO;
import com.swiftcheck.aml.dto.CoverageDTO;
import com.swiftcheck.aml.dto.PepCategoryDTO;
import com.swiftcheck.aml.dto.PepFunctionDTO;
import com.swiftcheck.aml.entities.Country;
import com.swiftcheck.aml.entities.PepCategory;
import com.swiftcheck.aml.entities.PepFunction;
import com.swiftcheck.aml.service.CoverageService;
import com.swiftcheck.aml.service.PepReferenceService;

/**
 * Référentiel PEP et couverture.
 * <pre>
 * GET    /api/pep/countries                     pays couverts
 * PUT    /api/pep/countries/{iso}               base légale et rétention du pays
 * GET    /api/pep/countries/{iso}/categories    catégories de la liste officielle
 * GET    /api/pep/countries/{iso}/coverage      page « Couverture PEP »
 * POST   /api/pep/categories                    création d'une catégorie
 * PUT    /api/pep/categories/{id}
 * DELETE /api/pep/categories/{id}               refusée si elle a encore des fonctions
 * GET    /api/pep/functions?country=LU          fonctions (toutes si country est absent)
 * POST   /api/pep/functions                     création d'une fonction
 * PUT    /api/pep/functions/{id}
 * DELETE /api/pep/functions/{id}                refusée si un parser la couvre encore
 * </pre>
 * Les modifications sont réservées aux administrateurs (SecurityConfig).
 */
@RestController
@RequestMapping("/api/pep")
public class PepReferenceController {

    private final PepReferenceService reference;
    private final CoverageService coverage;

    public PepReferenceController(PepReferenceService reference, CoverageService coverage) {
        this.reference = reference;
        this.coverage = coverage;
    }

    // ------------------------------------------------------------------ pays

    @GetMapping("/countries")
    public List<CountryDTO> countries() {
        return reference.findCountries().stream().map(CountryDTO::from).toList();
    }

    @PutMapping("/countries/{iso}")
    public CountryDTO updateCountry(@PathVariable String iso, @RequestBody CountryDTO dto) {
        Country values = new Country();
        values.setLegalBasis(dto.legalBasis());
        values.setRetentionMonths(dto.retentionMonths());
        values.setOfficialListReference(dto.officialListReference());
        values.setOfficialListDate(dto.officialListDate());
        values.setOfficialListUrl(dto.officialListUrl());
        return CountryDTO.from(reference.updateCountry(iso, values));
    }

    @GetMapping("/countries/{iso}/categories")
    public List<PepCategoryDTO> categories(@PathVariable String iso) {
        return reference.findCategories(iso).stream().map(PepCategoryDTO::from).toList();
    }

    @GetMapping("/countries/{iso}/coverage")
    public CoverageDTO coverage(@PathVariable String iso) {
        return coverage.coverage(iso);
    }

    // ------------------------------------------------------------------ catégories

    @PostMapping("/categories")
    public PepCategoryDTO createCategory(@RequestBody PepCategoryDTO dto) {
        return PepCategoryDTO.from(reference.saveCategory(0, dto.getCountryIso(), toCategory(dto)));
    }

    @PutMapping("/categories/{id}")
    public PepCategoryDTO updateCategory(@PathVariable long id, @RequestBody PepCategoryDTO dto) {
        return PepCategoryDTO.from(reference.saveCategory(id, dto.getCountryIso(), toCategory(dto)));
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable long id) {
        reference.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ fonctions

    @GetMapping("/functions")
    public List<PepFunctionDTO> functions(@RequestParam(required = false) String country) {
        return reference.findFunctions(country).stream().map(PepFunctionDTO::from).toList();
    }

    @PostMapping("/functions")
    public PepFunctionDTO createFunction(@RequestBody PepFunctionDTO dto) {
        return PepFunctionDTO.from(reference.saveFunction(0, dto.getCategoryId(), toFunction(dto)));
    }

    @PutMapping("/functions/{id}")
    public PepFunctionDTO updateFunction(@PathVariable long id, @RequestBody PepFunctionDTO dto) {
        return PepFunctionDTO.from(reference.saveFunction(id, dto.getCategoryId(), toFunction(dto)));
    }

    @DeleteMapping("/functions/{id}")
    public ResponseEntity<Void> deleteFunction(@PathVariable long id) {
        reference.deleteFunction(id);
        return ResponseEntity.noContent().build();
    }

    private static PepCategory toCategory(PepCategoryDTO dto) {
        PepCategory category = new PepCategory();
        category.setCode(dto.getCode());
        category.setLabel(dto.getLabel());
        category.setKind(dto.getKind());
        category.setRiskClass(dto.getRiskClass());
        category.setSortOrder(dto.getSortOrder());
        category.setLegalReference(dto.getLegalReference());
        return category;
    }

    private static PepFunction toFunction(PepFunctionDTO dto) {
        PepFunction function = new PepFunction();
        function.setLabel(dto.getLabel());
        function.setInstitution(dto.getInstitution());
        function.setExpectedHolders(dto.getExpectedHolders());
        return function;
    }
}
