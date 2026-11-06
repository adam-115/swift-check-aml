package com.swiftcheck.aml.plugins.grpep.audit;

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
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Blocs réels de la page www.elsyn.gr/en/judges (6 novembre 2026) : le président de la Cour des comptes et le procureur général
 * (téléphones et courriels retirés du fichier de test).
 */
class GrPepAuditParserTest {

    @Test
    void readsThePresidentAndTheProsecutorGeneral() {
        var holders = GrPepAuditParser.holders(resource("judges.html"));

        assertEquals(2, holders.size());
        assertEquals("Douni Sotiria", holders.get(0).name());
        assertEquals("Sotiria Douni", holders.get(0).alias(), "ordre inverse en alias");
        assertTrue(holders.get(0).role().startsWith("Président de la Cour des comptes"));
        assertEquals("Stamatios Poulis", holders.get(1).name());
        assertEquals("Procureur général près la Cour des comptes hellénique", holders.get(1).role());
        assertEquals("General Prosecutor", holders.get(1).title());
    }

    @Test
    void downloadsThePage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        GrPepAuditParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, GrPepAuditParser.DEFAULT_URL, rawDir);

        assertEquals(List.of("https://www.elsyn.gr/en/judges"), calls);
        assertTrue(Files.exists(rawDir.resolve("judges.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("judges.html"), resource("judges.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepAuditParser.ID_PREFIX, GrPepAuditParser.TOPIC);

        String message = new GrPepAuditParser().convert(rawDir, GrPepAuditParser.DEFAULT_URL, dataset);

        assertTrue(message.startsWith("2 personnes"), message);
        Set<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
        assertEquals(Set.of("Douni Sotiria", "Stamatios Poulis"), names);
        FtmEntity douni = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Douni Sotiria")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), douni.getValues("topics"));
        assertEquals(Set.of("Sotiria Douni"), douni.getValues("alias"));
        assertEquals(2, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) throws Exception {
        assertThrows(IllegalStateException.class, () -> new GrPepAuditParser().convert(rawDir, "x", new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> GrPepAuditParser.holders("<html/>"));
    }

    private static String resource(String name) {
        try (InputStream in = GrPepAuditParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
