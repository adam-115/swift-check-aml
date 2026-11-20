package com.swiftcheck.aml.plugins.depep.defencesupport;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;

/**
 * Exécution réelle sur bundeswehr.de avec les paramètres d'un fichier .properties (ceux de la table parser) :
 * {@code mvn test -Dtest=DePepDefenceSupportParserLiveTest -DliveParams=<fichier>}.
 */
class DePepDefenceSupportParserLiveTest {

    @Test
    @EnabledIfSystemProperty(named = "liveParams", matches = ".+")
    void liveRun(@TempDir Path tempDir) throws Exception {
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of(System.getProperty("liveParams")))) {
            props.load(r);
        }
        Map<String, String> params = new LinkedHashMap<>();
        props.forEach((k, v) -> params.put(k.toString(), v.toString()));
        Path out = tempDir.resolve("out.ndjson");
        ParserContext ctx = new ParserContext("live", null, tempDir.resolve("raw"), out, params);
        ParserResult result = new DePepDefenceSupportParser().parse(ctx);
        System.out.println("RESULT " + result.message());
        List<String> lines = Files.readAllLines(out);
        lines.stream().filter(l -> l.contains("\"schema\":\"Person\"")).forEach(l -> System.out.println("PERSON " + l));
    }
}
