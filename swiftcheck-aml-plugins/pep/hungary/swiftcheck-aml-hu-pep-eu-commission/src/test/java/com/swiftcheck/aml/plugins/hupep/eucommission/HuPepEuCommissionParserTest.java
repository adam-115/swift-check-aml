package com.swiftcheck.aml.plugins.hupep.eucommission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Fiches réelles de commission.europa.eu (6 novembre 2026) : Olivér Várhelyi (Hongrie) et dan-jorgensen (refusée). */
class HuPepEuCommissionParserTest {

    @Test
    void readsTheCommissionersPage() throws Exception {
        HuPepEuCommissionParser.Commissioner c = HuPepEuCommissionParser.read(resource("oliver-varhelyi.html"));

        assertEquals("Olivér Várhelyi", c.name());
        assertEquals("Olivér", c.firstName());
        assertEquals("2024", c.startYear());
        assertEquals("2029", c.endYear());
        assertTrue(c.pageUrl().contains("oliver-varhelyi"), c.pageUrl());
    }

    @Test
    void refusesAnotherCommissionersPage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> HuPepEuCommissionParser.read(resource("dan-jorgensen.html")));
        assertTrue(e.getMessage().contains("commissaire hongrois"));
    }

    @Test
    void writesThePersonTheFunctionAndTheMandate(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource("oliver-varhelyi.html"));

        ParserResult result = new HuPepEuCommissionParser()
                .parse(new ParserContext("hu_pep_eu_commission", input, dir, dir.resolve("out.ndjson"), Map.of()));
        List<SourceRecord> records = new HuPepEuCommissionParser()
                .readSourceRecords(new ParserContext("hu_pep_eu_commission", input, null, null, Map.of()));

        assertEquals(0, result.rejectedCount());
        FtmEntity person = result.entities().stream().filter(e -> e.getSchema().equals("Person")).findFirst().orElseThrow();
        assertEquals(Set.of("hu"), person.getValues("nationality"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        FtmEntity mandate = result.entities().stream().filter(e -> e.getSchema().equals("Occupancy")).findFirst().orElseThrow();
        assertEquals(Set.of("2024"), mandate.getValues("startDate"));
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(List.of(person.getId()), records.stream().map(SourceRecord::entityId).toList());
    }

    @Test
    void explainsWhatToConfigureWithoutASourceFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new HuPepEuCommissionParser()
                .parse(new ParserContext("hu_pep_eu_commission", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("HTTP_FILE"));
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = HuPepEuCommissionParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
