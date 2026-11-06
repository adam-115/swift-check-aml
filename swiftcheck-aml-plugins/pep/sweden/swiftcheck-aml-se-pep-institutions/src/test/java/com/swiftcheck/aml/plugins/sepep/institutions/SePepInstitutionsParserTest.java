package com.swiftcheck.aml.plugins.sepep.institutions;

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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Pages réelles de domstol.se, riksbank.se et kungahuset.se (6 novembre 2026). */
class SePepInstitutionsParserTest {

    @Test
    void readsTheSupremeCourtJustices() {
        var judges = SePepInstitutionsParser.readJustices(resource("hd.html"), "Högsta domstolen");

        assertEquals(16, judges.size());
        var president = judges.stream().filter(j -> j.role().startsWith("Président")).toList();
        assertEquals(1, president.size());
        assertEquals("Anders Eka", president.get(0).name());
        assertEquals("2013", president.get(0).start());
        assertTrue(president.get(0).note().contains("président depuis 2018"));
        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Katrin Hollunger Wågnert") && "2025".equals(j.start())), "nom composé");
        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Stefan Johansson") && j.note().contains("président de section")));
    }

    @Test
    void readsTheSupremeAdministrativeCourtJustices() {
        var judges = SePepInstitutionsParser.readJustices(resource("hfd.html"), "Högsta förvaltningsdomstolen");

        assertEquals(16, judges.size());
        assertEquals("Linda Haggren", judges.stream().filter(j -> j.role().startsWith("Président")).findFirst().orElseThrow().name());
        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Ulrik von Essen")), "particule « von »");
    }

    @Test
    void readsTheRiksbankBoard() {
        var board = SePepInstitutionsParser.readRiksbank(resource("riksbank.html"));

        assertEquals(5, board.size());
        assertEquals("Erik Thedéen", board.get(0).name());
        assertTrue(board.get(0).role().startsWith("Gouverneur"));
        assertTrue(board.get(1).role().startsWith("Premier sous-gouverneur"));
        assertTrue(board.stream().anyMatch(p -> p.name().equals("Göran Hjelm") && p.role().startsWith("Sous-gouverneur")));
    }

    @Test
    void readsTheKingWithBirthAndAccessionDates() {
        var king = SePepInstitutionsParser.readKing(resource("king.html"));

        assertEquals(1, king.size());
        assertEquals("1946-04-30", king.get(0).birthDate());
        assertEquals("1973-09-15", king.get(0).start());
        assertEquals("Carl Gustaf Folke Hubertus", king.get(0).name());
        assertEquals("Carl XVI Gustaf", king.get(0).alias());
    }

    @Test
    void downloadsEveryPage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        SePepInstitutionsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, SePepInstitutionsParser.SOURCES, rawDir);

        assertEquals(4, calls.size());
        assertTrue(Files.exists(rawDir.resolve("king.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        for (String f : List.of("hd.html", "hfd.html", "riksbank.html", "king.html")) {
            Files.writeString(rawDir.resolve(f), resource(f), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(SePepInstitutionsParser.ID_PREFIX, SePepInstitutionsParser.TOPIC);

        String message = new SePepInstitutionsParser().convert(rawDir, SePepInstitutionsParser.SOURCES, dataset);

        assertTrue(message.startsWith("38 personnes lues sur 4 source(s)"), message);
        FtmEntity king = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("alias").contains("Carl XVI Gustaf")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), king.getValues("topics"));
        assertEquals(Set.of("1946-04-30"), king.getValues("birthDate"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenAPageChanged() {
        assertThrows(IllegalStateException.class, () -> SePepInstitutionsParser.readJustices("<html/>", "Högsta domstolen"));
        assertThrows(IllegalStateException.class, () -> SePepInstitutionsParser.readRiksbank("<html/>"));
        assertThrows(IllegalStateException.class, () -> SePepInstitutionsParser.readKing("<html/>"));
    }

    @Test
    void rejectsAnUnknownSource() {
        assertThrows(IllegalArgumentException.class, () -> SePepInstitutionsParser.selected(Map.of("keys", "xyz")));
    }

    private static String resource(String name) {
        try (InputStream in = SePepInstitutionsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
