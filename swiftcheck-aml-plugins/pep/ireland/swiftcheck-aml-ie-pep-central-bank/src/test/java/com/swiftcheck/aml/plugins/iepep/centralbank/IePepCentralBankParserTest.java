package com.swiftcheck.aml.plugins.iepep.centralbank;

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
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page réelle « The Commission » de centralbank.ie (5 novembre 2026). */
class IePepCentralBankParserTest {

    @Test
    void readsTheTenMembersAndSkipsTheSecretary() throws Exception {
        List<IePepCentralBankParser.Member> members = IePepCentralBankParser.read(resource("commission.html"));

        assertEquals(10, members.size());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Gabriel Makhlouf") && "Governor".equals(m.role()) && m.exOfficio()));
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Shay Cody") && m.role() == null && !m.exOfficio()),
                "membre nommé, sans titre");
        assertTrue(members.stream().anyMatch(m -> m.name().equals("John Hogan")
                && "Secretary General of the Department of Finance".equals(m.role()) && m.exOfficio()),
                "nom et titre dans le même h3");
        assertTrue(members.stream().noneMatch(m -> m.name().contains("Mahon")), "la secrétaire n'est pas membre");
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource("commission.html"));

        ParserResult result = new IePepCentralBankParser()
                .parse(new ParserContext("ie_pep_central_bank", input, dir, dir.resolve("out.ndjson"), Map.of()));

        assertEquals(0, result.rejectedCount());
        FtmEntity governor = result.entities().stream().filter(e -> e.getSchema().equals("Person")
                && e.getValues("name").contains("Gabriel Makhlouf")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), governor.getValues("topics"));
        assertTrue(governor.getValues("position").contains("Governor"));
        assertEquals(10, result.entities().stream().filter(e -> e.getSchema().equals("Person")).count());
    }

    @Test
    void failsClearlyWhenThePageHasNoMember() {
        assertThrows(IllegalStateException.class, () -> IePepCentralBankParser.read("<html></html>"));
    }

    @Test
    void explainsWhatToConfigureWithoutASourceFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new IePepCentralBankParser()
                .parse(new ParserContext("ie_pep_central_bank", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("HTTP_FILE"));
    }

    private static String resource(String name) {
        try (InputStream in = IePepCentralBankParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
