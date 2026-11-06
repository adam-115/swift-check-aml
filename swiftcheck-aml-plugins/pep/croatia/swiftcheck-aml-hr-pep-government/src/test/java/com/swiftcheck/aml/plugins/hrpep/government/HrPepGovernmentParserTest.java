package com.swiftcheck.aml.plugins.hrpep.government;

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

/** Page réelle « Članovi Vlade » de vlada.gov.hr (7 novembre 2026) : 19 membres. */
class HrPepGovernmentParserTest {

    @Test
    void readsTheNameAndRolePairs() {
        var members = HrPepGovernmentParser.members(resource("members.html"));

        assertEquals(19, members.size());
        assertEquals("Andrej Plenković", members.get(0).name());
        assertEquals("Predsjednik Vlade Republike Hrvatske", members.get(0).role());
        assertEquals("Tomo Medved", members.get(1).name());
        assertEquals("Potpredsjednik Vlade i ministar hrvatskih branitelja", members.get(1).role());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Nina Obuljen Koržinek") && m.role().startsWith("Ministrica kulture")),
                "nom composé et fonction au féminin");
        assertEquals("Irena Hrstić", members.get(18).name());
    }

    @Test
    void downloadsThePage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        HrPepGovernmentParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, HrPepGovernmentParser.DEFAULT_URL, rawDir);

        assertEquals(List.of("https://vlada.gov.hr/clanovi-vlade/128"), calls);
        assertTrue(Files.exists(rawDir.resolve("members.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("members.html"), resource("members.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(HrPepGovernmentParser.ID_PREFIX, HrPepGovernmentParser.TOPIC);

        String message = new HrPepGovernmentParser().convert(rawDir, HrPepGovernmentParser.DEFAULT_URL, dataset);

        assertTrue(message.startsWith("19 membres"), message);
        assertEquals(19, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        assertEquals(19, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.head")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new HrPepGovernmentParser().convert(rawDir, "x", new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> HrPepGovernmentParser.members("<html/>"));
    }

    private static String resource(String name) {
        try (InputStream in = HrPepGovernmentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
