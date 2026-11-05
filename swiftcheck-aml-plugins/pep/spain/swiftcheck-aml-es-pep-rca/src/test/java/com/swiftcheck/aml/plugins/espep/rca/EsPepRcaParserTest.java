package com.swiftcheck.aml.plugins.espep.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class EsPepRcaParserTest {

    @Test
    void testConvert(@TempDir Path rawDir) throws Exception {
        copy(rawDir, "peps.json");
        copy(rawDir, "relatives.json");

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepRcaParser.ID_PREFIX);
        EsPepRcaParser.Report report = new EsPepRcaParser().convert(rawDir, dataset);

        assertTrue(report.peps() > 0);
        assertTrue(report.relatives() > 0);
        assertTrue(report.links() > 0);

        ParserContext ctx = new ParserContext("es_pep_rca", null, rawDir, null, Map.of());
        List<SourceRecord> records = new EsPepRcaParser().readSourceRecords(ctx);
        assertEquals(report.relatives(), records.size());
    }

    private static void copy(Path dir, String file) throws Exception {
        try (InputStream in = EsPepRcaParserTest.class.getResourceAsStream("/wikidata/" + file)) {
            if (in == null) {
                throw new IllegalArgumentException("/wikidata/" + file + " introuvable dans le classpath.");
            }
            Files.copy(in, dir.resolve(file));
        }
    }
}

