package com.swiftcheck.aml.parsertest;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.swiftcheck.aml.dto.ParserExecutionDTO;
import com.swiftcheck.aml.dto.ParserTestDTO;
import com.swiftcheck.aml.dto.ParserTestPreviewDTO;
import com.swiftcheck.aml.dto.SourceFileDTO;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.ParserTest;
import com.swiftcheck.aml.entities.ParserTestStatus;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.repositories.ParserTestRepository;
import com.swiftcheck.aml.repositories.SourceFileRepository;
import com.swiftcheck.aml.service.PluginManagementService;

/**
 * Lecture des tests pour l'interface : chaque test est accompagné de la publication testée et de son
 * fichier source (nom, empreinte, date de téléchargement). Aucune écriture, sans état.
 */
@Component
public class ParserTestViews {

    private final ParserRepository parsers;
    private final ParserExecutionRepository executions;
    private final SourceFileRepository sourceFiles;
    private final ParserTestRepository tests;
    private final PluginManagementService plugins;

    public ParserTestViews(ParserRepository parsers, ParserExecutionRepository executions,
            SourceFileRepository sourceFiles, ParserTestRepository tests, PluginManagementService plugins) {
        this.parsers = parsers;
        this.executions = executions;
        this.sourceFiles = sourceFiles;
        this.tests = tests;
        this.plugins = plugins;
    }

    /** Le test, avec sa publication et ses fichiers source. */
    public ParserTestDTO toDto(ParserTest test) {
        if (test.getExecutionId() == null) {
            return ParserTestDTO.from(test, null, List.of());
        }
        ParserExecution publication = executions.findById(test.getExecutionId()).orElse(null);
        return ParserTestDTO.from(test, publication, filesOf(test.getExecutionId()));
    }

    /**
     * Fenêtre de confirmation : quelle publication et quel fichier seront testés, et ce qui empêcherait le test.
     *
     * @throws IllegalArgumentException si le parser n'existe pas
     */
    public ParserTestPreviewDTO preview(long parserId) {
        Parser parser = parsers.findById(parserId)
                .orElseThrow(() -> new IllegalArgumentException("Parser introuvable : " + parserId));
        Optional<ParserExecution> publication = executions
                .findFirstByParserIdAndStatusOrderByStartedAtDesc(parserId, ParserExecutionStatus.SUCCESS);
        List<SourceFileDTO> files = publication.map(p -> filesOf(p.getId())).orElse(List.of());
        ParserTestDTO lastTest = tests.findByParserIdOrderByStartedAtDesc(parserId, PageRequest.of(0, 1))
                .stream().findFirst().map(this::toDto).orElse(null);
        boolean pluginLoaded = plugins.listPlugins().stream().anyMatch(p -> p.pluginId().equals(parser.getPluginId()));

        // Même ordre de vérification que ParserTestService.run, pour afficher le même message.
        String blocker = null;
        if (tests.existsByParserIdAndStatus(parserId, ParserTestStatus.RUNNING)) {
            blocker = "Un test de ce parser est déjà en cours.";
        } else if (publication.isEmpty()) {
            blocker = "Aucune publication réussie : lancez d'abord le parser, puis testez.";
        } else if (files.isEmpty()) {
            blocker = "La dernière publication n'a pas de fichier source archivé.";
        } else if (!pluginLoaded && parser.getPluginUrl() == null) {
            blocker = "Plugin « " + parser.getPluginId() + " » non chargé : indiquez le chemin de son JAR et cliquez sur « Vérifier ».";
        }
        return new ParserTestPreviewDTO(parser.getId(), parser.getName(), parser.getDatasetName(), parser.getPluginId(),
                pluginLoaded, publication.map(ParserExecutionDTO::from).orElse(null), files, lastTest, blocker);
    }

    private List<SourceFileDTO> filesOf(long executionId) {
        return sourceFiles.findByExecutionIdOrderByIdAsc(executionId).stream().map(SourceFileDTO::from).toList();
    }
}
