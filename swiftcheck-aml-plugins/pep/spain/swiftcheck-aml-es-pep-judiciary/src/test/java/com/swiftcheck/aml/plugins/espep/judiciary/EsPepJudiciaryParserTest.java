package com.swiftcheck.aml.plugins.espep.judiciary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Réponse réelle de Wikidata du 2026-11-04 (voir {@code src/test/resources/holders.json}). */
class EsPepJudiciaryParserTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-04");
    private static final Map<String, String> P = EsPepJudiciaryParser.settings(null);

    private static Path rawDir(Path dir) throws IOException {
        try (InputStream in = EsPepJudiciaryParserTest.class.getResourceAsStream("/holders.json")) {
            Files.copy(in, dir.resolve(EsPepJudiciaryParser.HOLDERS_FILE));
        }
        return dir;
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(EsPepJudiciaryParser.ID_PREFIX, EsPepJudiciaryParser.TOPIC);
    }

    @Test
    void convertsTheRealResponse(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = dataset();
        EsPepJudiciaryParser.Report report = new EsPepJudiciaryParser().convert(rawDir(dir), TODAY, P, dataset);

        assertTrue(report.persons() > 0);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        List<FtmEntity> persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).toList();
        assertEquals(report.persons(), persons.size());
        for (FtmEntity person : persons) {
            assertEquals(Set.of("role.pep"), person.getValues("topics"));
            assertEquals(Set.of("es"), person.getValues("nationality"));
            assertEquals(1, person.getValues("wikidataId").size());
            assertFalse(person.getValues("position").isEmpty());
        }
    }

    @Test
    void keepsAKnownHolder(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = dataset();
        new EsPepJudiciaryParser().convert(rawDir(dir), TODAY, P, dataset);

        FtmEntity person = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("wikidataId").contains("Q73265910")).findFirst().orElseThrow();
        assertEquals(Set.of("Francisco Marín Castán"), person.getValues("name"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(person.getId())));
    }

    @Test
    void appliesTheRetentionOfTheCountry(@TempDir Path dir) throws Exception {
        // beaucoup plus tard, tout mandat terminé dans la réponse a dépassé 24 mois : il est écarté
        FtmDatasetBuilder later = dataset();
        EsPepJudiciaryParser.Report atSnapshot = new EsPepJudiciaryParser().convert(rawDir(dir), TODAY, P, dataset());
        try {
            EsPepJudiciaryParser.Report report = new EsPepJudiciaryParser().convert(dir, TODAY.plusYears(30), P, later);
            assertEquals(0, report.ended(), "tout mandat terminé a dépassé 24 mois");
            assertTrue(report.dropped() >= atSnapshot.ended() + atSnapshot.dropped());
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("fenêtre"), "aucun mandat ne doit rester");
        }
    }

    @Test
    void failsWhenTheResponseIsEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(EsPepJudiciaryParser.HOLDERS_FILE),
                "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}");
        assertThrows(IllegalStateException.class, () -> new EsPepJudiciaryParser().convert(dir, TODAY, P, dataset()));
    }

    @Test
    void queryIsProtectedAgainstInjection() {
        Map<String, String> bad = new java.util.LinkedHashMap<>(P);
        bad.put("kind", "positions");
        bad.put("positions", "Q1 } UNION { ?x ?y ?z");
        assertThrows(IllegalArgumentException.class, () -> EsPepJudiciaryParser.query(bad, TODAY));
        Map<String, String> unknown = new java.util.LinkedHashMap<>(P);
        unknown.put("kind", "autre");
        assertThrows(IllegalArgumentException.class, () -> EsPepJudiciaryParser.query(unknown, TODAY));
    }

    @Test
    void queryHasTheRetentionDate() {
        String sparql = EsPepJudiciaryParser.query(P, LocalDate.parse("2026-11-04"));
        assertTrue(sparql.contains("2024-11-04T00:00:00Z"), "24 mois avant le 4 novembre 2026");
        assertTrue(sparql.contains("FILTER NOT EXISTS { ?pep wdt:P570 ?death }"));
    }

    @Test
    void birthDateKeepsThePrecisionOfWikidata() {
        assertEquals("1970-05-12", EsPepJudiciaryParser.birth("1970-05-12T00:00:00Z", "11"));
        assertEquals("1970-05", EsPepJudiciaryParser.birth("1970-05-01T00:00:00Z", "10"));
        assertEquals("1970", EsPepJudiciaryParser.birth("1970-01-01T00:00:00Z", "9"));
        assertEquals(null, EsPepJudiciaryParser.birth("1900-01-01T00:00:00Z", "8"));
    }

    /** Rafraîchit {@code holders.json} : {@code mvn test -Dtest=*Test -Dlive.snapshot=<dossier>}. */
    @Test
    void snapshotLive() throws Exception {
        String target = System.getProperty("live.snapshot");
        Assumptions.assumeTrue(target != null, "facultatif : -Dlive.snapshot=<dossier>");
        Path out = Path.of(target);
        EsPepJudiciaryParser.download(P, out);
        assertTrue(Files.isRegularFile(out.resolve(EsPepJudiciaryParser.HOLDERS_FILE)));
    }
}
