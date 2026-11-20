package com.swiftcheck.aml.plugins.depep.lobbyregister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;

/** Story 5, étape 1 : rapprochement des personnes du Lobbyregister avec la base PEP SwiftCheck. */
class PepCorrelationTest {

    private static final String INDEX = String.join("\n",
            // Nom rare, unique dans la base : confirmé sans autre contexte.
            "{\"dataset\":\"de_pep_state_enterprises\",\"id\":\"de-se-busch\",\"names\":[\"Roland Busch\"],\"firstName\":\"Roland\",\"lastName\":\"Busch\",\"position\":[\"Mitglied des Aufsichtsrats – Deutsche Bahn AG\"],\"country\":[\"de\"],\"sourceUrl\":\"https://example.org/db\",\"status\":\"ACTIVE_PEP\"}",
            // Nom courant (Müller) sans contexte commun : candidat, jamais publié comme PEP.
            "{\"dataset\":\"de_pep_bundestag\",\"id\":\"de-bt-mueller\",\"names\":[\"Daniel Müller\"],\"firstName\":\"Daniel\",\"lastName\":\"Müller\",\"position\":[\"Membre du Bundestag (Mitglied des Deutschen Bundestages)\"],\"country\":[\"de\"],\"status\":\"EXPIRED\",\"endDate\":\"2009-10-27\"}",
            // Nom approché (faute de frappe) avec contexte commun « energiepolitik » absent : non confirmé.
            "{\"dataset\":\"de_pep_landtage\",\"id\":\"de-lt-klein\",\"names\":[\"Eva Kleine\"],\"firstName\":\"Eva\",\"lastName\":\"Kleine\",\"position\":[\"Mitglied des Landtags Brandenburg\"],\"country\":[\"de\"],\"status\":\"ACTIVE_PEP\"}");

    private static List<String> run(Path tempDir, boolean withIndex) throws Exception {
        Path index = tempDir.resolve("swiftcheck-pep-index.ndjson");
        Files.writeString(index, INDEX, StandardCharsets.UTF_8);
        Path out = tempDir.resolve("out.ndjson");
        ParserContext ctx = new ParserContext("de_pep_lobbyregister",
                Path.of("src/test/resources/test-lobbyregister-sample.json"), tempDir, out,
                withIndex ? Map.of("pepIndexFile", index.toString()) : Map.of());
        ParserResult result = new DePepLobbyregisterParser().parse(ctx);
        System.out.println(result.message());
        List<String> lines = Files.readAllLines(out);
        lines.add(0, result.message());
        return lines;
    }

    private static String person(List<String> lines, String name) {
        return lines.stream().filter(l -> l.contains("\"schema\":\"Person\"") && l.contains("\"name\":[\"" + name + "\"]"))
                .findFirst().orElse(null);
    }

    @Test
    void knownPepIsLinkedToItsEntryAndPropagates(@TempDir Path tempDir) throws Exception {
        List<String> lines = run(tempDir, true);
        // AC1 : Roland Busch, PEP d'un autre jeu, devient PEP ici, relié à son entrée R001875.
        String busch = person(lines, "Roland Busch");
        assertNotNull(busch);
        assertTrue(busch.contains("role.pep") && !busch.contains("role.rca"), busch);
        assertTrue(busch.contains("pep_match: de_pep_state_enterprises / de-se-busch (exakter Name)"), busch);
        assertTrue(busch.contains("[R001875]"), busch);
        // Étape 2 depuis cette PEP : Oktay Ay, co-représentant légal, reçoit le lien RCA.1 vers Busch.
        String ay = person(lines, "Oktay Ay");
        assertNotNull(ay);
        assertTrue(ay.contains("role.rca") && ay.contains("Regel 1") && ay.contains("PEP : Dr. Roland Busch"), ay);
        // Nom courant sans contexte : Daniel Müller reste RCA (règle 2 depuis Schulz), jamais PEP.
        String mueller = person(lines, "Daniel Müller");
        assertNotNull(mueller);
        assertFalse(mueller.contains("role.pep"), mueller);
        assertTrue(lines.get(0).contains("homonymes non confirmes"), lines.get(0));
        assertTrue(lines.get(0).contains("Daniel Müller [R001875] ~ Daniel Müller (de_pep_bundestag)"), lines.get(0));
    }

    @Test
    void withoutIndexNothingChanges(@TempDir Path tempDir) throws Exception {
        List<String> lines = run(tempDir, false);
        String busch = person(lines, "Roland Busch");
        assertTrue(busch.contains("role.rca") && !busch.contains("role.pep"), busch);
        assertTrue(lines.get(0).contains("non croisee"));
    }

    @Test
    void contextConfirmsACommonName() {
        PepIndex index = PepIndex.empty();
        assertEquals(0, index.size());
        assertTrue(PepIndex.jaroWinkler("eva klein", "eva kleine") >= PepIndex.JARO_WINKLER_MIN);
        assertTrue(PepIndex.jaroWinkler("eva klein", "ewald klee") < PepIndex.JARO_WINKLER_MIN);
        assertEquals("roland busch", PepIndex.normalize("Dr. Roland Busch"));
        assertEquals("juergen mueller", PepIndex.normalize("Prof. Dr. Jürgen Müller"));
    }

    @Test
    void commonNameNeedsContext(@TempDir Path tempDir) throws Exception {
        Path f = tempDir.resolve("i.ndjson");
        Files.writeString(f, INDEX, StandardCharsets.UTF_8);
        PepIndex index = PepIndex.load(f);
        PepIndex.Match none = index.match("Daniel", "Müller", List.of("Siemens AG", "Referent"));
        assertNotNull(none);
        assertFalse(none.confirmed());
        PepIndex.Match ctx = index.match("Daniel", "Müller", List.of("Bundestag", "Mitglied des Deutschen Bundestages"));
        assertTrue(ctx.confirmed(), ctx.evidence());
        PepIndex.Match fuzzy = index.match("Eva", "Klein", List.of("Ehemalige Lobbyagentur Berlin"));
        assertNotNull(fuzzy);
        assertFalse(fuzzy.exact());
        assertFalse(fuzzy.confirmed());
        assertNull(index.match("Lena", "Vogt", List.of()));
    }
}
