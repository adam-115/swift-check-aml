package com.swiftcheck.aml.controllers;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.service.ApplicationConfigService;
import com.swiftcheck.aml.service.YenteManifestService;

/**
 * Actions sur Yente.
 *
 * <pre>
 * POST /api/yente/reindex   Yente relit le manifest et réindexe les listes (en tâche de fond)
 * </pre>
 * Une erreur (Yente injoignable, jeton refusé) renvoie 409 avec {@code {"message": "..."}}.
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

    @PostMapping("/reindex")
    public Map<String, String> reindex() {
        yenteService.reindex(configService.current());
        return Map.of("message", "Réindexation lancée : Yente relit le manifest et réindexe les listes en tâche de fond"
                + " (quelques minutes selon la taille). Attendez la fin avant de lancer un test.");
    }
}
