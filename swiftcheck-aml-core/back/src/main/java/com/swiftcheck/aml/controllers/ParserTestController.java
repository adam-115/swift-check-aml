package com.swiftcheck.aml.controllers;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.dto.ParserTestDTO;
import com.swiftcheck.aml.dto.ParserTestFailureDTO;
import com.swiftcheck.aml.entities.TestFailureReason;
import com.swiftcheck.aml.parsertest.ParserTestViews;
import com.swiftcheck.aml.repositories.ParserTestFailureRepository;
import com.swiftcheck.aml.repositories.ParserTestRepository;

/**
 * Historique et détail des tests de parser. Le lancement est {@code POST /api/parsers/{id}/test}.
 *
 * <pre>
 * GET /api/parser-tests                         tous les tests, du plus récent au plus ancien ; ?parserId= pour un parser
 * GET /api/parser-tests/{id}                    avec la publication testée et son fichier source
 * GET /api/parser-tests/{id}/failures           éléments non retrouvés ; ?reason=NOT_INDEXED pour filtrer
 * </pre>
 */
@RestController
@RequestMapping("/api/parser-tests")
public class ParserTestController {

    private final ParserTestRepository tests;
    private final ParserTestFailureRepository failures;
    private final ParserTestViews views;

    public ParserTestController(ParserTestRepository tests, ParserTestFailureRepository failures, ParserTestViews views) {
        this.tests = tests;
        this.failures = failures;
        this.views = views;
    }

    @GetMapping
    public Page<ParserTestDTO> getAll(@RequestParam(required = false) Long parserId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return (parserId == null ? tests.findAllByOrderByStartedAtDesc(pageable)
                : tests.findByParserIdOrderByStartedAtDesc(parserId, pageable)).map(views::toDto);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ParserTestDTO> getById(@PathVariable long id) {
        return tests.findById(id).map(test -> ResponseEntity.ok(views.toDto(test)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/failures")
    public Page<ParserTestFailureDTO> getFailures(@PathVariable long id,
            @RequestParam(required = false) TestFailureReason reason,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return (reason == null ? failures.findByTestIdOrderByIdAsc(id, pageable)
                : failures.findByTestIdAndReasonOrderByIdAsc(id, reason, pageable)).map(ParserTestFailureDTO::from);
    }
}
