package com.swiftcheck.aml.plugins.cypep.institutions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page réelle « Board of Directors » de www.centralbank.cy (8 novembre 2026), scripts retirés : le gouverneur et sept membres. */
class CyPepInstitutionsParserTest {

    @Test
    void readsTheGovernorAndTheBoardMembersWithTheirRole() {
        var members = CyPepInstitutionsParser.members(resource("board.html"));

        assertEquals(8, members.size());
        assertEquals("Christodoulos Patsalides", members.get(0).name());
        assertEquals("Governor of the Central Bank of Cyprus", members.get(0).role());
        assertEquals(2, members.stream().filter(m -> m.role().startsWith("Executive member")).count());
        assertEquals(5, members.stream().filter(m -> m.role().startsWith("Non-executive member")).count());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Evgenia Christodoulou")), "espace final de la page retiré");
    }

    @Test
    void addsTheSurnameFirstOrder() {
        assertEquals(Set.of("Patsalides Christodoulos"), CyPepInstitutionsParser.aliases("Christodoulos Patsalides"));
    }

    @Test
    void downloadsThePage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        CyPepInstitutionsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, CyPepInstitutionsParser.DEFAULT_URL, rawDir);
        assertEquals(List.of(CyPepInstitutionsParser.DEFAULT_URL), calls);
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("board.html"), resource("board.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(CyPepInstitutionsParser.ID_PREFIX, CyPepInstitutionsParser.TOPIC);

        String message = new CyPepInstitutionsParser().convert(rawDir, CyPepInstitutionsParser.DEFAULT_URL, dataset);

        assertTrue(message.startsWith("8 membres"), message);
        assertEquals(8, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        FtmEntity governor = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Christodoulos Patsalides")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), governor.getValues("topics"));
        assertEquals(3, dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new CyPepInstitutionsParser().convert(rawDir, "x", new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> CyPepInstitutionsParser.members("<html/>"));
    }

    private static String resource(String name) {
        try (InputStream in = CyPepInstitutionsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
