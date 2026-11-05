package com.swiftcheck.aml.plugins.ptpep.regulators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page réelle « Conselho Regulador » de l'ERC (erc.pt, 5 novembre 2026). */
class RegulatorPagesTest {

    private static final Path PAGE = Path.of("src/test/resources/regulators/erc.html");

    private static List<String> lines() throws IOException {
        return PageText.lines(Files.readString(PAGE, StandardCharsets.UTF_8));
    }

    @Test
    void readsThePresidentTheVicePresidentAndTheMembers() throws IOException {
        List<RegulatorPages.Member> members = RegulatorPages.erc(lines());

        assertEquals(5, members.size(), "le Conseil Regulador compte cinq membres");
        assertEquals("Helena Sousa", members.get(0).name());
        assertEquals("Presidente", members.get(0).role());
        assertEquals("Pedro Correia Gonçalves", members.get(1).name());
        assertEquals("Vice-Presidente", members.get(1).role());
        assertEquals(3, members.stream().filter(m -> m.role().equals("Vogal")).count());
    }

    @Test
    void writesPersonsPositionsAndCurrentMandates() throws IOException {
        PtPepRegulatorsParser.RegulatorConfig regulator = PtPepRegulatorsParser.loadConfig().regulators().get(0);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(PtPepRegulatorsParser.ID_PREFIX, PtPepRegulatorsParser.TOPIC);

        String message = PtPepRegulatorsParser.convert(
                List.of(new PtPepRegulatorsParser.Outcome(regulator, RegulatorPages.erc(lines()), null)), dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertTrue(message.startsWith("5 personnes lues sur 1 régulateur(s)"), message);
        FtmEntity sousa = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Helena Sousa")).findFirst().orElseThrow();
        assertEquals("Sousa", sousa.getValues("lastName").iterator().next());
        assertTrue(dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema()))
                .allMatch(e -> e.getValues("status").contains("current")));
    }

    @Test
    void reportsARegulatorWhosePageFailsAndStopsWhenNoneIsReadable(@TempDir Path dir) throws IOException {
        PtPepRegulatorsParser.Config config = PtPepRegulatorsParser.loadConfig();
        String html = Files.readString(PAGE, StandardCharsets.UTF_8);

        List<PtPepRegulatorsParser.Outcome> ok = PtPepRegulatorsParser.readAll(config, url -> html, dir);
        assertEquals(null, ok.get(0).problem());
        assertTrue(Files.exists(dir.resolve("erc.html")), "page archivée dans rawDir");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PtPepRegulatorsParser.readAll(config, url -> {
            throw new IOException("HTTP 503");
        }, dir));
        assertTrue(e.getMessage().contains("erc"));
        assertThrows(IllegalStateException.class,
                () -> PtPepRegulatorsParser.readAll(config, url -> "<html>autre page</html>", dir));
    }
}
