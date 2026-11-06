package com.swiftcheck.aml.plugins.fipep.institutions;

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

/** Pages réelles de presidentti.fi et solidium.fi (6 novembre 2026). */
class FiPepInstitutionsParserTest {

    @Test
    void readsTheCurrentPresident() {
        var people = FiPepInstitutionsParser.readCurrentPresident(resource("president-cv.html"), "u");

        assertEquals(1, people.size());
        assertEquals("Alexander Stubb", people.get(0).name());
        assertEquals(FiPepInstitutionsParser.PRESIDENT_ROLE, people.get(0).role());
    }

    @Test
    void readsOnlyLivingFormerPresidents() {
        var people = FiPepInstitutionsParser.readFormerPresidents(resource("president-former.html"), "u");

        assertEquals(2, people.size(), "Niinistö et Halonen ; Ahtisaari, Koivisto et Kekkonen sont décédés");
        var niinisto = people.stream().filter(p -> p.name().equals("Sauli Niinistö")).findFirst().orElseThrow();
        assertEquals("1948", niinisto.birthYear());
        assertEquals("2012", niinisto.startYear());
        assertEquals("2024", niinisto.endYear());
        assertEquals("Sauli Väinämö Niinistö", niinisto.alias());
        assertTrue(people.stream().anyMatch(p -> p.name().equals("Tarja Halonen") && "1943".equals(p.birthYear())));
    }

    @Test
    void readsSolidiumBoardWithRolesAndBirthYears() {
        var people = FiPepInstitutionsParser.readSolidiumBoard(resource("solidium-board.html"), "u");

        assertEquals(6, people.size());
        var chair = people.stream().filter(p -> p.name().equals("Jannica Fagerholm")).findFirst().orElseThrow();
        assertEquals("Président du conseil d'administration de Solidium", chair.role());
        assertEquals("1961", chair.birthYear());
        assertTrue(people.stream().anyMatch(p -> p.name().equals("Timo Löyttyniemi") && "1961".equals(p.birthYear())
                && p.role().startsWith("Membre")), "« s. 1961 » reconnu");
        assertTrue(people.stream().anyMatch(p -> p.name().equals("Tuomas Hyyryläinen") && p.role().startsWith("Vice-président")));
    }

    @Test
    void readsSolidiumTopManagementOnly() {
        var people = FiPepInstitutionsParser.readSolidiumTeam(resource("solidium-team.html"), "u");

        assertEquals(3, people.size());
        assertTrue(people.stream().anyMatch(p -> p.name().equals("Matts Rosenberg") && p.role().startsWith("Directeur général")));
        assertTrue(people.stream().noneMatch(p -> p.name().equals("Pia Lilja")));
    }

    @Test
    void downloadsEveryPage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        FiPepInstitutionsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, FiPepInstitutionsParser.SOURCES, rawDir);

        assertEquals(4, calls.size());
        assertTrue(Files.exists(rawDir.resolve("solidium-team.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        for (String f : List.of("president-cv.html", "president-former.html", "solidium-board.html", "solidium-team.html")) {
            Files.writeString(rawDir.resolve(f), resource(f), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FiPepInstitutionsParser.ID_PREFIX, FiPepInstitutionsParser.TOPIC);

        String message = new FiPepInstitutionsParser().convert(rawDir, FiPepInstitutionsParser.SOURCES, dataset);

        assertTrue(message.startsWith("12 personnes lues sur 2 source(s)"), message);
        FtmEntity stubb = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Alexander Stubb")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), stubb.getValues("topics"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("endDate").contains("2024") && e.getValues("status").contains("ended")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenAPageChanged() {
        assertThrows(IllegalStateException.class, () -> FiPepInstitutionsParser.readSolidiumBoard("<html><main>Just a moment</main></html>", "u"));
        assertThrows(IllegalStateException.class, () -> FiPepInstitutionsParser.readCurrentPresident("<html/>", "u"));
    }

    @Test
    void rejectsAnUnknownSource() {
        assertThrows(IllegalArgumentException.class, () -> FiPepInstitutionsParser.selected(java.util.Map.of("keys", "xyz")));
    }

    private static String resource(String name) {
        try (InputStream in = FiPepInstitutionsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
