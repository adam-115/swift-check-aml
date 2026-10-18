package com.swiftcheck.aml.controllers;

import java.security.Principal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.dto.ResolutionJudgementDTO;
import com.swiftcheck.aml.service.ResolutionJudgementService;

/**
 * Rapprochements entre entités.
 * <pre>
 * GET    /api/judgements        liste paginée, les plus récents d'abord
 * POST   /api/judgements        {"leftEntityId", "rightEntityId", "judgement"} : l'auteur est l'utilisateur connecté
 * DELETE /api/judgements/{id}
 * </pre>
 */
@RestController
@RequestMapping("/api/judgements")
public class ResolutionJudgementController {

    private final ResolutionJudgementService judgementService;

    public ResolutionJudgementController(ResolutionJudgementService judgementService) {
        this.judgementService = judgementService;
    }

    @GetMapping
    public Page<ResolutionJudgementDTO> all(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return judgementService.findAll(PageRequest.of(page, size)).map(ResolutionJudgementDTO::from);
    }

    @PostMapping
    public ResolutionJudgementDTO decide(@RequestBody ResolutionJudgementDTO dto, Principal user) {
        return ResolutionJudgementDTO.from(judgementService.decide(dto.getLeftEntityId(), dto.getRightEntityId(),
                dto.getJudgement(), user == null ? null : user.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        judgementService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
