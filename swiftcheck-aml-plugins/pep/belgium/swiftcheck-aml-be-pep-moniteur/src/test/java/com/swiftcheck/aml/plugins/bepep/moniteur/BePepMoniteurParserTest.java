package com.swiftcheck.aml.plugins.bepep.moniteur;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class BePepMoniteurParserTest {

    private final Path testResources = Paths.get("src/test/resources/moniteur");

    @Test
    void testConvertMoniteurArticles() throws Exception {
        BePepMoniteurParser parser = new BePepMoniteurParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepMoniteurParser.ID_PREFIX, BePepMoniteurParser.TOPIC);
        LocalDate fixedToday = LocalDate.of(2026, 11, 1);
        LocalDate oldestEnd = fixedToday.minusYears(5);

        BePepMoniteurParser.Report report = parser.convert(testResources, oldestEnd, fixedToday, dataset);

        assertNotNull(report);
        assertTrue(report.articlesRead() >= 5, "Au moins 5 actes lus");
        assertTrue(report.eventsFound() >= 10, "Plusieurs événements d'actes trouvés");
        assertTrue(report.pepEvents() >= 4, "Plusieurs événements PEP identifiés (Cassation, Appel, Travail, etc.)");

        assertFalse(dataset.entities().isEmpty(), "Le dataset ne doit pas être vide");

        // Vérifier la présence de juges de cour de cassation / appel / travail
        boolean hasCassation = dataset.entities().stream()
                .anyMatch(e -> "Position".equals(e.getSchema())
                        && e.getValues("name").stream().anyMatch(n -> n.toLowerCase().contains("cassation")));
        boolean hasAppel = dataset.entities().stream()
                .anyMatch(e -> "Position".equals(e.getSchema())
                        && e.getValues("name").stream().anyMatch(n -> n.toLowerCase().contains("appel")));
        boolean hasTravail = dataset.entities().stream()
                .anyMatch(e -> "Position".equals(e.getSchema())
                        && e.getValues("name").stream().anyMatch(n -> n.toLowerCase().contains("travail")));

        assertTrue(hasCassation, "Doit contenir une fonction liée à la Cour de cassation");
        assertTrue(hasAppel, "Doit contenir une fonction liée à la Cour d'appel");
        assertTrue(hasTravail, "Doit contenir une fonction liée à la Cour du travail");
    }

    @Test
    void testReadSourceRecords() throws Exception {
        BePepMoniteurParser parser = new BePepMoniteurParser();
        ParserContext ctx = new ParserContext("be_pep_moniteur", null, testResources, null, Map.of());
        List<SourceRecord> records = parser.readSourceRecords(ctx);

        assertNotNull(records);
        assertFalse(records.isEmpty());
        for (SourceRecord r : records) {
            assertNotNull(r.entityId());
            assertNotNull(r.name());
            assertFalse(r.name().isBlank());
        }
    }
}
