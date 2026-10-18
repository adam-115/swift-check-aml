package com.swiftcheck.aml.controllers;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.dto.DatasetChangeDTO;
import com.swiftcheck.aml.dto.ParserExecutionDTO;
import com.swiftcheck.aml.dto.SourceFileDTO;
import com.swiftcheck.aml.entities.ChangeType;
import com.swiftcheck.aml.service.ParserExecutionService;

/**
 * Historique des exécutions (lecture seule).
 * <pre>
 * GET /api/executions                         toutes, de la plus récente à la plus ancienne
 * GET /api/executions/{id}
 * GET /api/executions/parser/{parserId}       celles d'un parser
 * GET /api/executions/{id}/files              fichiers sources signés et archivés
 * GET /api/executions/{id}/changes?type=ADDED entités ajoutées / retirées / modifiées (type facultatif)
 * </pre>
 */
@RestController
@RequestMapping("/api/executions")
public class ParserExecutionController {

    private final ParserExecutionService executionService;

    public ParserExecutionController(ParserExecutionService executionService) {
        this.executionService = executionService;
    }

    @GetMapping
    public Page<ParserExecutionDTO> getAll(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return executionService.findAll(PageRequest.of(page, size)).map(ParserExecutionDTO::from);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ParserExecutionDTO> getById(@PathVariable long id) {
        return executionService.findById(id)
                .map(execution -> ResponseEntity.ok(ParserExecutionDTO.from(execution)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/parser/{parserId}")
    public Page<ParserExecutionDTO> getByParser(@PathVariable long parserId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return executionService.findByParser(parserId, PageRequest.of(page, size)).map(ParserExecutionDTO::from);
    }

    @GetMapping("/{id}/files")
    public List<SourceFileDTO> getFiles(@PathVariable long id) {
        return executionService.findSourceFiles(id).stream().map(SourceFileDTO::from).toList();
    }

    @GetMapping("/{id}/changes")
    public Page<DatasetChangeDTO> getChanges(@PathVariable long id,
                                             @RequestParam(required = false) ChangeType type,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return executionService.findChanges(id, type, PageRequest.of(page, size)).map(DatasetChangeDTO::from);
    }
}
