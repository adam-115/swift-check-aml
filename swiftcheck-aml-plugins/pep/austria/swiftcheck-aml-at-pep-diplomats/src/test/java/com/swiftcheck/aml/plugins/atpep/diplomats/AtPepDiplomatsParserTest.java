package com.swiftcheck.aml.plugins.atpep.diplomats;

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
import com.swiftcheck.aml.plugins.atpep.diplomats.AtPepDiplomatsParser.Diplomat;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class AtPepDiplomatsParserTest {

    private static final String SAMPLE_HTML = """
            <html><body>
            <select class="form-control" name="tx_bmeiadb_piresults[query]">
                <option value="46">Frankreich</option>
                <option value="198">Ägypten</option>
            </select>
            <div class="row">
                <div class="col-lg-3">Leitung:</div>
                <div class="col-lg-9">
                    PÖSTINGER Georg, Mag. | ao. und bev. Botschafter
                </div>
            </div>
            <div class="row">
                <div class="col-lg-3">Leitung:</div>
                <div class="col-lg-9">
                    KAUDEL-JENSEN Barbara, MA | ao. und bev. Botschafterin
                </div>
            </div>
            <div class="row">
                <div class="col-lg-3">Leitung:</div>
                <div class="col-lg-9">
                    CUTTOLI Chantal | Honorarkonsulin
                </div>
            </div>
            </body></html>
            """;

    @Test
    void testExtractCountries() {
        Map<String, String> countries = AtPepDiplomatsParser.extractCountries(SAMPLE_HTML);
        assertEquals(2, countries.size());
        assertEquals("Frankreich", countries.get("46"));
        assertEquals("Ägypten", countries.get("198"));
    }

    @Test
    void testParsePageExcludesHonoraryAndExtractsAmbassadors() {
        List<Diplomat> list = AtPepDiplomatsParser.parsePage(SAMPLE_HTML, "Frankreich", "http://example.com");
        assertEquals(2, list.size());

        Diplomat d1 = list.get(0);
        assertEquals("Georg Pöstinger", d1.name());
        assertEquals("Georg", d1.firstName());
        assertEquals("Pöstinger", d1.lastName());
        assertTrue(d1.role().contains("Ambassadeur"));

        Diplomat d2 = list.get(1);
        assertEquals("Barbara Kaudel-Jensen", d2.name());
        assertEquals("Barbara", d2.firstName());
        assertEquals("Kaudel-Jensen", d2.lastName());
    }

    @Test
    void testConvertAndWrite(@TempDir Path tempDir) throws Exception {
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        Files.writeString(rawDir.resolve("bmeia_country_46.html"), SAMPLE_HTML);

        Path outFile = tempDir.resolve("out.ndjson");
        ParserContext ctx = new ParserContext("at_pep_diplomats", null, rawDir, outFile, Map.of());

        AtPepDiplomatsParser parser = new AtPepDiplomatsParser();
        ParserResult result = parser.parse(ctx);

        assertTrue(Files.exists(outFile));
        List<String> lines = Files.readAllLines(outFile);
        assertFalse(lines.isEmpty());
        assertTrue(result.message().contains("ambassadeurs"));
    }
}

