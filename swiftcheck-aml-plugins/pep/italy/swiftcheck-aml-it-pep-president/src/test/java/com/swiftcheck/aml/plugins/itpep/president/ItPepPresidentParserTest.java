package com.swiftcheck.aml.plugins.itpep.president;

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
class ItPepPresidentParserTest {

    private static final Path PAGE = Path.of("src/test/resources/biografia.html");

    @Test
    void readsTheOfficialBiography() throws Exception {
        ItPepPresidentParser.President p = ItPepPresidentParser.read(PAGE, ItPepPresidentParser.DEFAULT_PAGE_URL);

        assertEquals("Sergio Mattarella", p.name());
        assertEquals("Sergio", p.firstName());
        assertEquals("Mattarella", p.lastName());
        assertEquals("1941-07-23", p.birthDate());
        assertEquals("Palermo", p.birthPlace());
    }

    @Test
    void writesThePersonThePositionAndTheCurrentMandate() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ItPepPresidentParser.ID_PREFIX, ItPepPresidentParser.TOPIC);

        new ItPepPresidentParser().convert(
                ItPepPresidentParser.read(PAGE, ItPepPresidentParser.DEFAULT_PAGE_URL), dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        FtmEntity person = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("Sergio Mattarella"), person.getValues("name"));
        assertEquals(Set.of("1941-07-23"), person.getValues("birthDate"));
        assertEquals(Set.of("it"), person.getValues("nationality"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        FtmEntity mandate = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("current"), mandate.getValues("status"));
        FtmEntity position = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).findFirst().orElseThrow();
        assertTrue(position.getValues("topics").contains("gov.head"));
    }

    @Test
    void sourceRecordsGiveTheIdYenteMustKnow(@TempDir Path dir) throws Exception {
        List<SourceRecord> records = new ItPepPresidentParser().readSourceRecords(
                new ParserContext("it_pep_president", PAGE, dir, null, Map.of()));

        assertEquals(1, records.size());
        assertEquals("Sergio Mattarella", records.get(0).name());
    }

    @Test
    void refusesAPageThatIsNotTheBiography() {
        assertThrows(IllegalStateException.class, () -> ItPepPresidentParser.read("<html><body>Errore 404</body></html>", "x"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ItPepPresidentParser.read("<html><body>Presidente della Repubblica. Senza biografia.</body></html>", "x"));
        assertTrue(e.getMessage().contains("biographie"));
    }

    @Test
    void explainsWhatToConfigureWithoutASourceFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ItPepPresidentParser()
                .parse(new ParserContext("it_pep_president", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("HTTP_FILE"));
    }

    @SuppressWarnings("unused")
    private static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
