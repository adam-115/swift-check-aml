package com.swiftcheck.aml.plugins.mtpep.institutions;

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

/** Pages réelles du 9 novembre 2026, scripts retirés : la biographie de la présidente (president.gov.mt) et les hauts responsables du NAO. */
class MtPepInstitutionsParserTest {

    @Test
    void readsThePresident() {
        var president = MtPepInstitutionsParser.president(resource("president.html"));

        assertEquals("Myriam Spiteri Debono", president.name());
        assertEquals("President of Malta", president.role());
    }

    @Test
    void readsTheAuditorGeneralAndHisDeputyButNotTheAssistants() {
        var auditors = MtPepInstitutionsParser.auditors(resource("senior.html"));

        assertEquals(2, auditors.size());
        assertEquals("Charles Deguara", auditors.get(0).name());
        assertEquals("Auditor General of Malta", auditors.get(0).role());
        assertEquals("Noel Camilleri", auditors.get(1).name());
        assertEquals("Deputy Auditor General of Malta", auditors.get(1).role());
    }

    @Test
    void addsTheSurnameFirstOrder() {
        assertEquals(Set.of("Debono Myriam Spiteri"), MtPepInstitutionsParser.aliases("Myriam Spiteri Debono"));
        assertEquals(Set.of("Deguara Charles"), MtPepInstitutionsParser.aliases("Charles Deguara"));
    }

    @Test
    void downloadsBothPages(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        MtPepInstitutionsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, MtPepInstitutionsParser.DEFAULT_PRESIDENT_URL, MtPepInstitutionsParser.DEFAULT_AUDIT_URL, rawDir);

        assertEquals(List.of(MtPepInstitutionsParser.DEFAULT_PRESIDENT_URL, MtPepInstitutionsParser.DEFAULT_AUDIT_URL), calls);
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("president.html"), resource("president.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("senior.html"), resource("senior.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(MtPepInstitutionsParser.ID_PREFIX, MtPepInstitutionsParser.TOPIC);

        String message = new MtPepInstitutionsParser().convert(rawDir, MtPepInstitutionsParser.DEFAULT_PRESIDENT_URL,
                MtPepInstitutionsParser.DEFAULT_AUDIT_URL, dataset);

        assertTrue(message.startsWith("3 personnes"), message);
        assertEquals(3, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        FtmEntity president = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Myriam Spiteri Debono")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), president.getValues("topics"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.head")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePagesChangedOrAreMissing(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new MtPepInstitutionsParser().convert(rawDir, "x", "y", new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> MtPepInstitutionsParser.president("<html/>"));
        assertThrows(IllegalStateException.class, () -> MtPepInstitutionsParser.auditors("<html/>"));
    }

    private static String resource(String name) {
        try (InputStream in = MtPepInstitutionsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
