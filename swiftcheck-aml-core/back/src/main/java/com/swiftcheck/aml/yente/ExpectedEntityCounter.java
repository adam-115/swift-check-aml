package com.swiftcheck.aml.yente;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Nombre d'entités attendu dans Yente pour un dataset : lignes non vides de son fichier {@code .ndjson} publié
 * (une entité par ligne). Mis en cache par fichier, recompté seulement quand sa date ou sa taille change : l'écran se
 * rafraîchit toutes les quelques secondes et il y a environ 250 fichiers.
 */
@Component
public class ExpectedEntityCounter {

    private record Cached(long modified, long size, long count) {
    }

    private final Map<Path, Cached> cache = new ConcurrentHashMap<>();

    /** @return le nombre d'entités, ou null si le fichier est absent ou illisible */
    public Long count(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            long modified = attributes.lastModifiedTime().toMillis();
            long size = attributes.size();
            Cached cached = cache.get(file);
            if (cached != null && cached.modified() == modified && cached.size() == size) {
                return cached.count();
            }
            long count = 0;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        count++;
                    }
                }
            }
            cache.put(file, new Cached(modified, size, count));
            return count;
        } catch (IOException | RuntimeException e) {
            cache.remove(file);
            return null;
        }
    }
}
