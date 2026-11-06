package com.swiftcheck.aml.plugins.czpep.psp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Extrait réel des données ouvertes de la Chambre des députés (6 novembre 2026) : 143 personnes (députés des dixième et neuvième
 * législatures, membres du gouvernement actuel et du précédent, présidents de la République, dirigeants de la NKÚ, médiateurs).
 */
class CzPepPspParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Test
    void readsPersonsWithDeathAndUnknownBirth(@TempDir Path dir) throws Exception {
        Path zip = copy(dir);
        try (ZipFile z = new ZipFile(zip.toFile())) {
            var persons = CzPepPspParser.persons(CzPepPspParser.rows(z, "osoby.unl"));

            assertEquals(143, persons.size());
            var havel = persons.values().stream().filter(p -> p.lastName().equals("Havel") && p.firstName().equals("Václav")).findFirst().orElseThrow();
            assertTrue(havel.dead());
            assertEquals("1936-10-05", havel.birthDate());
            var drabek = persons.values().stream().filter(p -> p.lastName().equals("Drábek") && p.firstName().equals("Jiří")).findFirst().orElseThrow();
            assertNull(drabek.birthDate(), "01.01.1900 signifie naissance inconnue");
            assertEquals("Marek Benda", persons.values().stream().filter(p -> p.lastName().equals("Benda")).findFirst().orElseThrow().name());
        }
    }

    @Test
    void parliamentKeepsCurrentMembersAndTheHistoryAddsFormerOnes(@TempDir Path dir) throws Exception {
        Set<String> current = names(run(dir, 12, "parliament"));
        Set<String> history = names(run(dir, 600, "parliament"));

        assertTrue(current.contains("Marek Benda"), current.toString());
        assertTrue(history.containsAll(current));
        assertTrue(history.size() > current.size(), "les députés de la neuvième législature s'ajoutent");
        assertTrue(!history.contains("Jaroslav Bašta") || !current.contains("Jaroslav Bašta"), "décédé jamais publié");
        FtmEntity benda = person(run(dir, 12, "parliament"), "Marek Benda");
        assertEquals(Set.of("role.pep"), benda.getValues("topics"));
        assertEquals(Set.of("1968-11-10"), benda.getValues("birthDate"));
        assertEquals(Set.of("male"), benda.getValues("gender"));
        assertTrue(benda.getValues("position").contains("Député à la Chambre des députés (poslanec)"));
    }

    @Test
    void deadPersonsAreNeverPublished(@TempDir Path dir) throws Exception {
        Set<String> history = names(run(dir, 600, "parliament"));
        history.addAll(names(run(dir, 600, "president")));
        history.addAll(names(run(dir, 600, "oversight")));

        assertTrue(!history.contains("Jaroslav Bašta"));
        assertTrue(!history.contains("Václav Havel"), "décédé");
        assertTrue(!history.contains("Otakar Motejl"), "décédé");
    }

    @Test
    void presidentLayer(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder current = run(dir, 12, "president");
        assertEquals(Set.of("Petr Pavel"), names(current));
        assertTrue(person(current, "Petr Pavel").getValues("position").contains("Président de la République tchèque (prezident republiky)"));

        FtmDatasetBuilder history = run(dir, 600, "president");
        assertEquals(Set.of("Petr Pavel", "Miloš Zeman", "Václav Klaus"), names(history));
        assertTrue(history.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("endDate").contains("2023-03-08") && e.getValues("status").contains("ended")));
    }

    @Test
    void governmentLayerReadsMinistersWithDates(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder current = run(dir, 12, "government");

        assertTrue(names(current).containsAll(Set.of("Andrej Babiš", "Petr Fiala")), names(current).toString());
        assertTrue(person(current, "Andrej Babiš").getValues("position").contains("Premier ministre (předseda vlády)"));
        assertTrue(person(current, "Petr Fiala").getValues("position").contains("Premier ministre (předseda vlády)"));
        assertTrue(current.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("endDate").contains("2025-12-15") && e.getValues("status").contains("ended")));
        assertTrue(person(current, "Andrej Babiš").getValues("position").stream().noneMatch(p -> p.startsWith("Ministre : Předseda")));
    }

    @Test
    void oversightLayerReadsAuditorsAndOmbudsmen(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = run(dir, 600, "oversight");

        assertTrue(dataset.entities().stream().anyMatch(e -> "Person".equals(e.getSchema())
                && e.getValues("position").stream().anyMatch(p -> p.contains("Cour des comptes"))));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Person".equals(e.getSchema())
                && e.getValues("position").stream().anyMatch(p -> p.startsWith("Médiateur"))));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void downloadsTheArchive(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        CzPepPspParser.download(url -> {
            calls.add(url);
            return new byte[] {1, 2, 3};
        }, CzPepPspParser.URL, rawDir);

        assertEquals(List.of(CzPepPspParser.URL), calls);
        assertEquals(3, Files.size(rawDir.resolve("poslanci.zip")));
    }

    @Test
    void rejectsAnUnknownScope() {
        assertThrows(IllegalArgumentException.class, () -> CzPepPspParser.scope(Map.of("scope", "senate")));
    }

    @Test
    void failsClearlyWithoutDownloadedArchive(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new CzPepPspParser().convert(rawDir, TODAY, 12, "parliament",
                new FtmDatasetBuilder("x", "role.pep")));
    }

    private static Path copy(Path dir) throws IOException {
        Path zip = dir.resolve("poslanci.zip");
        try (InputStream in = CzPepPspParserTest.class.getResourceAsStream("/poslanci-sample.zip")) {
            Files.write(zip, in.readAllBytes());
        }
        return zip;
    }

    private static FtmDatasetBuilder run(Path dir, int months, String scope) throws Exception {
        copy(dir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(CzPepPspParser.prefix(scope), CzPepPspParser.TOPIC);
        new CzPepPspParser().convert(dir, TODAY, months, scope, dataset);
        return dataset;
    }

    private static Set<String> names(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow();
    }
}
