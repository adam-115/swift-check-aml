package com.swiftcheck.aml.plugins.depep.rca;

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

class DePepRcaParserTest {

    @Test
    void testConvert(@TempDir Path rawDir) throws Exception {
        copy(rawDir, "peps.json");
        copy(rawDir, "relatives.json");

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepRcaParser.ID_PREFIX);
        DePepRcaParser.Report report = new DePepRcaParser().convert(rawDir, dataset);

        assertTrue(report.peps() > 0);
        assertTrue(report.relatives() > 0);
        assertTrue(report.links() > 0);

        ParserContext ctx = new ParserContext("de_pep_rca", null, rawDir, null, Map.of());
        List<SourceRecord> records = new DePepRcaParser().readSourceRecords(ctx);
        assertEquals(report.relatives(), records.size());
    }

    private static void copy(Path dir, String file) throws Exception {
        try (InputStream in = DePepRcaParserTest.class.getResourceAsStream("/wikidata/" + file)) {
            if (in == null) {
                throw new IllegalArgumentException("/wikidata/" + file + " introuvable dans le classpath.");
            }
            Files.copy(in, dir.resolve(file));
        }
    }
}

