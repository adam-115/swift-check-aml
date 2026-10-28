package com.swiftcheck.aml.controllers;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

import com.swiftcheck.aml.dto.ParserDTO;
import com.swiftcheck.aml.dto.ParserExecutionDTO;
import com.swiftcheck.aml.entities.ExecutionTriggerEnum;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.PepFunction;
import com.swiftcheck.aml.entities.PepFunctionParser;
import com.swiftcheck.aml.dto.RetainedEntityDTO;
import com.swiftcheck.aml.ingestion.IngestionService;
import com.swiftcheck.aml.ingestion.RetentionService;
import com.swiftcheck.aml.service.PepReferenceService;
import com.swiftcheck.aml.service.ParserExecutionService;
import com.swiftcheck.aml.dto.ParserTestDTO;
import com.swiftcheck.aml.dto.ParserTestPreviewDTO;
import com.swiftcheck.aml.parsertest.ParserTestService;
import com.swiftcheck.aml.parsertest.ParserTestViews;
import com.swiftcheck.aml.repositories.ParserTestRepository;
import com.swiftcheck.aml.service.ParserService;
import com.swiftcheck.aml.service.PluginManagementService;
import com.swiftcheck.aml.service.PluginManagementService.PluginCheck;

/**
 * Configuration et lancement des parsers.
 * 
 * <pre>
 * GET    /api/parsers                 liste paginée (avec le statut de la dernière exécution)
 * GET    /api/parsers/{id}
 * POST   /api/parsers                 création
 * PUT    /api/parsers/{id}            modification
 * DELETE /api/parsers/{id}            désactivation (l'historique est conservé)
 * POST   /api/parsers/{id}/run        exécution immédiate ; ?force=true pour republier même sans changement
 * GET    /api/parsers/{id}/technical-description   description technique (Markdown)
 * PUT    /api/parsers/{id}/technical-description   remplacement, {"markdown": "..."}
 * GET    /api/parsers/{id}/retained  entités gardées par la rétention (disparues de la source)
 * GET    /api/parsers/{id}/test-preview  ce que le test va vérifier (fenêtre de confirmation)
 * POST   /api/parsers/{id}/test       test : chaque élément du fichier source est cherché dans Yente
 * POST   /api/parsers/verify-plugin?path=...   bouton « Vérifier » : charge le JAR avec PF4J et le contrôle
 * </pre>
 * 
 * Les erreurs de validation renvoient 400 avec {@code {"message": "..."}} (voir
 * ApiExceptionHandler).
 */
@RestController
@RequestMapping("/api/parsers")
public class ParserController {

    private final ParserService parserService;
    private final ParserExecutionService executionService;
    private final IngestionService ingestionService;
    private final PluginManagementService pluginService;
    private final ParserTestService testService;
    private final ParserTestViews testViews;
    private final ParserTestRepository testRepository;
    private final PepReferenceService referenceService;
    private final RetentionService retentionService;

    public ParserController(ParserService parserService, ParserExecutionService executionService,
            IngestionService ingestionService,
            PluginManagementService pluginService, ParserTestService testService, ParserTestViews testViews,
            ParserTestRepository testRepository, PepReferenceService referenceService,
            RetentionService retentionService) {
        this.referenceService = referenceService;
        this.retentionService = retentionService;
        this.testRepository = testRepository;
        this.testService = testService;
        this.testViews = testViews;
        this.parserService = parserService;
        this.executionService = executionService;
        this.ingestionService = ingestionService;
        this.pluginService = pluginService;
    }

    @GetMapping
    public Page<ParserDTO> getAll(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return parserService.findAll(PageRequest.of(page, size)).map(this::toDto);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ParserDTO> getById(@PathVariable long id) {
        return parserService.findById(id)
                .map(parser -> ResponseEntity.ok(toDto(parser)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ParserDTO create(@RequestBody ParserDTO dto) {
        Parser parser = dto.toEntity();
        parser.replaceCoverage(resolveCoverage(dto));
        return toDto(parserService.create(parser));
    }

    @PutMapping("/{id}")
    public ParserDTO update(@PathVariable long id, @RequestBody ParserDTO dto) throws IOException {
        Parser changes = dto.toEntity();
        changes.replaceCoverage(resolveCoverage(dto));
        return toDto(parserService.update(id, changes));
    }

    /** Description technique du parser, en Markdown ({@code markdown} vide s'il n'en a pas). */
    @GetMapping("/{id}/technical-description")
    public TechnicalDescription technicalDescription(@PathVariable long id) {
        Parser parser = parserService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Parser introuvable : " + id));
        return TechnicalDescription.of(parser);
    }

    /** Remplace la description technique ; un texte vide l'efface. */
    @PutMapping("/{id}/technical-description")
    public TechnicalDescription updateTechnicalDescription(@PathVariable long id, @RequestBody TechnicalDescription body) {
        return TechnicalDescription.of(parserService.updateTechnicalDescription(id, body.markdown()));
    }

    /** Corps des deux appels ci-dessus. {@code updatedAt} : dernière modification du parser (lecture seule). */
    public record TechnicalDescription(String markdown, Instant updatedAt) {
        static TechnicalDescription of(Parser parser) {
            return new TechnicalDescription(parser.getTechnicalDescription() == null ? "" : parser.getTechnicalDescription(),
                    parser.getUpdatedAt());
        }
    }

    /** Entités disparues de la source mais encore publiées grâce à la rétention. */
    @GetMapping("/{id}/retained")
    public List<RetainedEntityDTO> retained(@PathVariable long id) {
        Parser parser = parserService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Parser introuvable : " + id));
        return retentionService.findRetained(parser).stream()
                .map(e -> RetainedEntityDTO.from(e, RetentionService.expiresAt(e, parser)))
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disable(@PathVariable long id) throws IOException {
        parserService.disable(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lance le parser et attend la fin. Le résultat (SUCCESS, UNCHANGED, BLOCKED,
     * FAILED) est dans la réponse.
     */
    @PostMapping("/{id}/run")
    public ParserExecutionDTO run(@PathVariable long id, @RequestParam(defaultValue = "false") boolean force) {
        return ParserExecutionDTO.from(ingestionService.run(id, ExecutionTriggerEnum.MANUAL, force));
    }

    /**
     * Teste le parser et attend la fin : chaque élément du fichier source de la dernière publication est
     * cherché dans Yente. Le résultat (PASSED / FAILED, compteurs) est dans la réponse ; le détail des
     * éléments non retrouvés est dans {@code GET /api/parser-tests/{id}/failures}.
     */
    @PostMapping("/{id}/test")
    public ParserTestDTO test(@PathVariable long id) {
        return testViews.toDto(testService.run(id));
    }

    /** Fenêtre de confirmation du test : publication et fichier source qui seront testés, dernier test. */
    @GetMapping("/{id}/test-preview")
    public ParserTestPreviewDTO testPreview(@PathVariable long id) {
        return testViews.preview(id);
    }

    /**
     * Vérifie qu'un JAR est un plugin de liste valide : PF4J le charge et y trouve
     * une classe {@code Iparser}.
     * Renvoie son Plugin-Id (recopié dans le formulaire), sa version et la classe
     * du parser.
     * Un JAR invalide renvoie 400 avec le message d'erreur.
     */
    @PostMapping("/verify-plugin")
    public PluginCheck verifyPlugin(@RequestParam String path) {
        return pluginService.verify(path);
    }

    private ParserDTO toDto(Parser parser) {
        return ParserDTO.from(parser, executionService.findLast(parser.getId()).orElse(null))
                .withLastTest(testRepository.findFirstByParserIdOrderByStartedAtDesc(parser.getId()).orElse(null));
    }

    /**
     * Fonctions couvertes choisies dans le formulaire ({@code coverage}), avec leur complétude.
     *
     * @throws IllegalArgumentException si une fonction n'existe pas
     */
    private List<PepFunctionParser> resolveCoverage(ParserDTO dto) {
        Map<Long, PepFunction> functions = new HashMap<>();
        referenceService.findFunctionsByIds(dto.getCoverage().stream().map(ParserDTO.CoverageItem::functionId).toList())
                .forEach(f -> functions.put(f.getId(), f));
        List<PepFunctionParser> links = new ArrayList<>();
        for (ParserDTO.CoverageItem item : dto.getCoverage()) {
            links.add(new PepFunctionParser(functions.get(item.functionId()), item.completeness(), item.includesFormer()));
        }
        return links;
    }
}
