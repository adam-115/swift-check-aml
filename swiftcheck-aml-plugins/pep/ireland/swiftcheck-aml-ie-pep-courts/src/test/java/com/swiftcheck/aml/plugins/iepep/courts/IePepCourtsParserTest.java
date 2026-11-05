package com.swiftcheck.aml.plugins.iepep.courts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page réelle « The Judges » de courts.ie (5 novembre 2026). */
class IePepCourtsParserTest {

    @Test
    void readsTheThreeHighCourtsAndSkipsTheOthers() throws Exception {
        List<IePepCourtsParser.Judge> judges = IePepCourtsParser.read(resource("judges.html"), IePepCourtsParser.DEFAULT_COURTS);

        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Donal O'Donnell") && j.court().equals("Supreme Court")
                && "Chief Justice".equals(j.role()) && !j.exOfficio()));
        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Caroline Costello") && j.court().equals("Court of Appeal")
                && "President of the Court of Appeal".equals(j.role())));
        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Michael Quinn") && j.court().equals("High Court")));
        assertTrue(judges.stream().noneMatch(j -> j.court().contains("Circuit")), "Circuit Court non demandée");
        assertTrue(judges.stream().noneMatch(j -> j.name().contains("Vacancy") || j.name().contains("Hon.")));
    }

    @Test
    void ex_officioMembersAreNotCountedAsOrdinaryJudgesOfThatCourt() throws Exception {
        List<IePepCourtsParser.Judge> judges = IePepCourtsParser.read(resource("judges.html"), IePepCourtsParser.DEFAULT_COURTS);

        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Caroline Costello") && j.court().equals("Supreme Court") && j.exOfficio()));
        assertFalse(judges.stream().anyMatch(j -> j.name().equals("Donal O'Donnell") && j.court().equals("Court of Appeal") && !j.exOfficio()),
                "le Chief Justice est de droit à la Court of Appeal, pas un juge ordinaire");
    }

    @Test
    void decodesIrishNamesWithFadas() throws Exception {
        List<IePepCourtsParser.Judge> judges = IePepCourtsParser.read(resource("judges.html"), IePepCourtsParser.DEFAULT_COURTS);

        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Máire Whelan")), "&aacute; décodé en á");
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource("judges.html"));

        ParserResult result = new IePepCourtsParser()
                .parse(new ParserContext("ie_pep_courts", input, dir, dir.resolve("out.ndjson"), Map.of()));

        assertEquals(0, result.rejectedCount());
        FtmEntity chief = result.entities().stream().filter(e -> e.getSchema().equals("Person")
                && e.getValues("name").contains("Donal O'Donnell")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), chief.getValues("topics"));
        assertTrue(chief.getValues("position").contains("Judge of the Supreme Court"));
        assertTrue(chief.getValues("position").contains("Chief Justice"));
        long persons = result.entities().stream().filter(e -> e.getSchema().equals("Person")).count();
        assertTrue(persons >= 55 && persons <= 120, "juges des trois cours : " + persons);
    }

    @Test
    void courtsParameterNarrowsTheSelection(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(IePepCourtsParser.ID_PREFIX, IePepCourtsParser.TOPIC);
        IePepCourtsParser.Report report = new IePepCourtsParser().convert(resource("judges.html"), List.of("Supreme Court"), dataset, "x");

        assertTrue(report.persons() < 15, "Supreme Court seulement : " + report.persons());
        assertEquals(List.of("Supreme Court", "Court of Appeal"), IePepCourtsParser.courts(Map.of("courts", "Supreme Court, Court of Appeal")));
    }

    @Test
    void failsClearlyWhenThePageHasNoJudge() {
        assertThrows(IllegalStateException.class, () -> new IePepCourtsParser().convert("<html><h2>Other</h2></html>",
                IePepCourtsParser.DEFAULT_COURTS, new FtmDatasetBuilder(IePepCourtsParser.ID_PREFIX), "x"));
    }

    private static String resource(String name) {
        try (InputStream in = IePepCourtsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
