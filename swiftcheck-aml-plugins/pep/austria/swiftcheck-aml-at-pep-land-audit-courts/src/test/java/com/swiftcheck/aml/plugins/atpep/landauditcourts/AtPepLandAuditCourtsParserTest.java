package com.swiftcheck.aml.plugins.atpep.landauditcourts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;

class AtPepLandAuditCourtsParserTest {

    @Test
    void testReadSourceRecords() throws Exception {
        AtPepLandAuditCourtsParser parser = new AtPepLandAuditCourtsParser();
        List<SourceRecord> records = parser.readSourceRecords(new ParserContext("at_pep_land_audit_courts", null, null, null, Map.of()));
        assertEquals(9, records.size());
        assertTrue(records.stream().anyMatch(r -> r.name().equals("Werner Sedlak")));
        assertTrue(records.stream().anyMatch(r -> r.name().equals("Edith Goldeband")));
    }

    @Test
    void testParseAndWrite(@TempDir Path tempDir) throws Exception {
        Path rawDir = tempDir.resolve("raw");
        Path outFile = tempDir.resolve("out.ndjson");
        ParserContext ctx = new ParserContext("at_pep_land_audit_courts", null, rawDir, outFile, Map.of());

        AtPepLandAuditCourtsParser parser = new AtPepLandAuditCourtsParser();
        ParserResult result = parser.parse(ctx);

        assertTrue(Files.exists(outFile));
        List<String> lines = Files.readAllLines(outFile);
        assertFalse(lines.isEmpty());
        assertTrue(result.message().contains("9 directeurs"));
    }
}

