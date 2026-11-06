package com.swiftcheck.aml.plugins.sipep.government;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Page réelle « O vladi » de www.gov.si (7 novembre 2026) : le Gouvernement de Janez Janša, nommé le 4 juin 2026. */
class SiPepGovernmentParserTest {

    @Test
    void readsThePrimeMinisterAndTheMinisters() {
        var members = SiPepGovernmentParser.members(resource("o-vladi.html"));

        assertEquals("Janez Janša", members.get(0).name(), "Premier ministre d'après la phrase de présentation");
        assertEquals("Predsednik vlade", members.get(0).role());
        assertEquals(15, members.size(), "Premier ministre et 14 ministres listés");
        var sircelj = members.stream().filter(m -> m.name().equals("Andrej Šircelj")).findFirst().orElseThrow();
        assertEquals("Minister (Ministrstvo za finance)", sircelj.role());
        assertEquals("https://www.gov.si/drzavni-organi/ministrstva/ministrstvo-za-finance/o-ministrstvu/mag-andrej-sircelj/", sircelj.url());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Janez Cigler Kralj")), "nom composé");
    }

    @Test
    void readsTheAppointmentDateWithGenitiveMonths() {
        assertEquals(LocalDate.of(2026, 6, 4), SiPepGovernmentParser.appointed(resource("o-vladi.html")));
        assertEquals(LocalDate.of(2022, 6, 1), SiPepGovernmentParser.appointed("imenoval 1. junija 2022"));
        assertNull(SiPepGovernmentParser.appointed("<html/>"));
    }

    @Test
    void downloadsThePage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        SiPepGovernmentParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, SiPepGovernmentParser.DEFAULT_URL, rawDir);

        assertEquals(List.of("https://www.gov.si/drzavni-organi/vlada/o-vladi/"), calls);
        assertTrue(Files.exists(rawDir.resolve("o-vladi.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("o-vladi.html"), resource("o-vladi.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(SiPepGovernmentParser.ID_PREFIX, SiPepGovernmentParser.TOPIC);

        String message = new SiPepGovernmentParser().convert(rawDir, SiPepGovernmentParser.DEFAULT_URL, dataset);

        assertTrue(message.startsWith("15 membres du Gouvernement (nommé le 2026-06-04)"), message);
        assertEquals(15, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        assertEquals(15, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("startDate").contains("2026-06-04")).count());
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.head")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new SiPepGovernmentParser().convert(rawDir, "x", new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> SiPepGovernmentParser.members("<html/>"));
    }

    private static String resource(String name) {
        try (InputStream in = SiPepGovernmentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
