package com.swiftcheck.aml.plugins.frpep.eucommission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Fiches réelles de commission.europa.eu (octobre 2026) : Stéphane Séjourné (France) et Kaja Kallas (Estonie). */
class FrPepEuCommissionParserTest {

    @Test
    void readsTheFrenchCommissionersPage() throws Exception {
        FrPepEuCommissionParser.Commissioner c = FrPepEuCommissionParser.read(resource("stephane-sejourne.html"));

        assertEquals("Stéphane Séjourné", c.name());
        assertEquals("Stéphane", c.firstName());
        assertEquals("Séjourné", c.lastName());
        assertEquals("Vice-président exécutif", c.role());
        assertEquals("Prospérité et stratégie industrielle", c.portfolio());
        assertEquals("2024", c.startYear());
        assertEquals("2029", c.endYear());
    }

    @Test
    void refusesAnotherCommissionersPage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FrPepEuCommissionParser.read(resource("kaja-kallas.html")));
        assertTrue(e.getMessage().contains("commissaire français"));
        assertTrue(e.getMessage().contains("Estonia"), "la description est citée");
    }

    @Test
    void writesThePersonTheFunctionAndTheMandate(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource("stephane-sejourne.html"));

        ParserResult result = new FrPepEuCommissionParser()
                .parse(new ParserContext("fr_pep_eu_commission", input, dir, dir.resolve("out.ndjson"), Map.of()));
        List<SourceRecord> records = new FrPepEuCommissionParser()
                .readSourceRecords(new ParserContext("fr_pep_eu_commission", input, null, null, Map.of()));

        assertEquals(3, result.entityCount());
        assertEquals(0, result.rejectedCount());
        FtmEntity person = result.entities().stream().filter(e -> e.getSchema().equals("Person")).findFirst().orElseThrow();
        assertEquals(Set.of("fr"), person.getValues("nationality"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        assertTrue(person.getValues("position").contains("Vice-président exécutif – Prospérité et stratégie industrielle"));
        FtmEntity mandate = result.entities().stream().filter(e -> e.getSchema().equals("Occupancy")).findFirst().orElseThrow();
        assertEquals(Set.of("2024"), mandate.getValues("startDate"));
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(List.of(person.getId()), records.stream().map(SourceRecord::entityId).toList());
    }

    private static String resource(String file) throws Exception {
        try (InputStream in = FrPepEuCommissionParserTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
