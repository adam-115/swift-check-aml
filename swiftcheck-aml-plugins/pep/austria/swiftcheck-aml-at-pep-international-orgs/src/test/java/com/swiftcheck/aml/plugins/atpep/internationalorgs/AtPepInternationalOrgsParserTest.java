package com.swiftcheck.aml.plugins.atpep.internationalorgs;

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

class AtPepInternationalOrgsParserTest {

    @Test
    void testReadSourceRecords() throws Exception {
        AtPepInternationalOrgsParser parser = new AtPepInternationalOrgsParser();
        List<SourceRecord> records = parser.readSourceRecords(new ParserContext("at_pep_international_orgs", null, null, null, Map.of()));
        assertFalse(records.isEmpty());
        assertTrue(records.stream().anyMatch(r -> r.name().contains("Ghada Waly")));
        assertTrue(records.stream().anyMatch(r -> r.name().contains("Helga Maria Schmid")));
    }

    @Test
    void testParseAndWrite(@TempDir Path tempDir) throws Exception {
        Path rawDir = tempDir.resolve("raw");
        Path outFile = tempDir.resolve("out.ndjson");
        ParserContext ctx = new ParserContext("at_pep_international_orgs", null, rawDir, outFile, Map.of());

        AtPepInternationalOrgsParser parser = new AtPepInternationalOrgsParser();
        ParserResult result = parser.parse(ctx);

        assertTrue(Files.exists(outFile));
        List<String> lines = Files.readAllLines(outFile);
        assertFalse(lines.isEmpty());
        assertTrue(result.message().contains("dirigeants d'organisations internationales"));
    }
}

