package com.swiftcheck.aml.plugins.nlpep.defence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Pages réelles de defensie.nl (6 novembre 2026) : CDS, forces terrestres, navales, aériennes et Maréchaussée. */
class NlPepDefenceParserTest {

    private static final List<String> FILES = List.of("cds", "clas", "cszk", "clrs", "kmar");

    @Test
    void readsFunctionRankNameAndStartDateOfEachCommander() {
        var cds = NlPepDefenceParser.read(resource("cds"), "https://x/cds");
        assertEquals("Commandant der Strijdkrachten", cds.function());
        assertEquals("generaal", cds.rank());
        assertEquals("Onno Eichelsheim", cds.name());
        assertEquals(LocalDate.of(2021, 4, 15), cds.since());

        var navy = NlPepDefenceParser.read(resource("cszk"), "https://x/cszk");
        assertEquals("viceadmiraal", navy.rank());
        assertEquals("Harold Liebregs", navy.name());
        assertEquals(LocalDate.of(2025, 9, 18), navy.since());

        var kmar = NlPepDefenceParser.read(resource("kmar"), "https://x/kmar");
        assertEquals("Roger Costongs", kmar.name(), "« Sinds Sinds » dupliqué sur la page");
        assertEquals(LocalDate.of(2026, 8, 27), kmar.since());
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        List<String> pages = FILES.stream().map(f -> "https://www.defensie.nl/" + f).toList();
        NlPepDefenceParser.download(url -> resource(url.substring(url.lastIndexOf('/') + 1)), pages, rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(NlPepDefenceParser.ID_PREFIX, NlPepDefenceParser.TOPIC);

        int count = new NlPepDefenceParser().convert(rawDir, pages, dataset);

        assertEquals(5, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
        FtmEntity cds = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Onno Eichelsheim")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), cds.getValues("topics"));
        assertTrue(cds.getValues("alias").contains("generaal Onno Eichelsheim"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.security")));
    }

    @Test
    void failsWithTheAddressWhenAPageHasNoCommander() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NlPepDefenceParser.read("<html><title>Page</title></html>", "https://x/y"));
        assertTrue(e.getMessage().contains("https://x/y"));
    }

    @Test
    void pagesParameterIsRequired() {
        assertEquals(2, NlPepDefenceParser.pages("https://a; https://b").size());
        assertThrows(IllegalStateException.class, () -> NlPepDefenceParser.pages(" "));
    }

    private static String resource(String name) {
        try (InputStream in = NlPepDefenceParserTest.class.getResourceAsStream("/" + name + ".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
