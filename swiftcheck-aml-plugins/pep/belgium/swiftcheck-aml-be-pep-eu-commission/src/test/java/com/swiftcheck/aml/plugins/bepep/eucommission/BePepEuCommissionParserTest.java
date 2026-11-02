package com.swiftcheck.aml.plugins.bepep.eucommission;

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
 * Fiches réelles de commission.europa.eu : Hadja Lahbib (Belgique, novembre 2026), Stéphane Séjourné (France) et
 * Kaja Kallas (Estonie, octobre 2026).
 */
class BePepEuCommissionParserTest {

    @Test
    void readsTheBelgianCommissionersPage() throws Exception {
        BePepEuCommissionParser.Commissioner c = BePepEuCommissionParser.read(resource("hadja-lahbib.html"));

        assertEquals("Hadja Lahbib", c.name());
        assertEquals("Hadja", c.firstName());
        assertEquals("Lahbib", c.lastName());
        assertEquals("Commissaire", c.role());
        assertEquals("Égalité, état de préparation et gestion des crises", c.portfolio());
        assertEquals("2024", c.startYear());
        assertEquals("2029", c.endYear());
        assertEquals("https://commission.europa.eu/sites/default/files/styles/ewcms_metatag_image/public/2024-11/"
                + "hadja-lahbib-991x991.jpg?h=33760c2f&itok=eR1soIXz", c.photo());
    }

    @Test
    void refusesAnotherCommissionersPage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BePepEuCommissionParser.read(resource("kaja-kallas.html")));
        assertTrue(e.getMessage().contains("commissaire belge"));
        assertTrue(e.getMessage().contains("Estonia"), "la description est citée");
    }

    @Test
    void refusesTheFrenchCommissionersPage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BePepEuCommissionParser.read(resource("stephane-sejourne.html")));
        assertTrue(e.getMessage().contains("commissaire belge"));
    }

    @Test
    void writesThePersonTheFunctionAndTheMandate(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource("hadja-lahbib.html"));

        ParserResult result = new BePepEuCommissionParser()
                .parse(new ParserContext("be_pep_eu_commission", input, dir, dir.resolve("out.ndjson"), Map.of()));
        List<SourceRecord> records = new BePepEuCommissionParser()
                .readSourceRecords(new ParserContext("be_pep_eu_commission", input, null, null, Map.of()));

        assertEquals(3, result.entityCount());
        assertEquals(0, result.rejectedCount());
        FtmEntity person = result.entities().stream().filter(e -> e.getSchema().equals("Person")).findFirst().orElseThrow();
        assertEquals(Set.of("be"), person.getValues("nationality"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        assertTrue(person.getValues("position").contains("Commissaire – Égalité, état de préparation et gestion des crises"));
        FtmEntity mandate = result.entities().stream().filter(e -> e.getSchema().equals("Occupancy")).findFirst().orElseThrow();
        assertEquals(Set.of("2024"), mandate.getValues("startDate"));
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(List.of(person.getId()), records.stream().map(SourceRecord::entityId).toList());
    }

    private static String resource(String file) throws Exception {
        try (InputStream in = BePepEuCommissionParserTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
