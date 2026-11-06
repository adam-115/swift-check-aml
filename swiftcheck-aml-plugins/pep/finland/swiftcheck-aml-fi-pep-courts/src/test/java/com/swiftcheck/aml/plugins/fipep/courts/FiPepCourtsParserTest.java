package com.swiftcheck.aml.plugins.fipep.courts;

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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Pages réelles « Presidentti ja jäsenet » de la Cour suprême et de la Cour administrative suprême (6 novembre 2026). */
class FiPepCourtsParserTest {

    @Test
    void readsThePresidentAndMembersOfTheSupremeCourt() {
        List<FiPepCourtsParser.Judge> judges = FiPepCourtsParser.read(resource("kko.html"));

        assertEquals(20, judges.size(), "1 président et 19 membres");
        var president = judges.stream().filter(FiPepCourtsParser.Judge::president).toList();
        assertEquals(1, president.size());
        assertEquals("Tatu Leppänen", president.get(0).name());
        assertEquals("1968", president.get(0).birthYear());
        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Tuula Pynnä") && j.onLeave()), "(vv.) = en congé");
        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Kaarlo Hakamies")), "caractères invisibles retirés");
        assertTrue(judges.stream().noneMatch(j -> j.name().contains("(vv.)")));
    }

    @Test
    void readsThePresidentAndMembersOfTheSupremeAdministrativeCourt() {
        List<FiPepCourtsParser.Judge> judges = FiPepCourtsParser.read(resource("kho.html"));

        assertTrue(judges.size() >= 20, judges.size() + " juges lus");
        var president = judges.stream().filter(FiPepCourtsParser.Judge::president).toList();
        assertEquals(1, president.size());
        assertEquals("Kari Kuusiniemi", president.get(0).name());
        assertEquals("1960", president.get(0).birthYear());
        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Janne Aer") && j.birthYear().equals("1961")));
    }

    @Test
    void downloadsOnePagePerCourt(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        FiPepCourtsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, FiPepCourtsParser.COURTS, rawDir);

        assertEquals(2, calls.size());
        assertTrue(Files.exists(rawDir.resolve("kko.html")) && Files.exists(rawDir.resolve("kho.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("kko.html"), resource("kko.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("kho.html"), resource("kho.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FiPepCourtsParser.ID_PREFIX, FiPepCourtsParser.TOPIC);

        String message = new FiPepCourtsParser().convert(rawDir, FiPepCourtsParser.COURTS, dataset);

        assertTrue(message.contains("2 cour(s)"), message);
        FtmEntity president = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Tatu Leppänen")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), president.getValues("topics"));
        assertEquals(Set.of("1968"), president.getValues("birthDate"));
        assertEquals(Set.of("Président de la Cour suprême (Korkein oikeus)"), president.getValues("position"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageHasNoJudge() {
        assertThrows(IllegalStateException.class, () -> FiPepCourtsParser.read("<html><main>Just a moment...</main></html>"));
    }

    @Test
    void rejectsAnUnknownCourt() {
        assertThrows(IllegalArgumentException.class, () -> FiPepCourtsParser.selected(java.util.Map.of("courts", "xyz")));
    }

    private static String resource(String name) {
        try (InputStream in = FiPepCourtsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
