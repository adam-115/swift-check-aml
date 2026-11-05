package com.swiftcheck.aml.plugins.espep.eucommission;

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

/**
 * Fiches réelles de commission.europa.eu : Teresa Ribera (Espagne, novembre 2026), Stéphane Séjourné (France) et
 * Kaja Kallas (Estonie, octobre 2026).
 */
class EsPepEuCommissionParserTest {

    @Test
    void readsTheSpanishCommissionersPage() throws Exception {
        EsPepEuCommissionParser.Commissioner c = EsPepEuCommissionParser.read(resource("teresa-ribera.html"));

        assertEquals("Teresa Ribera", c.name());
        assertEquals("Teresa", c.firstName());
        assertEquals("Ribera", c.lastName());
        assertEquals("Executive vice-president", c.role());
        assertEquals("Clean, Just and Competitive Transition", c.portfolio());
        assertEquals("2024", c.startYear());
        assertEquals("2029", c.endYear());
        assertEquals("https://commission.europa.eu/sites/default/files/styles/ewcms_metatag_image/public/2024-11/"
                + "teresa-ribera-rodriguez-991x991.jpg?h=09af3d30&itok=xuByHPQ9", c.photo());
    }

    @Test
    void refusesAnotherCommissionersPage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EsPepEuCommissionParser.read(resource("kaja-kallas.html")));
        assertTrue(e.getMessage().contains("commissaire espagnol"));
        assertTrue(e.getMessage().contains("Estonia"), "la description est citée");
    }

    @Test
    void refusesTheFrenchCommissionersPage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EsPepEuCommissionParser.read(resource("stephane-sejourne.html")));
        assertTrue(e.getMessage().contains("commissaire espagnol"));
    }

    @Test
    void writesThePersonTheFunctionAndTheMandate(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource("teresa-ribera.html"));

        ParserResult result = new EsPepEuCommissionParser()
                .parse(new ParserContext("es_pep_eu_commission", input, dir, dir.resolve("out.ndjson"), Map.of()));
        List<SourceRecord> records = new EsPepEuCommissionParser()
                .readSourceRecords(new ParserContext("es_pep_eu_commission", input, null, null, Map.of()));

        assertEquals(3, result.entityCount());
        assertEquals(0, result.rejectedCount());
        FtmEntity person = result.entities().stream().filter(e -> e.getSchema().equals("Person")).findFirst().orElseThrow();
        assertEquals(Set.of("es"), person.getValues("nationality"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        assertTrue(person.getValues("position").contains("Executive vice-president – Clean, Just and Competitive Transition"));
        FtmEntity mandate = result.entities().stream().filter(e -> e.getSchema().equals("Occupancy")).findFirst().orElseThrow();
        assertEquals(Set.of("2024"), mandate.getValues("startDate"));
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(List.of(person.getId()), records.stream().map(SourceRecord::entityId).toList());
    }

    private static String resource(String file) throws Exception {
        try (InputStream in = EsPepEuCommissionParserTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
