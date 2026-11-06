package com.swiftcheck.aml.plugins.hrpep.sabor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Pages réelles de www.sabor.hr/hr/zastupnici (7 novembre 2026) : quatre lignes de la première page (50 représentants actifs de
 * la 11e législature) et une page au-delà de la dernière, sans ligne.
 */
class HrPepSaborParserTest {

    private static final String LIST = "https://www.sabor.hr/hr/zastupnici";

    @Test
    void readsTheRowsOfAPage() {
        var mps = HrPepSaborParser.mps(resource("page-0.html"));

        assertEquals(4, mps.size());
        var ahmetovic = mps.stream().filter(m -> m.listedName().startsWith("Ahmetović")).findFirst().orElseThrow();
        assertEquals("Mirela Ahmetović", ahmetovic.name(), "« Nom, Prénom » remis dans l'ordre");
        assertEquals("SDP", ahmetovic.party());
        assertEquals("VIII. izborna jedinica", ahmetovic.district());
        assertEquals("Aktivan", ahmetovic.status());
        assertEquals(LocalDate.of(2024, 5, 16), ahmetovic.start());
        assertTrue(ahmetovic.slug().startsWith("/hr/zastupnici/"), ahmetovic.slug());
        var compound = mps.stream().filter(m -> m.listedName().startsWith("Antolić Vupora")).findFirst().orElseThrow();
        assertEquals("Barbara Antolić Vupora", compound.name(), "nom de famille composé");
    }

    @Test
    void anEmptyPageHasNoRow() {
        assertTrue(HrPepSaborParser.mps(resource("page-empty.html")).isEmpty());
    }

    @Test
    void downloadsPagesUntilTheFirstEmptyOne(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        HrPepSaborParser.download(url -> {
            calls.add(url);
            return url.endsWith("page=0") || url.endsWith("page=1") ? resource("page-0.html") : resource("page-empty.html");
        }, LIST, rawDir);

        assertEquals(List.of(LIST + "?page=0", LIST + "?page=1", LIST + "?page=2"), calls);
        assertTrue(Files.exists(rawDir.resolve("page-1.html")));
        assertTrue(Files.notExists(rawDir.resolve("page-2.html")), "la page vide n'est pas gardée");
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("page-0.html"), resource("page-0.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(HrPepSaborParser.ID_PREFIX, HrPepSaborParser.TOPIC);

        String message = new HrPepSaborParser().convert(rawDir, dataset);

        assertTrue(message.startsWith("4 représentants du Sabor sur 1 page(s)"), message);
        Set<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of("Mirela Ahmetović", "Barbara Antolić Vupora", "Dubravko Bilić", "Ante Babić")), names.toString());
        FtmEntity ahmetovic = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Mirela Ahmetović")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), ahmetovic.getValues("topics"));
        assertEquals(Set.of("Ahmetović, Mirela"), ahmetovic.getValues("alias"));
        assertEquals(4, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("status").contains("current") && e.getValues("startDate").contains("2024-05-16")).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWithoutRowsOrFiles(@TempDir Path rawDir) throws Exception {
        assertThrows(IllegalStateException.class, () -> new HrPepSaborParser().convert(rawDir, new FtmDatasetBuilder("x", "role.pep")));
        Files.writeString(rawDir.resolve("page-0.html"), "<html/>", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new HrPepSaborParser().convert(rawDir, new FtmDatasetBuilder("x", "role.pep")));
    }

    private static String resource(String name) {
        try (InputStream in = HrPepSaborParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
