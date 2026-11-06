package com.swiftcheck.aml.plugins.czpep.institutions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Pages réelles de cnb.cz et nsoud.cz (6 novembre 2026). */
class CzPepInstitutionsParserTest {

    @Test
    void readsTheBankBoard() {
        var board = CzPepInstitutionsParser.readBoard(resource("cnb.html"));

        assertEquals(7, board.size());
        assertEquals("Aleš Michl", board.get(0).name());
        assertTrue(board.get(0).role().startsWith("Gouverneur"));
        assertTrue(board.stream().anyMatch(p -> p.name().equals("Eva Zamrazilová") && p.role().startsWith("Vice-gouverneur")), "viceguvernérka");
        assertTrue(board.stream().anyMatch(p -> p.name().equals("Karina Kubelková") && p.role().startsWith("Membre du conseil")), "členka");
        assertTrue(board.stream().anyMatch(p -> p.name().equals("Jakub Seidler")));
    }

    @Test
    void readsTheSupremeCourtJudgesOfBothColleges() {
        var criminal = CzPepInstitutionsParser.readJudges(resource("ns-criminal.html"), "Soudci trestního kolegia");
        var civil = CzPepInstitutionsParser.readJudges(resource("ns-civil.html"), "Soudci občanskoprávního a obchodního kolegia");

        assertEquals(23, criminal.size());
        assertEquals("Petr Angyalossy", criminal.get(0).name());
        assertTrue(criminal.stream().anyMatch(p -> p.name().equals("Ondřej Vítů")));
        assertTrue(civil.size() >= 30, civil.size() + " juges du collège civil");
        assertTrue(civil.stream().anyMatch(p -> p.name().equals("Vít Bičák")));
        assertTrue(civil.stream().noneMatch(p -> p.name().startsWith("Soudci")), "les menus ne sont pas des juges");
    }

    @Test
    void downloadsEveryPage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        CzPepInstitutionsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, CzPepInstitutionsParser.SOURCES, rawDir);

        assertEquals(3, calls.size());
        assertTrue(Files.exists(rawDir.resolve("ns-criminal.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        for (String f : List.of("cnb.html", "ns-civil.html", "ns-criminal.html")) {
            Files.writeString(rawDir.resolve(f), resource(f), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(CzPepInstitutionsParser.ID_PREFIX, CzPepInstitutionsParser.TOPIC);

        String message = new CzPepInstitutionsParser().convert(rawDir, CzPepInstitutionsParser.SOURCES, dataset);

        assertTrue(message.contains("3 source(s)"), message);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
        assertTrue(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count() >= 60);
    }

    @Test
    void failsClearlyWhenAPageChanged() {
        assertThrows(IllegalStateException.class, () -> CzPepInstitutionsParser.readBoard("<html/>"));
        assertThrows(IllegalStateException.class, () -> CzPepInstitutionsParser.readJudges("<html/>", "Soudci trestního kolegia"));
    }

    @Test
    void rejectsAnUnknownSource() {
        assertThrows(IllegalArgumentException.class, () -> CzPepInstitutionsParser.selected(Map.of("keys", "xyz")));
    }

    private static String resource(String name) {
        try (InputStream in = CzPepInstitutionsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
