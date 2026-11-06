package com.swiftcheck.aml.plugins.ropep.government;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Page réelle « Cabinetul de miniștri » de gov.ro (7 novembre 2026) : le Premier ministre et ses ministres. */
class RoPepGovernmentParserTest {

    @Test
    void readsTheNameAndRolePairs() {
        var members = RoPepGovernmentParser.members(resource("members.html"));

        assertEquals(19, members.size());
        assertEquals("Luca-Alexandru Niculescu", members.get(0).name(), "prénom composé");
        assertEquals("prim-ministru", members.get(0).role());
        assertEquals("Marian Neacșu", members.get(1).name());
        assertEquals("viceprim-ministru, ministrul afacerilor interne", members.get(1).role());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Costinel-Bogdan Dumitru")
                && m.role().startsWith("ministru delegat pentru digitalizare")), "ministre délégué");
        assertEquals("Ioana-Ruxandra-Victoria Ciocan", members.get(18).name(), "trois prénoms composés");
    }

    @Test
    void downloadsThePage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        RoPepGovernmentParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, RoPepGovernmentParser.DEFAULT_URL, rawDir);

        assertEquals(List.of("https://gov.ro/ro/guvernul/cabinetul-de-ministri"), calls);
        assertTrue(Files.exists(rawDir.resolve("members.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("members.html"), resource("members.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(RoPepGovernmentParser.ID_PREFIX, RoPepGovernmentParser.TOPIC);

        String message = new RoPepGovernmentParser().convert(rawDir, RoPepGovernmentParser.DEFAULT_URL, dataset);

        assertTrue(message.startsWith("19 membres"), message);
        assertEquals(19, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        assertEquals(19, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.head")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new RoPepGovernmentParser().convert(rawDir, "x", new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> RoPepGovernmentParser.members("<html/>"));
    }

    private static String resource(String name) {
        try (InputStream in = RoPepGovernmentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
