package com.swiftcheck.aml.ingestion;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);   // système de fichiers limité
        }
        manifest.addDataset(parser.getDatasetName(), parser.getName(), config);
    }

    /** Retire le dataset de Yente (parser désactivé). Le fichier reste sur le disque. */
    public void unpublish(Parser parser, ApplicationConfigEntity config) throws IOException {
        manifest.removeDataset(parser.getDatasetName(), config);
    }
}
