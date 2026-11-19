package com.swiftcheck.aml.controllers;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.dto.YenteEventDTO;
import com.swiftcheck.aml.dto.YenteStatusDTO;
import com.swiftcheck.aml.service.ApplicationConfigService;
import com.swiftcheck.aml.service.YenteManifestService;
import com.swiftcheck.aml.yente.YenteMonitorService;

/**
 * Actions sur Yente.
 *
 * <pre>
 * GET  /api/yente/status                    état de Yente et de chaque dataset (écran « Yente »)
 * GET  /api/yente/datasets/{name}/events     historique d'indexation d'un dataset (?limit=50, 200 au plus)
 * POST /api/yente/datasets/{name}/reindex   réindexe ce seul dataset au prochain passage de Yente (2 min au plus)
 * POST /api/yente/reindex                   « Tout reconstruire » : tous les index, forcé (administrateur)
 * </pre>
 * Une publication n'a besoin d'aucun appel : Yente réindexe seul les datasets modifiés ({@code YENTE_AUTO_REINDEX}).
 * Une erreur (Yente injoignable, jeton refusé, dataset inconnu) renvoie 409 avec {@code {"message": "..."}}.
 */
@RestController
@RequestMapping("/api/yente")
public class YenteController {

    private final YenteManifestService yenteService;
    private final ApplicationConfigService configService;
    private final YenteMonitorService monitor;

    public YenteController(YenteManifestService yenteService, ApplicationConfigService configService,
            YenteMonitorService monitor) {
        this.yenteService = yenteService;
        this.configService = configService;
        this.monitor = monitor;
    }

    @GetMapping("/status")
    public YenteStatusDTO status() {
        return monitor.snapshot();
    }

    @GetMapping("/datasets/{name}/events")
    public List<YenteEventDTO> events(@PathVariable String name, @RequestParam(defaultValue = "50") int limit) {
        return monitor.events(name, limit);
    }

    @PostMapping("/datasets/{name}/reindex")
    public Map<String, String> reindexDataset(@PathVariable String name) {
        yenteService.reindexDataset(name, configService.current());
        return Map.of("message", "Réindexation de " + name + " demandée : Yente la prendra en compte à son prochain"
                + " passage (2 minutes au plus), puis l'indexera.");
    }

    @PostMapping("/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> reindex() {
        yenteService.reindex(configService.current());
        return Map.of("message", "Reconstruction complète lancée : Yente reconstruit tous les index en tâche de fond"
                + " (cela peut être long).");
    }
}
