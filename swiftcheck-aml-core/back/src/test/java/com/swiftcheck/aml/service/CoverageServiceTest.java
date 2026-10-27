package com.swiftcheck.aml.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.dto.CoverageDTO;
import com.swiftcheck.aml.dto.CoverageDTO.Status;
import com.swiftcheck.aml.entities.Completeness;
import com.swiftcheck.aml.entities.Country;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.PepCategory;
import com.swiftcheck.aml.entities.PepCategoryKind;
import com.swiftcheck.aml.entities.PepFunction;
import com.swiftcheck.aml.entities.PepFunctionParser;
import com.swiftcheck.aml.repositories.CountryRepository;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.repositories.PepCategoryRepository;
import com.swiftcheck.aml.repositories.PepFunctionRepository;

/**
 * Page « Couverture PEP » : catégorie LU-b avec trois fonctions (Député couvert par un parser complet publié,
 * Conseil d'État couvert seulement par un parser partiel publié, Ancien député sans parser) et catégorie LU-c
 * couverte par un parser jamais exécuté.
 */
class CoverageServiceTest {

    @Test
    void functionStatusDependsOnPublishedParsersAndTheirCompleteness() {
        Country lu = new Country();
        lu.setIso("LU");
        lu.setName("Luxembourg");
        lu.setRetentionMonths(12);

        PepCategory parliament = category(1, lu, "LU-b");
        PepCategory parties = category(2, lu, "LU-c");
        PepFunction deputy = function(10, parliament, "Député");
        PepFunction councillor = function(11, parliament, "Membre du Conseil d'État");
        PepFunction formerDeputy = function(13, parliament, "Ancien député");
        PepFunction partyLeader = function(12, parties, "Dirigeant de parti");

        Parser chamber = parser(5, "lu_pep_chamber");
        chamber.replaceCoverage(List.of(new PepFunctionParser(deputy, Completeness.COMPLETE, false)));
        Parser gazette = parser(7, "lu_pep_legilux");
        gazette.replaceCoverage(List.of(
                new PepFunctionParser(deputy, Completeness.PARTIAL, true),
                new PepFunctionParser(councillor, Completeness.PARTIAL, true)));
        Parser draft = parser(6, "lu_pep_parties");                       // jamais exécuté
        draft.replaceCoverage(List.of(new PepFunctionParser(partyLeader, Completeness.COMPLETE, false)));

        CountryRepository countries = mock(CountryRepository.class);
        when(countries.findById("LU")).thenReturn(Optional.of(lu));
        PepCategoryRepository categories = mock(PepCategoryRepository.class);
        when(categories.findByCountryIsoOrderBySortOrderAscIdAsc("LU")).thenReturn(List.of(parliament, parties));
        PepFunctionRepository functions = mock(PepFunctionRepository.class);
        when(functions.findByCategoryCountryIsoOrderByCategorySortOrderAscIdAsc("LU"))
                .thenReturn(List.of(deputy, councillor, formerDeputy, partyLeader));
        ParserRepository parsers = mock(ParserRepository.class);
        when(parsers.findAll()).thenReturn(List.of(chamber, gazette, draft));
        ParserExecutionRepository executions = mock(ParserExecutionRepository.class);
        when(executions.findFirstByParserIdOrderByStartedAtDesc(anyLong())).thenReturn(Optional.empty());
        when(executions.findFirstByParserIdAndStatusInOrderByStartedAtDesc(anyLong(), anyCollection()))
                .thenReturn(Optional.empty());
        published(executions, 5L, 60);
        published(executions, 7L, 40);

        CoverageService service = new CoverageService(new PepReferenceService(countries, categories, functions),
                parsers, executions);
        CoverageDTO coverage = service.coverage("lu");

        assertEquals(2, coverage.officialCategories());
        assertEquals(0, coverage.coveredOfficialCategories(), "LU-b n'est que partiellement couverte");
        // Chaque parser compté une fois, même s'il couvre plusieurs fonctions
        assertEquals(100, coverage.personCount());

        CoverageDTO.CategoryCoverage lub = coverage.categories().get(0);
        assertEquals(Status.PARTIAL, lub.status());

        CoverageDTO.FunctionCoverage deputies = lub.functions().get(0);
        assertEquals(Status.COVERED, deputies.status());
        assertEquals(2, deputies.parsers().size());
        CoverageDTO.CoveringParser fromGazette = deputies.parsers().stream()
                .filter(p -> p.datasetName().equals("lu_pep_legilux")).findFirst().orElseThrow();
        assertEquals(Completeness.PARTIAL, fromGazette.completeness());
        assertTrue(fromGazette.includesFormer());

        assertEquals(Status.PARTIAL, lub.functions().get(1).status(), "seul un parser partiel couvre le Conseil d'État");
        assertEquals(Status.NONE, lub.functions().get(2).status());
        assertTrue(lub.functions().get(2).parsers().isEmpty());

        // Un parser déclaré mais qui n'a jamais publié ne couvre rien
        CoverageDTO.CategoryCoverage luc = coverage.categories().get(1);
        assertEquals(Status.NONE, luc.status());
        assertEquals(1, luc.functions().get(0).parsers().size());
        assertFalse(luc.functions().get(0).parsers().get(0).includesFormer());
    }

    @Test
    void disabledCategoriesAreLeftOutOfTheCoverage() {
        Country lu = new Country();
        lu.setIso("LU");
        PepCategory parliament = category(1, lu, "LU-b");
        PepCategory parties = category(2, lu, "LU-c");
        parties.setActive(false);

        CountryRepository countries = mock(CountryRepository.class);
        when(countries.findById("LU")).thenReturn(Optional.of(lu));
        PepCategoryRepository categories = mock(PepCategoryRepository.class);
        when(categories.findByCountryIsoOrderBySortOrderAscIdAsc("LU")).thenReturn(List.of(parliament, parties));
        PepFunctionRepository functions = mock(PepFunctionRepository.class);
        when(functions.findByCategoryCountryIsoOrderByCategorySortOrderAscIdAsc("LU"))
                .thenReturn(List.of(function(10, parliament, "Député"), function(12, parties, "Dirigeant de parti")));

        CoverageDTO coverage = new CoverageService(new PepReferenceService(countries, categories, functions),
                mock(ParserRepository.class), mock(ParserExecutionRepository.class)).coverage("LU");

        assertEquals(1, coverage.officialCategories());
        assertEquals(List.of("LU-b"), coverage.categories().stream().map(CoverageDTO.CategoryCoverage::code).toList());
    }

    @Test
    void replaceCoverageUpdatesExistingLinksInPlace() {
        PepCategory parliament = category(1, new Country(), "LU-b");
        PepFunction deputy = function(10, parliament, "Député");
        PepFunction councillor = function(11, parliament, "Membre du Conseil d'État");
        Parser parser = parser(5, "lu_pep_chamber");
        parser.replaceCoverage(List.of(new PepFunctionParser(deputy, Completeness.COMPLETE, false)));
        PepFunctionParser original = parser.getCoverage().get(0);

        parser.replaceCoverage(List.of(
                new PepFunctionParser(deputy, Completeness.PARTIAL, true),
                new PepFunctionParser(councillor, null, false)));

        assertEquals(2, parser.getCoverage().size());
        assertTrue(parser.getCoverage().get(0) == original, "le lien existant est gardé, pas recréé");
        assertEquals(Completeness.PARTIAL, original.getCompleteness());
        assertTrue(original.isIncludesFormer());
        assertEquals(Completeness.COMPLETE, parser.getCoverage().get(1).getCompleteness());
        assertEquals(5L, parser.getCoverage().get(1).getId().getParserId());

        parser.replaceCoverage(List.of());
        assertTrue(parser.getCoverage().isEmpty());
    }

    private static void published(ParserExecutionRepository executions, long parserId, int persons) {
        ParserExecution published = new ParserExecution();
        published.setStatus(ParserExecutionStatus.SUCCESS);
        published.setStartedAt(Instant.parse("2026-10-18T08:00:00Z"));
        published.setPersonCount(persons);
        when(executions.findFirstByParserIdOrderByStartedAtDesc(parserId)).thenReturn(Optional.of(published));
        when(executions.findFirstByParserIdAndStatusInOrderByStartedAtDesc(eq(parserId), anyCollection()))
                .thenReturn(Optional.of(published));
    }

    private static PepCategory category(long id, Country country, String code) {
        PepCategory category = new PepCategory();
        category.setId(id);
        category.setCountry(country);
        category.setCode(code);
        category.setLabel(code);
        category.setKind(PepCategoryKind.OFFICIAL);
        category.setRiskClass(1);
        return category;
    }

    private static PepFunction function(long id, PepCategory category, String label) {
        PepFunction function = new PepFunction();
        function.setId(id);
        function.setCategory(category);
        function.setLabel(label);
        return function;
    }

    private static Parser parser(long id, String dataset) {
        Parser parser = new Parser();
        parser.setId(id);
        parser.setName(dataset);
        parser.setDatasetName(dataset);
        return parser;
    }
}
