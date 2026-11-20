package com.swiftcheck.aml.plugins.depep.president;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;

/** Exécution réelle sur bundespraesident.de : {@code mvn test -Dlive=true -Dtest=DePepPresidentParserLiveTest}. */
class DePepPresidentParserLiveTest {

    @Test
    @EnabledIfSystemProperty(named = "live", matches = "true")
    void liveRun(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("out.ndjson");
        ParserResult r = new DePepPresidentParser().parse(new ParserContext("de_pep_president", null, tempDir.resolve("raw"), out, Map.of()));
        System.out.println("RESULT " + r.message());
        Files.readAllLines(out).forEach(l -> System.out.println("LINE " + l));
    }
}
