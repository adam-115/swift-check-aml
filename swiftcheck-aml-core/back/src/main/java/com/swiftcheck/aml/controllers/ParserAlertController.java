package com.swiftcheck.aml.controllers;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.dto.ParserAlertDTO;
import com.swiftcheck.aml.service.AlertService;

/**
 * Alertes sur les parsers.
 * <pre>
 * GET  /api/alerts/open          alertes ouvertes
 * GET  /api/alerts/open/count    nombre d'alertes ouvertes (badge du menu)
 * GET  /api/alerts               historique paginé
 * POST /api/alerts/{id}/resolve  résolution à la main
 * </pre>
 */
@RestController
@RequestMapping("/api/alerts")
public class ParserAlertController {

    private final AlertService alertService;

    public ParserAlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/open")
    public List<ParserAlertDTO> open() {
        return alertService.findOpen().stream().map(ParserAlertDTO::from).toList();
    }

    @GetMapping("/open/count")
    public Map<String, Long> openCount() {
        return Map.of("count", alertService.countOpen());
    }

    @GetMapping
    public Page<ParserAlertDTO> all(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return alertService.findAll(PageRequest.of(page, size)).map(ParserAlertDTO::from);
    }

    @PostMapping("/{id}/resolve")
    public ParserAlertDTO resolve(@PathVariable long id) {
        return ParserAlertDTO.from(alertService.resolve(id));
    }
}
