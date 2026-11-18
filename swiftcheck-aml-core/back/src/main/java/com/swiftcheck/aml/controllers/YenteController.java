package com.swiftcheck.aml.controllers;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.service.ApplicationConfigService;
import com.swiftcheck.aml.service.YenteManifestService;

/**
 * Actions sur Yente.
 *
 * <pre>
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

    public YenteController(YenteManifestService yenteService, ApplicationConfigService configService) {
        this.yenteService = yenteService;
        this.configService = configService;
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
