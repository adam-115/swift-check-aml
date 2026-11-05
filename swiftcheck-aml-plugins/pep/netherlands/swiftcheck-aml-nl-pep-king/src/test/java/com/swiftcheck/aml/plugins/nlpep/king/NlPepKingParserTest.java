package com.swiftcheck.aml.plugins.nlpep.king;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Pages réelles de koninklijkhuis.nl (5 novembre 2026) : le Roi Willem-Alexander et la Reine Máxima. */
class NlPepKingParserTest {

    @Test
    void readsTheKingAndTheDateOfAccession() {
        NlPepKingParser.King k = NlPepKingParser.read(resource("koning-willem-alexander.html"), "https://x");

        assertEquals("Willem-Alexander", k.name());
        assertEquals("Koning", k.title());
        assertEquals(LocalDate.of(2013, 4, 30), k.accession());
    }

    @Test
    void refusesThePageOfAnotherMemberOfTheRoyalHouse() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NlPepKingParser.read(resource("koningin-maxima.html"), "https://x"));
        assertTrue(e.getMessage().contains("chef d'État"));
    }

    @Test
    void writesThePersonThePositionAndTheMandate(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource("koning-willem-alexander.html"));

        ParserResult result = new NlPepKingParser()
                .parse(new ParserContext("nl_pep_king", input, dir, dir.resolve("out.ndjson"), Map.of()));
        List<SourceRecord> records = new NlPepKingParser()
                .readSourceRecords(new ParserContext("nl_pep_king", input, null, null, Map.of()));

        assertEquals(0, result.rejectedCount());
        FtmEntity person = result.entities().stream().filter(e -> e.getSchema().equals("Person")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        assertTrue(person.getValues("alias").contains("Koning Willem-Alexander"));
        FtmEntity mandate = result.entities().stream().filter(e -> e.getSchema().equals("Occupancy")).findFirst().orElseThrow();
        assertEquals(Set.of("2013-04-30"), mandate.getValues("startDate"));
        assertEquals(List.of(person.getId()), records.stream().map(SourceRecord::entityId).toList());
    }

    @Test
    void explainsWhatToConfigureWithoutASourceFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new NlPepKingParser()
                .parse(new ParserContext("nl_pep_king", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("HTTP_FILE"));
    }

    private static String resource(String name) {
        try (InputStream in = NlPepKingParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
