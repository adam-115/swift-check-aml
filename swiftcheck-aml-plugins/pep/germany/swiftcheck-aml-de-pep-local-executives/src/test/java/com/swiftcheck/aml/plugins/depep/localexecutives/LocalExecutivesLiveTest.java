package com.swiftcheck.aml.plugins.depep.localexecutives;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;

/**
 * Exécution réelle sur les sources en ligne, avec les paramètres en base (fichier JSON passé par
 * {@code -Dlive.params=…}). Lancée à la main seulement.
 */
@EnabledIfSystemProperty(named = "live.params", matches = ".+")
class LocalExecutivesLiveTest {

    @Test
    void readsTheOfficialSources(@TempDir Path dir) throws Exception {
        Map<String, String> p = new ObjectMapper().readValue(Files.readString(Path.of(System.getProperty("live.params")), StandardCharsets.UTF_8),
                new TypeReference<Map<String, String>>() { });
        ParserResult result = new DePepLocalExecutivesParser().parse(new ParserContext("de_pep_local_executives", null, System.getProperty("live.raw") != null ? Path.of(System.getProperty("live.raw")) : dir.resolve("raw"),
                dir.resolve("out.ndjson"), p));
        System.out.println("LIVE " + result.message());
        assertThat(Files.readAllLines(dir.resolve("out.ndjson"))).isNotEmpty();
    }
}
