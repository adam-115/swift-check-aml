package com.swiftcheck.aml.parsertest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import com.swiftcheck.aml.dto.ParserTestPreviewDTO;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.SourceFile;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.repositories.ParserTestRepository;
import com.swiftcheck.aml.repositories.SourceFileRepository;
import com.swiftcheck.aml.service.PluginManagementService;
import com.swiftcheck.aml.service.PluginManagementService.PluginInfo;

/** Fenêtre de confirmation du test : fichier source de la publication testée et raisons de blocage. */
class ParserTestViewsTest {

    private final ParserRepository parsers = mock(ParserRepository.class);
    private final ParserExecutionRepository executions = mock(ParserExecutionRepository.class);
    private final SourceFileRepository files = mock(SourceFileRepository.class);
    private final ParserTestRepository tests = mock(ParserTestRepository.class);
    private final PluginManagementService plugins = mock(PluginManagementService.class);
    private final ParserTestViews views = new ParserTestViews(parsers, executions, files, tests, plugins);

    ParserTestViewsTest() {
        Parser parser = new Parser();
        parser.setId(7);
        parser.setName("Sanctions UE");
        parser.setDatasetName("eu_sanctions");
        parser.setPluginId("eu-parser");
        when(parsers.findById(7L)).thenReturn(Optional.of(parser));
        when(tests.findByParserIdOrderByStartedAtDesc(anyLong(), any())).thenReturn(Page.empty());
        when(plugins.listPlugins()).thenReturn(List.of(new PluginInfo("eu-parser", "0.0.1", "STARTED", "/p/eu.jar")));
    }

    @Test
    void sansPublicationLeTestEstBloque() {
        when(executions.findFirstByParserIdAndStatusOrderByStartedAtDesc(7, ParserExecutionStatus.SUCCESS))
                .thenReturn(Optional.empty());
        ParserTestPreviewDTO preview = views.preview(7);
        assertNull(preview.publication());
        assertTrue(preview.blocker().contains("Aucune publication"), preview.blocker());
    }

    @Test
    void publicationAvecFichierSourcePret() {
        ParserExecution publication = new ParserExecution();
        publication.setId(42);
        publication.setStatus(ParserExecutionStatus.SUCCESS);
        publication.setEntityCount(11200);        publication.setParser(parsers.findById(7L).get());
        when(executions.findFirstByParserIdAndStatusOrderByStartedAtDesc(7, ParserExecutionStatus.SUCCESS))
                .thenReturn(Optional.of(publication));
        SourceFile file = new SourceFile();
        file.setOrigin("https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content");
        file.setSha256("ab".repeat(32));
        file.setSizeBytes(24_000_000);
        file.setFetchedAt(Instant.parse("2026-10-16T08:00:00Z"));
        when(files.findByExecutionIdOrderByIdAsc(42)).thenReturn(List.of(file));

        ParserTestPreviewDTO preview = views.preview(7);
        assertNull(preview.blocker());
        assertTrue(preview.pluginLoaded());
        assertEquals(42, preview.publication().id());
        assertEquals(1, preview.sourceFiles().size());
        assertEquals(Instant.parse("2026-10-16T08:00:00Z"), preview.sourceFiles().get(0).fetchedAt());
    }
}
