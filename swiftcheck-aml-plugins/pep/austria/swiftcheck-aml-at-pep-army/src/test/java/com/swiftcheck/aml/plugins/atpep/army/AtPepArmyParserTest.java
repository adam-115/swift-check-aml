package com.swiftcheck.aml.plugins.atpep.army;

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
import com.swiftcheck.aml.plugins.atpep.army.AtPepArmyParser.Officer;

class AtPepArmyParserTest {

    private static final String SAMPLE_HTML = """
            <html><body>
            <h1>Führung des Österreichischen Bundesheeres</h1>
            <p>Chef des Generalstabes ist General Mag. Harald Vodosek.</p>
            <p>Sein Stellvertreter ist Generalleutnant Bruno Hofbauer.</p>
            <p>Ehemaliger General Rudolf Striedinger ging in den Ruhestand.</p>
            </body></html>
            """;

    @Test
    void testParseHtml() {
        List<Officer> officers = AtPepArmyParser.parseHtml(SAMPLE_HTML, "http://example.com");
        assertEquals(3, officers.size());

        Officer o1 = officers.get(0);
        assertEquals("Harald Vodosek", o1.name());
        assertEquals("General", o1.rank());

        Officer o2 = officers.get(1);
        assertEquals("Bruno Hofbauer", o2.name());
        assertEquals("Generalleutnant", o2.rank());
    }

    @Test
    void testConvertAndWrite(@TempDir Path tempDir) throws Exception {
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        Files.writeString(rawDir.resolve("bundesheer_organisation.html"), SAMPLE_HTML);

        Path outFile = tempDir.resolve("out.ndjson");
        ParserContext ctx = new ParserContext("at_pep_army", null, rawDir, outFile, Map.of());

        AtPepArmyParser parser = new AtPepArmyParser();
        ParserResult result = parser.parse(ctx);

        assertTrue(Files.exists(outFile));
        List<String> lines = Files.readAllLines(outFile);
        assertFalse(lines.isEmpty());
        assertTrue(result.message().contains("officiers généraux"));
    }
}

