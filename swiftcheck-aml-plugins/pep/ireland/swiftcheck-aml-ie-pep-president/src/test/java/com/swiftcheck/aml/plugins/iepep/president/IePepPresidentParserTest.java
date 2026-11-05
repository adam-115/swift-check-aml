package com.swiftcheck.aml.plugins.iepep.president;

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

/** Pages réelles de president.ie (5 novembre 2026) : Catherine Connolly (en fonction) et Michael D. Higgins (ancien). */
class IePepPresidentParserTest {

    @Test
    void readsThePresidentInOffice() throws Exception {
        IePepPresidentParser.President p = IePepPresidentParser.read(resource("catherine-connolly.html"), "https://president.ie/x");

        assertEquals("Catherine Connolly", p.name());
        assertEquals("Catherine", p.firstName());
        assertEquals("Connolly", p.lastName());
        assertEquals("2025", p.startYear());
    }

    @Test
    void refusesAFormerPresidentsPage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> IePepPresidentParser.read(resource("michael-d-higgins.html"), "https://president.ie/x"));
        assertTrue(e.getMessage().contains("ancien Président"));
        assertTrue(e.getMessage().contains("2011"));
    }

    @Test
    void writesThePersonThePositionAndTheMandate(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource("catherine-connolly.html"));

        ParserResult result = new IePepPresidentParser()
                .parse(new ParserContext("ie_pep_president", input, dir, dir.resolve("out.ndjson"), Map.of()));
        List<SourceRecord> records = new IePepPresidentParser()
                .readSourceRecords(new ParserContext("ie_pep_president", input, null, null, Map.of()));

        assertEquals(0, result.rejectedCount());
        FtmEntity person = result.entities().stream().filter(e -> e.getSchema().equals("Person")).findFirst().orElseThrow();
        assertEquals(Set.of("ie"), person.getValues("nationality"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        FtmEntity mandate = result.entities().stream().filter(e -> e.getSchema().equals("Occupancy")).findFirst().orElseThrow();
        assertEquals(Set.of("2025"), mandate.getValues("startDate"));
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(List.of(person.getId()), records.stream().map(SourceRecord::entityId).toList());
    }

    @Test
    void explainsWhatToConfigureWithoutASourceFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new IePepPresidentParser()
                .parse(new ParserContext("ie_pep_president", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("HTTP_FILE"));
    }

    private static String resource(String name) {
        try (InputStream in = IePepPresidentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
