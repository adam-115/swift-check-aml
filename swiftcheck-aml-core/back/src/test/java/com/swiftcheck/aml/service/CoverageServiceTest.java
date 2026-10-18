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
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.dto.CoverageDTO;
import com.swiftcheck.aml.dto.CoverageDTO.Status;
import com.swiftcheck.aml.entities.Completeness;
import com.swiftcheck.aml.entities.Country;
import com.swiftcheck.aml.entities.PepFunctionSource;
import com.swiftcheck.aml.entities.PepSource;
import com.swiftcheck.aml.entities.PepSourceStatus;
import com.swiftcheck.aml.entities.SourceAccess;
import com.swiftcheck.aml.repositories.PepFunctionSourceRepository;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.PepCategory;
import com.swiftcheck.aml.entities.PepCategoryKind;
import com.swiftcheck.aml.entities.PepFunction;
import com.swiftcheck.aml.repositories.CountryRepository;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.repositories.PepCategoryRepository;
import com.swiftcheck.aml.repositories.PepFunctionRepository;

/**
 * Page « Couverture PEP » : catégorie LU-b avec deux fonctions (Député couvert par un parser publié,
 * Conseil d'État sans parser) et catégorie LU-c sans parser.
 */
class CoverageServiceTest {

    @Test
    void functionIsCoveredOnlyByAnEnabledParserThatHasPublished() {
        Country lu = new Country();
        lu.setIso("LU");
        lu.setName("Luxembourg");
        lu.setRetentionMonths(12);

        PepCategory parliament = category(1, lu, "LU-b");
        PepCategory parties = category(2, lu, "LU-c");
        PepFunction deputy = function(10, parliament, "Député");
        PepFunction councillor = function(11, parliament, "Membre du Conseil d'État");
        PepFunction partyLeader = function(12, parties, "Dirigeant de parti");

        Parser chamber = parser(5, "lu_pep_chamber", deputy);
        Parser draft = parser(6, "lu_pep_parties", partyLeader);     // jamais exécuté

        ParserExecution published = new ParserExecution();
        published.setStatus(ParserExecutionStatus.SUCCESS);
        published.setStartedAt(Instant.parse("2026-10-18T08:00:00Z"));
        published.setPersonCount(60);

        CountryRepository countries = mock(CountryRepository.class);
        when(countries.findById("LU")).thenReturn(Optional.of(lu));
        PepCategoryRepository categories = mock(PepCategoryRepository.class);
        when(categories.findByCountryIsoOrderBySortOrderAscIdAsc("LU")).thenReturn(List.of(parliament, parties));
        PepFunctionRepository functions = mock(PepFunctionRepository.class);
        when(functions.findByCategoryCountryIsoOrderByCategorySortOrderAscIdAsc("LU"))
                .thenReturn(List.of(deputy, councillor, partyLeader));
        ParserRepository parsers = mock(ParserRepository.class);
        when(parsers.findAll()).thenReturn(List.of(chamber, draft));
        ParserExecutionRepository executions = mock(ParserExecutionRepository.class);
        when(executions.findFirstByParserIdOrderByStartedAtDesc(anyLong())).thenReturn(Optional.empty());
        when(executions.findFirstByParserIdAndStatusInOrderByStartedAtDesc(anyLong(), anyCollection()))
                .thenReturn(Optional.empty());
        when(executions.findFirstByParserIdOrderByStartedAtDesc(5L)).thenReturn(Optional.of(published));
        when(executions.findFirstByParserIdAndStatusInOrderByStartedAtDesc(eq(5L), anyCollection()))
                .thenReturn(Optional.of(published));

        // Sources : la Chambre (lue par lu_pep_chamber) et le site du Conseil d'État (pas encore de parser)
        PepSource chamberData = source(20, lu, "Chambre des Députés");
        PepSource councilSite = source(21, lu, "Conseil d'État");
        chamber.setSource(chamberData);
        PepFunctionSourceRepository functionSources = mock(PepFunctionSourceRepository.class);
        when(functionSources.findBySourceCountryIso("LU"))
                .thenReturn(List.of(link(deputy, chamberData), link(councillor, councilSite)));

        CoverageService service = new CoverageService(new PepReferenceService(countries, categories, functions),
                parsers, executions, functionSources);
        CoverageDTO coverage = service.coverage("lu");

        assertEquals(2, coverage.officialCategories());
        assertEquals(0, coverage.coveredOfficialCategories(), "LU-b n'est que partiellement couverte");
        assertEquals(60, coverage.personCount());

        CoverageDTO.CategoryCoverage lub = coverage.categories().get(0);
        assertEquals(Status.PARTIAL, lub.status());
        assertEquals(Status.COVERED, lub.functions().get(0).status());
        assertEquals("lu_pep_chamber", lub.functions().get(0).parsers().get(0).datasetName());
        assertEquals(Status.NONE, lub.functions().get(1).status());

        // Sources : celle de la Chambre est implémentée, celle du Conseil d'État est le prochain parser à écrire
        assertTrue(lub.functions().get(0).sources().get(0).implemented());
        CoverageDTO.SourceRef next = lub.functions().get(1).sources().get(0);
        assertEquals("Conseil d'État", next.name());
        assertFalse(next.implemented());

        // Un parser déclaré mais qui n'a jamais publié ne couvre rien
        CoverageDTO.CategoryCoverage luc = coverage.categories().get(1);
        assertEquals(Status.NONE, luc.status());
        assertEquals(1, luc.functions().get(0).parsers().size());
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

    private static PepSource source(long id, Country country, String name) {
        PepSource source = new PepSource();
        source.setId(id);
        source.setCountry(country);
        source.setName(name);
        source.setAccess(SourceAccess.OPEN);
        source.setStatus(PepSourceStatus.CANDIDATE);
        return source;
    }

    private static PepFunctionSource link(PepFunction function, PepSource source) {
        PepFunctionSource link = new PepFunctionSource();
        link.setFunction(function);
        link.setSource(source);
        link.setCompleteness(Completeness.COMPLETE);
        return link;
    }

    private static Parser parser(long id, String dataset, PepFunction... covered) {
        Parser parser = new Parser();
        parser.setId(id);
        parser.setName(dataset);
        parser.setDatasetName(dataset);
        parser.setCoveredFunctions(Set.of(covered));
        return parser;
    }
}
