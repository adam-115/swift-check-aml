package com.swiftcheck.aml.plugins.plpep.government;

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

/** Page réelle « Skład Rady Ministrów » de gov.pl (6 novembre 2026) : 21 membres. */
class PlPepGovernmentParserTest {

    @Test
    void readsThePrimeMinisterAndTheMinisters() {
        List<PlPepGovernmentParser.Member> members = PlPepGovernmentParser.read(resource());

        assertEquals(21, members.size());
        assertEquals("Donald Tusk", members.get(0).name());
        assertEquals("Prezes Rady Ministrów", members.get(0).title());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Agnieszka Dziemianowicz-Bąk") && m.title().startsWith("Ministra")),
                "nom composé et titre féminin");
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Radosław Sikorski") && m.title().contains("Minister Spraw Zagranicznych")));
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Jan Grabiec") && m.title().startsWith("Minister - Członek Rady Ministrów")));
    }

    @Test
    void mapsTitlesToFrenchRoles() {
        assertEquals("Premier ministre (Prezes Rady Ministrów)", PlPepGovernmentParser.role("Prezes Rady Ministrów"));
        assertTrue(PlPepGovernmentParser.role("Wiceprezes Rady Ministrów, Minister Cyfryzacji").startsWith("Vice-premier ministre"));
        assertEquals("Ministre : Minister Zdrowia", PlPepGovernmentParser.role("Minister Zdrowia"));
    }

    @Test
    void downloadsThePage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        PlPepGovernmentParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, PlPepGovernmentParser.URL, rawDir);

        assertEquals(List.of(PlPepGovernmentParser.URL), calls);
        assertTrue(Files.exists(rawDir.resolve("sklad-rady-ministrow.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("sklad-rady-ministrow.html"), resource(), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(PlPepGovernmentParser.ID_PREFIX, PlPepGovernmentParser.TOPIC);

        int n = new PlPepGovernmentParser().convert(rawDir, PlPepGovernmentParser.URL, dataset);

        assertEquals(21, n);
        assertEquals(21, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        assertEquals(21, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChanged() {
        assertThrows(IllegalStateException.class, () -> PlPepGovernmentParser.read("<html><body>Request Rejected</body></html>"));
    }

    private static String resource() {
        try (InputStream in = PlPepGovernmentParserTest.class.getResourceAsStream("/rada-ministrow.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
