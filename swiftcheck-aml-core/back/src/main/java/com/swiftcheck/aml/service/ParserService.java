package com.swiftcheck.aml.service;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.DataSourceType;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.ingestion.DatasetPublisher;
import com.swiftcheck.aml.ingestion.ParserParameters;
import com.swiftcheck.aml.repositories.ParserRepository;

/**
 * Gestion de la configuration des parsers : création, modification, désactivation.
 * L'exécution d'un parser est faite par {@code IngestionService}.
 */
@Service
public class ParserService {

    private final ParserRepository parsers;
    private final DatasetPublisher publisher;
    private final ApplicationConfigService configService;

    public ParserService(ParserRepository parsers, DatasetPublisher publisher, ApplicationConfigService configService) {
        this.parsers = parsers;
        this.publisher = publisher;
        this.configService = configService;
    }

    public Page<Parser> findAll(Pageable pageable) {
        return parsers.findAll(pageable);
    }

    public Optional<Parser> findById(long id) {
        return parsers.findById(id);
    }

    /** Crée un parser après vérification de sa configuration. */
    public Parser create(Parser parser) {
        parser.setId(0);
        parser.setCreatedAt(Instant.now());
        validate(parser);
        return parsers.save(parser);
    }

    /**
     * Met à jour la configuration d'un parser. Désactiver un parser retire aussi son dataset de Yente.
     *
     * @throws IllegalArgumentException si le parser n'existe pas ou si la configuration est invalide
     */
    public Parser update(long id, Parser changes) throws IOException {
        Parser parser = parsers.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Parser introuvable : " + id));
        boolean wasEnabled = parser.isEnabled();

        parser.setListProvider(changes.getListProvider());
        parser.setName(changes.getName());
        parser.setDescription(changes.getDescription());
        parser.setDatasetName(changes.getDatasetName());
        parser.setPluginId(changes.getPluginId());
        parser.setPluginUrl(changes.getPluginUrl());
        parser.setDataSourceType(changes.getDataSourceType());
        parser.setSourceLocation(changes.getSourceLocation());
        parser.setParameters(changes.getParameters());
        parser.setRefreshHours(changes.getRefreshHours());
        parser.setParserKind(changes.getParserKind());
        parser.setRetentionMonths(changes.getRetentionMonths());
        parser.setLicence(changes.getLicence());
        parser.setAttribution(changes.getAttribution());
        parser.setSource(changes.getSource());
        parser.setCoveredFunctions(changes.getCoveredFunctions());
        parser.setEnabled(changes.isEnabled());
        parser.setUpdatedAt(Instant.now());
        validate(parser);

        Parser saved = parsers.save(parser);
        if (wasEnabled && !saved.isEnabled()) {
            publisher.unpublish(saved, configService.current());
        }
        return saved;
    }

    /**
     * « Suppression » d'un parser : il est désactivé et retiré de Yente, mais gardé en base pour conserver
     * l'historique de ses exécutions (preuve des listes utilisées).
     */
    public void disable(long id) throws IOException {
        Parser parser = parsers.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Parser introuvable : " + id));
        parser.setEnabled(false);
        parser.setUpdatedAt(Instant.now());
        parsers.save(parser);
        publisher.unpublish(parser, configService.current());
    }

    /**
     * Vérifie la configuration. Les messages sont affichés tels quels à l'utilisateur.
     *
     * @throws IllegalArgumentException au premier problème trouvé
     */
    void validate(Parser parser) {
        String dataset = parser.getDatasetName();
        if (dataset == null || !dataset.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "Nom de dataset obligatoire, en minuscules, chiffres et _ (ex. eu_sanctions).");
        }
        if (parsers.existsByDatasetNameAndIdNot(dataset, parser.getId())) {
            throw new IllegalArgumentException("Le dataset « " + dataset + " » est déjà utilisé par un autre parser.");
        }
        if (parser.getPluginId() == null || parser.getPluginId().isBlank()) {
            throw new IllegalArgumentException("Identifiant du plugin obligatoire (Plugin-Id du JAR, ex. eu-parser).");
        }
        if (parser.getPluginUrl() != null) {
            // Le JAR doit exister et déclarer le même Plugin-Id : sinon le parser lancerait un autre plugin.
            String jarPluginId = PluginManagementService.readPluginId(parser.getPluginUrl());
            if (!jarPluginId.equals(parser.getPluginId())) {
                throw new IllegalArgumentException("Le JAR déclare le plugin « " + jarPluginId
                        + " » et non « " + parser.getPluginId() + " » : cliquez sur « Vérifier » pour corriger.");
            }
        }
        if (parser.getDataSourceType() == null) {
            throw new IllegalArgumentException("Type de source obligatoire : HTTP_FILE, LOCAL_FILE ou PLUGIN_MANAGED.");
        }
        boolean needsLocation = parser.getDataSourceType() != DataSourceType.PLUGIN_MANAGED;
        String location = parser.getSourceLocation();
        if (needsLocation && (location == null || location.isBlank())) {
            throw new IllegalArgumentException("Emplacement de la source obligatoire (URL ou chemin du fichier).");
        }
        if (parser.getDataSourceType() == DataSourceType.HTTP_FILE && !location.matches("(?i)https?://.+")) {
            throw new IllegalArgumentException("Une source HTTP_FILE doit être une URL http:// ou https://.");
        }
        if (parser.getRefreshHours() < 0) {
            throw new IllegalArgumentException("La fréquence (heures) ne peut pas être négative.");
        }
        if (parser.getRetentionMonths() < 0) {
            throw new IllegalArgumentException("La rétention (mois) ne peut pas être négative : 0 = aucune rétention.");
        }
        ParserParameters.parse(parser.getParameters());       // lève une erreur claire si le JSON est invalide
    }
}
