package com.swiftcheck.aml.plugins.nopep.norgesbank;

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
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page réelle « The Executive Board » de Norges Bank (6 novembre 2026). */
class NoPepNorgesBankParserTest {

    @Test
    void readsGovernorDeputyGovernorsExternalMembersAndEmployeeRepresentatives() {
        List<NoPepNorgesBankParser.Member> members = NoPepNorgesBankParser.read(resource("executive-board.html"));

        assertEquals(11, members.size(), "gouverneur, 2 sous-gouverneurs, 6 membres externes, 2 représentants du personnel");
        var governor = members.stream().filter(m -> m.name().equals("Ida Wolden Bache")).findFirst().orElseThrow();
        assertEquals("Gouverneur (Governor)", governor.role());
        assertEquals(LocalDate.of(2022, 4, 8), governor.since());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Pål Longva") && m.role().startsWith("Sous-gouverneur")), "å décodé");
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Hans Aasnæs") && m.role().equals("Membre du conseil de direction")));
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Synne Pretorius") && m.role().startsWith("Représentant du personnel")
                && LocalDate.of(2026, 5, 16).equals(m.since())));
        assertTrue(members.stream().noneMatch(m -> m.name().contains("Baardseng") || m.name().contains("Wehler")), "suppléants exclus");
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource("executive-board.html"));

        ParserResult result = new NoPepNorgesBankParser()
                .parse(new ParserContext("no_pep_norges_bank", input, dir, dir.resolve("out.ndjson"), Map.of()));

        assertEquals(0, result.rejectedCount());
        FtmEntity governor = result.entities().stream().filter(e -> e.getSchema().equals("Person")
                && e.getValues("name").contains("Ida Wolden Bache")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), governor.getValues("topics"));
        assertEquals(11, result.entities().stream().filter(e -> e.getSchema().equals("Person")).count());
    }

    @Test
    void failsClearlyWhenThePageHasNoMemberList() {
        assertThrows(IllegalStateException.class, () -> NoPepNorgesBankParser.read("<html><body>Verifying</body></html>"));
    }

    @Test
    void explainsWhatToConfigureWithoutASourceFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new NoPepNorgesBankParser()
                .parse(new ParserContext("no_pep_norges_bank", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("HTTP_FILE"));
    }

    private static String resource(String name) {
        try (InputStream in = NoPepNorgesBankParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
