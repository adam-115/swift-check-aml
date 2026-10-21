package com.swiftcheck.aml.plugins.lupep.europarlformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;

class LuPepEuroparlFormerParserTest {

    @TempDir
    Path tempDir;

    private Path term9File;
    private Path semedoFile;
    private Path outgoingFile;

    @BeforeEach
    void setUp() {
        term9File = Path.of("src/test/resources/europarl_term9.json");
        semedoFile = Path.of("src/test/resources/mep_semedo.json");
        outgoingFile = Path.of("src/test/resources/meps_outgoing.json");
    }

    @Test
    void parseTerm9ExcludesCurrentAndRetainsFormerMeps() throws Exception {
        assertTrue(Files.exists(term9File), "Le fichier test europarl_term9.json doit exister");

        Path outputFile = tempDir.resolve("output_term9.ndjson");
        // yearsAfterEnd = 10 pour retenir la 9e législature (2019-2024), et IDs des députés en fonction actuels
        ParserContext ctx = new ParserContext("lu_pep_europarl_former", term9File, null, outputFile,
                Map.of("yearsAfterEnd", "10", "currentMepIds", "202073,840,193292"));

        LuPepEuroparlFormerParser parser = new LuPepEuroparlFormerParser();
        ParserResult result = parser.parse(ctx);

        assertNotNull(result);
        assertTrue(result.entityCount() > 0, "Des entités FtM doivent être générées");
        assertTrue(Files.exists(outputFile));

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertNotNull(records);
        assertFalse(records.isEmpty(), "Des anciens eurodéputés doivent être renvoyés");

        // Vérifier que les députés actuellement en fonction (10e législature) sont exclus
        boolean hasMarcAngel = records.stream().anyMatch(r -> r.entityId().contains("202073"));
        boolean hasGoerens = records.stream().anyMatch(r -> r.entityId().contains("840"));
        boolean hasMetz = records.stream().anyMatch(r -> r.entityId().contains("193292"));
        assertFalse(hasMarcAngel, "Marc ANGEL (en fonction) ne doit pas être inclus dans les anciens eurodéputés");
        assertFalse(hasGoerens, "Charles GOERENS (en fonction) ne doit pas être inclus dans les anciens eurodéputés");
        assertFalse(hasMetz, "Tilly METZ (en fonction) ne doit pas être incluse dans les anciens eurodéputés");

        // Vérifier que les anciens eurodéputés sont bien présents
        boolean hasSemedo = records.stream().anyMatch(r -> r.entityId().contains("197418"));
        boolean hasSchmit = records.stream().anyMatch(r -> r.entityId().contains("197428"));
        boolean hasHansen = records.stream().anyMatch(r -> r.entityId().contains("193419"));
        assertTrue(hasSemedo, "Monica SEMEDO doit être présente");
        assertTrue(hasSchmit, "Nicolas SCHMIT doit être présent");
        assertTrue(hasHansen, "Christophe HANSEN doit être présent");
    }

    @Test
    void parseDetailedMepJsonExtractsCorrectFields() throws Exception {
        assertTrue(Files.exists(semedoFile), "Le fichier test mep_semedo.json doit exister");

        Path outputFile = tempDir.resolve("output_semedo.ndjson");
        ParserContext ctx = new ParserContext("lu_pep_europarl_former", semedoFile, null, outputFile,
                Map.of("yearsAfterEnd", "5"));

        LuPepEuroparlFormerParser parser = new LuPepEuroparlFormerParser();
        ParserResult result = parser.parse(ctx);

        assertNotNull(result);
        assertTrue(result.entityCount() >= 3, "Doit contenir Person, Position et Occupancy");

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertEquals(1, records.size());
        assertEquals("Monica SEMEDO", records.get(0).name());
        assertTrue(records.get(0).entityId().contains("197418"));
    }

    @Test
    void parseOutgoingMepJsonCorrectlyProcessed() throws Exception {
        assertTrue(Files.exists(outgoingFile), "Le fichier test meps_outgoing.json doit exister");

        Path outputFile = tempDir.resolve("output_outgoing.ndjson");
        ParserContext ctx = new ParserContext("lu_pep_europarl_former", outgoingFile, null, outputFile,
                Map.of("yearsAfterEnd", "5"));

        LuPepEuroparlFormerParser parser = new LuPepEuroparlFormerParser();
        ParserResult result = parser.parse(ctx);

        assertNotNull(result);
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertEquals(1, records.size());
        assertEquals("Christophe HANSEN", records.get(0).name());
    }

    @Test
    void parseUsesDynamicSourceUrlFromContextParameters() throws Exception {
        String customUrl = "https://custom.europarl.europa.eu/meps/custom";
        Path outputFile = tempDir.resolve("output_custom.ndjson");
        ParserContext ctx = new ParserContext("lu_pep_europarl_former", semedoFile, null, outputFile,
                Map.of("yearsAfterEnd", "5", "sourceUrl", customUrl));

        LuPepEuroparlFormerParser parser = new LuPepEuroparlFormerParser();
        List<SourceRecord> records = parser.readSourceRecords(ctx);

        assertFalse(records.isEmpty());
        assertEquals(customUrl, records.get(0).sourceRef(), "L'URL source doit provenir du ParserContext");
    }

    @Test
    void strictCutoffYearsExcludesOlderMandates() throws Exception {
        Path outputFile = tempDir.resolve("output_strict.ndjson");
        // yearsAfterEnd = 1 an -> mandat terminé en 2019 (Nicolas Schmit, > 5 ans) doit être exclu
        ParserContext ctx = new ParserContext("lu_pep_europarl_former", term9File, null, outputFile,
                Map.of("yearsAfterEnd", "1"));

        LuPepEuroparlFormerParser parser = new LuPepEuroparlFormerParser();
        List<SourceRecord> records = parser.readSourceRecords(ctx);

        boolean hasSchmit = records.stream().anyMatch(r -> r.entityId().contains("197428"));
        assertFalse(hasSchmit, "Nicolas SCHMIT (fin 2019) doit être exclu avec un seuil de 1 an");
    }
}
