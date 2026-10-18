package com.swiftcheck.aml.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvRecordReaderTest {

    @TempDir
    Path dir;

    private List<CsvRow> read(byte[] content) throws Exception {
        Path file = dir.resolve("test.csv");
        Files.write(file, content);
        List<CsvRow> rows = new ArrayList<>();
        CsvRecordReader.readEach(file, rows::add);
        return rows;
    }

    @Test
    void readsValuesByColumnName() throws Exception {
        List<CsvRow> rows = read("NAME,FIRSTNAME\nAdam,Claude\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, rows.size());
        assertEquals("Adam", rows.get(0).get("NAME"));
        assertEquals("Claude", rows.get(0).get("FIRSTNAME"));
        assertEquals(2, rows.get(0).lineNumber());
    }

    @Test
    void handlesQuotesCommasAndLineBreaksInsideValues() throws Exception {
        String csv = "nom,adresse\r\n\"Arendt\",\"21, rue du \"\"Cimetière\"\"\nMondercange\"\r\n";
        CsvRow row = read(csv.getBytes(StandardCharsets.UTF_8)).get(0);

        assertEquals("Arendt", row.get("nom"));
        assertEquals("21, rue du \"Cimetière\"\nMondercange", row.get("adresse"));
    }

    @Test
    void detectsUtf8WithBomAndWindows1252() throws Exception {
        byte[] utf8WithBom = "﻿groupe\ndéi gréng\n".getBytes(StandardCharsets.UTF_8);
        byte[] windows1252 = "groupe\ndéi gréng\n".getBytes(Charset.forName("windows-1252"));

        assertEquals("déi gréng", read(utf8WithBom).get(0).get("groupe"), "BOM retiré du nom de la 1re colonne");
        assertEquals("déi gréng", read(windows1252).get(0).get("groupe"));
    }

    @Test
    void emptyCellsAndShortLinesGiveNull() throws Exception {
        List<CsvRow> rows = read("a,b,c\n1,,\n\n2\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(2, rows.size(), "ligne vide ignorée");
        assertNull(rows.get(0).get("b"));
        assertNull(rows.get(1).get("c"));
    }

    @Test
    void unknownColumnIsAnError() throws Exception {
        CsvRow row = read("NAME\nAdam\n".getBytes(StandardCharsets.UTF_8)).get(0);

        assertThrows(IllegalArgumentException.class, () -> row.get("NOM"));
    }
}
