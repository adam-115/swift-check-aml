package com.swiftcheck.aml.yente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Nombre d'entités attendu = lignes non vides du fichier publié, recompté seulement quand le fichier change. */
class ExpectedEntityCounterTest {

    @TempDir
    Path dir;

    private final ExpectedEntityCounter counter = new ExpectedEntityCounter();

    @Test
    void compteLesLignesNonVides() throws Exception {
        Path file = dir.resolve("a.ndjson");
        Files.writeString(file, "{\"id\":1}\n\n{\"id\":2}\n");
        assertEquals(2L, counter.count(file));
    }

    @Test
    void compteInconnuSiFichierAbsent() {
        assertNull(counter.count(dir.resolve("absent.ndjson")));
    }

    @Test
    void recompteQuandLeFichierChange() throws Exception {
        Path file = dir.resolve("b.ndjson");
        Files.writeString(file, "a\nb\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals(2L, counter.count(file));

        Files.writeString(file, "a\nb\nc\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));
        assertEquals(3L, counter.count(file));
    }
}
