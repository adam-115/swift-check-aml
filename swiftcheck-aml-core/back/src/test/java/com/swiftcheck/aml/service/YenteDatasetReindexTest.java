package com.swiftcheck.aml.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;

/**
 * « Réindexer ce dataset » : la date de modification du fichier publié avance, Yente y voit une nouvelle
 * version et ne reconstruit que cet index à son prochain passage.
 */
class YenteDatasetReindexTest {

    private static final FileTime OLD = FileTime.from(Instant.parse("2026-01-01T00:00:00Z"));

    @TempDir
    Path dir;

    private final YenteManifestService service = new YenteManifestService();
    private ApplicationConfigEntity config;
    private Path file;

    @BeforeEach
    void publish() throws Exception {
        config = new ApplicationConfigEntity();
        config.setYenteDataFolder(dir.resolve("data").toString());
        config.setYenteManifestPath(dir.resolve("manifest.yml").toString());
        config.setYenteContainerDataPath("/data");
        file = dir.resolve("data/fr_pep_senate/fr_pep_senate.ndjson");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"id\":\"a\"}\n{\"id\":\"b\"}\n");
        Files.setLastModifiedTime(file, OLD);
        service.addDataset("fr_pep_senate", "PEP France – Sénateurs", config);
    }

    @Test
    void reindexDatasetAvanceLaDateDuFichier() throws Exception {
        service.reindexDataset("fr_pep_senate", config);

        assertTrue(Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minusSeconds(5)));
        assertEquals("{\"id\":\"a\"}\n{\"id\":\"b\"}\n", Files.readString(file), "contenu inchangé");
    }

    @Test
    void reindexDatasetRefuseUnDatasetInconnu() throws Exception {
        var absent = assertThrows(IllegalStateException.class, () -> service.reindexDataset("absent", config));
        assertTrue(absent.getMessage().contains("Aucun fichier publié"), absent.getMessage());
        assertFalse(Files.exists(dir.resolve("data/absent")), "aucun dossier créé");

        Path orphan = dir.resolve("data/orphan/orphan.ndjson");
        Files.createDirectories(orphan.getParent());
        Files.writeString(orphan, "{}\n");
        var notInManifest = assertThrows(IllegalStateException.class, () -> service.reindexDataset("orphan", config));
        assertTrue(notInManifest.getMessage().contains("manifest"), notInManifest.getMessage());
    }

    @Test
    void reindexDatasetRefuseUnNomInvalide() throws Exception {
        FileTime manifestTime = Files.getLastModifiedTime(dir.resolve("manifest.yml"));
        for (String name : new String[] { "../manifest", "a/b", "a\\b", "", " ", "FR_PEP" }) {
            var invalid = assertThrows(IllegalStateException.class, () -> service.reindexDataset(name, config),
                    "nom : " + name);
            assertTrue(invalid.getMessage().contains("Nom de dataset invalide"), invalid.getMessage());
        }
        assertEquals(manifestTime, Files.getLastModifiedTime(dir.resolve("manifest.yml")));
    }
}
