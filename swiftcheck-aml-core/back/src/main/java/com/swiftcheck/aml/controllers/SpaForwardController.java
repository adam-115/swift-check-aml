package com.swiftcheck.aml.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Contrôleur de redirection pour les routes Angular (SPA).
 * Permet d'intercepter les accès directs ou rafraîchissements sur les URLs frontend
 * (/parsers, /providers, /config, etc.) et de les transférer à index.html.
 */
@Controller
public class SpaForwardController {

    @GetMapping(value = {
        "/parsers",
        "/parsers/**",
        "/monitoring",
        "/monitoring/**",
        "/verification",
        "/verification/**",
        "/providers",
        "/providers/**",
        "/config",
        "/config/**"
    })
    public String forwardSpaRoutes() {
        return "forward:/index.html";
    }
}
