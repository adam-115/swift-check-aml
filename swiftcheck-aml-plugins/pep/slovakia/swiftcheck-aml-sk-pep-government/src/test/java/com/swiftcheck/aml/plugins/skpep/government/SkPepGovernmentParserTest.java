package com.swiftcheck.aml.plugins.skpep.government;

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

/** Page réelle « Členovia vlády » de www.vlada.gov.sk (7 novembre 2026, mise à jour du 27/01/2026). */
class SkPepGovernmentParserTest {

    @Test
    void readsTheMembersAndSkipsTheBrokenEntry() {
        var members = SkPepGovernmentParser.members(resource("members.html"));

        assertEquals("Robert Fico", members.get(0).name());
        assertEquals("Predseda vlády SR", members.get(0).role());
        assertEquals("Robert Kaliňák", members.get(1).name());
        assertEquals("Podpredseda vlády a minister obrany SR", members.get(1).role());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Denisa Saková") && m.role().startsWith("Podpredsedníčka")), "féminin");
        assertEquals(1, members.stream().filter(m -> m.name().equals("Tomáš Drucker")).count(), "une seule entrée par personne");
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Matúš Šutaj Eštok")), "nom composé");
        assertTrue(members.size() >= 15, members.size() + " membres");
    }

    @Test
    void downloadsThePage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        SkPepGovernmentParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, SkPepGovernmentParser.DEFAULT_URL, rawDir);

        assertEquals(List.of("https://www.vlada.gov.sk/vlada-sr/clenovia-vlady/"), calls);
        assertTrue(Files.exists(rawDir.resolve("members.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("members.html"), resource("members.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(SkPepGovernmentParser.ID_PREFIX, SkPepGovernmentParser.TOPIC);

        String message = new SkPepGovernmentParser().convert(rawDir, SkPepGovernmentParser.DEFAULT_URL, dataset);

        assertTrue(message.contains("mise à jour le 27.01.2026"), message);
        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertTrue(persons >= 15, message);
        assertEquals(persons, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.head")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new SkPepGovernmentParser().convert(rawDir, "x", new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> SkPepGovernmentParser.members("<html/>"));
    }

    private static String resource(String name) {
        try (InputStream in = SkPepGovernmentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
