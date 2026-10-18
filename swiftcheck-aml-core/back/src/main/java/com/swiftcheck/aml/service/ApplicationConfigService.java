package com.swiftcheck.aml.service;

import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.repositories.ApplicationConfigEntityRepository;

/**
 * Accès à la configuration globale (une seule ligne en base, créée par Liquibase).
 */
@Service
public class ApplicationConfigService {

    private final ApplicationConfigEntityRepository repository;

    public ApplicationConfigService(ApplicationConfigEntityRepository repository) {
        this.repository = repository;
    }

    /**
     * La configuration en vigueur. Relue en base à chaque appel : une modification faite depuis l'écran
     * est prise en compte dès l'exécution suivante, sans redémarrage.
     *
     * @throws IllegalStateException si la table est vide (la ligne par défaut est normalement créée par Liquibase)
     */
    public ApplicationConfigEntity current() {
        return repository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Configuration absente : table application_config vide."));
    }

    /** Remplace les valeurs de la configuration en vigueur par celles reçues. */
    public ApplicationConfigEntity update(ApplicationConfigEntity changes) {
        ApplicationConfigEntity config = current();
        config.setYenteUrl(changes.getYenteUrl());
        config.setYenteUpdateToken(changes.getYenteUpdateToken());
        config.setYenteDataFolder(changes.getYenteDataFolder());
        config.setYenteContainerDataPath(changes.getYenteContainerDataPath());
        config.setYenteManifestPath(changes.getYenteManifestPath());
        config.setArchiveFolder(changes.getArchiveFolder());
        config.setWorkFolder(changes.getWorkFolder());
        return repository.save(config);
    }
}
