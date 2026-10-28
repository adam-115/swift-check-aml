package com.swiftcheck.aml.ingestion;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.service.YenteManifestService;

/**
 * Publie un fichier validé dans le dossier lu par Yente, puis met à jour le manifest et recharge Yente.
 *
 * <p>Emplacement unique d'un dataset : {@code <yenteDataFolder>/<dataset>/<dataset>.ndjson}.
 * C'est le core qui le décide, jamais le plugin : le manifest pointe ainsi toujours au bon endroit.
 *
 * <p>Sans état.
 */
@Service
public class DatasetPublisher {

    private static final Logger log = LoggerFactory.getLogger(DatasetPublisher.class);

    /** Tentatives de remplacement du fichier publié (2 s, 4 s, 6 s, 8 s d'attente entre elles). */
    private static final int MOVE_ATTEMPTS = 5;

    private final YenteManifestService manifest;

    public DatasetPublisher(YenteManifestService manifest) {
        this.manifest = manifest;
    }

    /** Fichier actuellement publié pour ce parser (il peut ne pas encore exister). */
    public Path publishedFile(Parser parser, ApplicationConfigEntity config) {
        String dataset = parser.getDatasetName();
        return Path.of(config.getYenteDataFolder(), dataset, dataset + ".ndjson");
    }

    /**
     * Remplace le fichier publié par {@code newFile}, sans que Yente puisse lire un fichier à moitié copié :
     * copie d'abord dans un fichier temporaire du même dossier, puis remplacement en une seule opération.
     */
    public void publish(Parser parser, Path newFile, ApplicationConfigEntity config) throws IOException {
        Path target = publishedFile(parser, config);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.copy(newFile, temporary, StandardCopyOption.REPLACE_EXISTING);
        replace(temporary, target);
        manifest.addDataset(parser.getDatasetName(), parser.getName(), config);
    }

    /**
     * Sous Windows, un fichier ouvert par un autre programme (Yente qui lit l'ancienne version, antivirus qui
     * analyse le fichier temporaire) ne peut pas être remplacé : AccessDeniedException. Le verrou dure en général
     * quelques secondes, d'où quelques nouvelles tentatives avant d'abandonner.
     */
    private static void replace(Path temporary, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);   // système de fichiers limité
                }
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= MOVE_ATTEMPTS) {
                    throw e;
                }
                log.warn("Fichier publié verrouillé ({}), nouvelle tentative {}/{} dans {} s", target, attempt + 1,
                        MOVE_ATTEMPTS, attempt * 2);
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Retire le dataset de Yente (parser désactivé). Le fichier reste sur le disque. */
    public void unpublish(Parser parser, ApplicationConfigEntity config) throws IOException {
        manifest.removeDataset(parser.getDatasetName(), config);
    }
}
