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

import com.swiftcheck.aml.dto.PepSourceDTO;
import com.swiftcheck.aml.service.PepSourceService;

/**
 * Sources PEP (où récupérer les titulaires des fonctions).
 * <pre>
 * GET    /api/pep/sources?country=LU   sources d'un pays (toutes si country est absent)
 * GET    /api/pep/sources/{id}
 * POST   /api/pep/sources              création, avec les fonctions couvertes
 * PUT    /api/pep/sources/{id}         modification (les fonctions couvertes sont remplacées)
 * DELETE /api/pep/sources/{id}         refusée si un parser lit encore la source
 * </pre>
 * Les modifications sont réservées aux administrateurs (SecurityConfig, /api/pep/**).
 */
@RestController
@RequestMapping("/api/pep/sources")
public class PepSourceController {

    private final PepSourceService sourceService;

    public PepSourceController(PepSourceService sourceService) {
        this.sourceService = sourceService;
    }

    @GetMapping
    public List<PepSourceDTO> all(@RequestParam(required = false) String country) {
        return sourceService.findAll(country);
    }

    @GetMapping("/{id}")
    public PepSourceDTO get(@PathVariable long id) {
        return sourceService.get(id);
    }

    @PostMapping
    public PepSourceDTO create(@RequestBody PepSourceDTO dto) {
        return sourceService.save(0, dto);
    }

    @PutMapping("/{id}")
    public PepSourceDTO update(@PathVariable long id, @RequestBody PepSourceDTO dto) {
        return sourceService.save(id, dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        sourceService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
