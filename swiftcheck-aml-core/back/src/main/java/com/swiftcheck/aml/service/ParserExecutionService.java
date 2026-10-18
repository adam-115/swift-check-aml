package com.swiftcheck.aml.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.ChangeType;
import com.swiftcheck.aml.entities.DatasetChange;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.SourceFile;
import com.swiftcheck.aml.repositories.DatasetChangeRepository;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.SourceFileRepository;

/**
 * Lecture de l'historique des exécutions. Les exécutions sont créées uniquement par
 * {@code IngestionService} : elles ne se créent pas et ne se modifient pas depuis l'API.
 */
@Service
public class ParserExecutionService {

    private final ParserExecutionRepository executions;
    private final SourceFileRepository sourceFiles;
    private final DatasetChangeRepository changes;

    public ParserExecutionService(ParserExecutionRepository executions, SourceFileRepository sourceFiles,
                                  DatasetChangeRepository changes) {
        this.executions = executions;
        this.sourceFiles = sourceFiles;
        this.changes = changes;
    }

    public Page<ParserExecution> findAll(Pageable pageable) {
        return executions.findAllByOrderByStartedAtDesc(pageable);
    }

    public Optional<ParserExecution> findById(long id) {
        return executions.findById(id);
    }

    public Page<ParserExecution> findByParser(long parserId, Pageable pageable) {
        return executions.findByParserIdOrderByStartedAtDesc(parserId, pageable);
    }

    /** Dernière exécution d'un parser, pour l'afficher dans la liste des parsers. */
    public Optional<ParserExecution> findLast(long parserId) {
        return executions.findFirstByParserIdOrderByStartedAtDesc(parserId);
    }

    /** Fichiers sources signés et archivés lors de l'exécution. */
    public List<SourceFile> findSourceFiles(long executionId) {
        return sourceFiles.findByExecutionIdOrderByIdAsc(executionId);
    }

    /** Changements publiés par l'exécution, tous ou d'un seul type (ADDED, REMOVED, MODIFIED). */
    public Page<DatasetChange> findChanges(long executionId, ChangeType type, Pageable pageable) {
        return type == null
                ? changes.findByExecutionIdOrderByIdAsc(executionId, pageable)
                : changes.findByExecutionIdAndChangeTypeOrderByIdAsc(executionId, type, pageable);
    }
}
