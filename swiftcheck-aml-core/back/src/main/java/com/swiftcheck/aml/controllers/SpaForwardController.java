package com.swiftcheck.aml.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Contrôleur de redirection pour les routes Angular (SPA).
 * Permet d'intercepter les accès directs ou rafraîchissements sur les URLs frontend
 * (/parsers, /coverage, /config, etc.) et de les transférer à index.html.
 *
 * <p>Ces adresses ne renvoient que la page vide de l'application : elles sont ouvertes sans jeton dans
 * {@code SecurityConfig} (un rafraîchissement du navigateur n'envoie pas le jeton JWT, qui n'existe que dans
 * Angular). Les données restent protégées : elles passent par /api/**. La connexion Keycloak est faite par
 * l'authGuard d'Angular.
 */
@Controller
public class SpaForwardController {

    /** Routes de l'application Angular (app.routes.ts), avec leurs sous-pages. À tenir à jour. */
    public static final String[] SPA_ROUTES = {
        "/wiki", "/wiki/**",
        "/parsers", "/parsers/**",
        "/executions", "/executions/**",
        "/tests", "/tests/**",
        "/verification", "/verification/**",
        "/coverage", "/coverage/**",
        "/alerts", "/alerts/**",
        "/config", "/config/**",
    };

    @GetMapping({
        "/wiki", "/wiki/**",
        "/parsers", "/parsers/**",
        "/executions", "/executions/**",
        "/tests", "/tests/**",
        "/verification", "/verification/**",
        "/coverage", "/coverage/**",
        "/alerts", "/alerts/**",
        "/config", "/config/**",
    })
    public String forwardSpaRoutes() {
        return "forward:/index.html";
    }
}
