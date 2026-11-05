package com.swiftcheck.aml.plugins.ptpep.president;

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

/** Page réelle de la biographie du Président (presidencia.pt, 5 novembre 2026). */
class PtPepPresidentParserTest {

    private static final Path PAGE = Path.of("src/test/resources/presidencia-biografia.html");

    @Test
    void readsTheOfficialBiography() throws Exception {
        PtPepPresidentParser.President p = PtPepPresidentParser.read(PAGE, PtPepPresidentParser.DEFAULT_PAGE_URL);

        assertEquals("António José Martins Seguro", p.name());
        assertEquals("António", p.firstName());
        assertEquals("Seguro", p.lastName());
        assertEquals("1962-03-11", p.birthDate());
        assertEquals("Penamacor", p.birthPlace());
    }

    @Test
    void writesThePersonThePositionAndTheCurrentMandate() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(PtPepPresidentParser.ID_PREFIX, PtPepPresidentParser.TOPIC);

        new PtPepPresidentParser().convert(
                PtPepPresidentParser.read(PAGE, PtPepPresidentParser.DEFAULT_PAGE_URL), dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        FtmEntity person = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("António José Martins Seguro"), person.getValues("name"));
        assertEquals(Set.of("1962-03-11"), person.getValues("birthDate"));
        assertEquals(Set.of("pt"), person.getValues("nationality"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        FtmEntity mandate = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("current"), mandate.getValues("status"));
        FtmEntity position = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).findFirst().orElseThrow();
        assertTrue(position.getValues("topics").contains("gov.head"));
    }

    @Test
    void sourceRecordsGiveTheIdYenteMustKnow(@TempDir Path dir) throws Exception {
        List<SourceRecord> records = new PtPepPresidentParser().readSourceRecords(
                new ParserContext("pt_pep_president", PAGE, dir, null, Map.of()));

        assertEquals(1, records.size());
        assertEquals("António José Martins Seguro", records.get(0).name());
    }

    @Test
    void refusesAPageThatIsNotTheBiography() {
        assertThrows(IllegalStateException.class, () -> PtPepPresidentParser.read("<html><body>Erro 404</body></html>", "x"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PtPepPresidentParser.read("<html><body>Presidente da República. Sem biografia.</body></html>", "x"));
        assertTrue(e.getMessage().contains("nasceu"));
    }

    @Test
    void explainsWhatToConfigureWithoutASourceFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new PtPepPresidentParser()
                .parse(new ParserContext("pt_pep_president", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("HTTP_FILE"));
    }

    @SuppressWarnings("unused")
    private static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
