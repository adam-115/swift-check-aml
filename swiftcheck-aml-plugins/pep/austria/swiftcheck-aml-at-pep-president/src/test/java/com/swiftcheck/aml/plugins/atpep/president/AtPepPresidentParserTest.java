package com.swiftcheck.aml.plugins.atpep.president;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page réelle de la biographie du Président (bundespraesident.at, 5 novembre 2026). */
class AtPepPresidentParserTest {

    private static final Path PAGE = Path.of("src/test/resources/lebenslauf.html");

    @Test
    void readsTheOfficialBiography() throws Exception {
        AtPepPresidentParser.President p = AtPepPresidentParser.read(PAGE, AtPepPresidentParser.DEFAULT_PAGE_URL);

        assertEquals("Alexander Van der Bellen", p.name());
        assertEquals("Alexander", p.firstName());
        assertEquals("Van der Bellen", p.lastName());
        assertEquals("1944-01-18", p.birthDate());
        assertEquals("Wien", p.birthPlace());
    }

    @Test
    void writesThePersonThePositionAndTheCurrentMandate() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(AtPepPresidentParser.ID_PREFIX, AtPepPresidentParser.TOPIC);

        new AtPepPresidentParser().convert(
                AtPepPresidentParser.read(PAGE, AtPepPresidentParser.DEFAULT_PAGE_URL), dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        FtmEntity person = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("Alexander Van der Bellen"), person.getValues("name"));
        assertEquals(Set.of("1944-01-18"), person.getValues("birthDate"));
        assertEquals(Set.of("at"), person.getValues("nationality"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        FtmEntity mandate = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("current"), mandate.getValues("status"));
        FtmEntity position = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).findFirst().orElseThrow();
        assertTrue(position.getValues("topics").contains("gov.head"));
    }

    @Test
    void sourceRecordsGiveTheIdYenteMustKnow(@TempDir Path dir) throws Exception {
        List<SourceRecord> records = new AtPepPresidentParser().readSourceRecords(
                new ParserContext("at_pep_president", PAGE, dir, null, Map.of()));

        assertEquals(1, records.size());
        assertEquals("Alexander Van der Bellen", records.get(0).name());
    }

    @Test
    void refusesAPageThatIsNotTheBiography() {
        assertThrows(IllegalStateException.class, () -> AtPepPresidentParser.read("<html><body>Fehler 404</body></html>", "x"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AtPepPresidentParser.read("<html><body>Bundespräsident. Ohne Lebenslauf.</body></html>", "x"));
        assertTrue(e.getMessage().contains("geboren"));
    }

    @Test
    void explainsWhatToConfigureWithoutASourceFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new AtPepPresidentParser()
                .parse(new ParserContext("at_pep_president", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("HTTP_FILE"));
    }

    @SuppressWarnings("unused")
    private static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
