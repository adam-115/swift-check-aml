package com.swiftcheck.aml.plugins.iepep.defenceforces;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page réelle « Designated Public Officials » de military.ie (5 novembre 2026). */
class IePepDefenceForcesParserTest {

    @Test
    void readsTheGeneralOfficersAndSkipsTheCivilServants() throws Exception {
        List<IePepDefenceForcesParser.Officer> officers = IePepDefenceForcesParser.read(resource("designated-public-officials.html"));

        assertEquals(12, officers.size(), "chef d état-major, 2 adjoints, 8 généraux de brigade, 1 commodore");
        assertTrue(officers.stream().anyMatch(o -> o.name().equals("Rossa Mulcahy") && o.rank().equals("Lieutenant General")
                && o.appointment().equals("Chief of Staff")));
        assertTrue(officers.stream().anyMatch(o -> o.name().equals("Darragh Kirwan") && o.rank().equals("Commodore")));
        assertTrue(officers.stream().anyMatch(o -> o.name().equals("Colm Ó Luasa")), "fada conservé");
        assertTrue(officers.stream().noneMatch(o -> o.name().equals("Brian Molloy") || o.rank().contains("Secretary")),
                "les Assistant Secretary sont des civils");
        assertTrue(officers.stream().noneMatch(o -> o.name().startsWith(" ") || o.name().endsWith(" ")), "espaces superflus retirés");
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource("designated-public-officials.html"));

        ParserResult result = new IePepDefenceForcesParser()
                .parse(new ParserContext("ie_pep_defence_forces", input, dir, dir.resolve("out.ndjson"), Map.of()));

        assertEquals(0, result.rejectedCount());
        FtmEntity chief = result.entities().stream().filter(e -> e.getSchema().equals("Person")
                && e.getValues("name").contains("Rossa Mulcahy")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), chief.getValues("topics"));
        assertTrue(chief.getValues("position").contains("Chief of Staff"));
        assertEquals(12, result.entities().stream().filter(e -> e.getSchema().equals("Person")).count());
    }

    @Test
    void failsClearlyWithoutTheTable() {
        assertThrows(IllegalStateException.class, () -> IePepDefenceForcesParser.read("<html><table></table></html>"));
    }

    @Test
    void explainsWhatToConfigureWithoutASourceFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new IePepDefenceForcesParser()
                .parse(new ParserContext("ie_pep_defence_forces", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("HTTP_FILE"));
    }

    private static String resource(String name) {
        try (InputStream in = IePepDefenceForcesParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
