package com.swiftcheck.aml.plugins.hrpep.institutions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/**
 * Extraits réels de predsjednik.hr, hnb.hr et usud.hr (7 novembre 2026) : le président, le gouverneur, la gouverneure adjointe,
 * les quatre vice-gouverneurs, les juges et le président de la Cour constitutionnelle.
 */
class HrPepInstitutionsParserTest {

    private static final String URL = "https://example.test/page";

    @Test
    void readsThePresident() {
        var h = HrPepInstitutionsParser.president(resource("president.html"), URL).get(0);

        assertEquals("Zoran Milanović", h.name(), "nom en capitales du pied de page");
        assertEquals("1966-10-30", h.birth());
        assertEquals("Zagrebu", h.birthPlace());
        assertEquals("2020", h.start());
        assertEquals("gov.head", h.topic());
    }

    @Test
    void readsTheGovernorIgnoringTheFormerGovernorsCommentedBio() {
        var h = HrPepInstitutionsParser.hnb(resource("hnb-governor.html"), URL, "Gouverneur").get(0);

        assertEquals("Ante Žigman", h.name(), "titre « Dr. sc. » retiré, ancienne fiche commentée ignorée");
        assertEquals("1972-05-08", h.birth());
        assertEquals("2026-06-19", h.start());
    }

    @Test
    void readsTheDeputyGovernorAndTheViceGovernors() {
        var deputy = HrPepInstitutionsParser.hnb(resource("hnb-deputy.html"), URL, "Adjointe").get(0);
        assertEquals("Sandra Švaljek", deputy.name());
        assertEquals("1970-02-23", deputy.birth());
        assertEquals("Krapini", deputy.birthPlace());
        assertEquals("2018-07-18", deputy.start(), "première nomination, avant le second mandat");

        var vice = HrPepInstitutionsParser.hnb(resource("hnb-vice.html"), URL, "Vice");
        assertEquals(4, vice.size());
        var faulend = vice.stream().filter(v -> v.name().equals("Michael Faulend")).findFirst().orElseThrow();
        assertEquals("1974-05-25", faulend.birth());
        assertEquals("2013-07-05", faulend.start());
        var fras = vice.stream().filter(v -> v.name().equals("Bojan Fras")).findFirst().orElseThrow();
        assertEquals("1963-01-04", fras.birth(), "formulation « rođen je u Zagrebu 4. siječnja 1963. »");
        assertEquals("Zagrebu", fras.birthPlace());
        var jakir = vice.stream().filter(v -> v.name().equals("Ivana Jakir-Bajo")).findFirst().orElseThrow();
        assertEquals("1974-03-15", jakir.birth());
        assertEquals("2020-07-01", jakir.start());
        assertTrue(vice.stream().anyMatch(v -> v.name().equals("Maroje Lang") && "1972-11-27".equals(v.birth())));
    }

    @Test
    void readsTheConstitutionalCourtWithBirthYears() {
        var judges = HrPepInstitutionsParser.court(resource("usud-judges.html"), URL, "Juge");

        assertTrue(judges.size() >= 9, judges.size() + " juges");
        var marochini = judges.stream().filter(j -> j.name().equals("Maša Marochini Zrinski")).findFirst().orElseThrow();
        assertEquals("1983", marochini.birth(), "année de naissance seule");
        assertTrue(judges.stream().anyMatch(j -> j.name().equals("Sanja Bezbradica Jelavić")), "nom de famille composé");

        var president = HrPepInstitutionsParser.court(resource("usud-president.html"), URL, "Président").get(0);
        assertEquals("Frane Staničić", president.name());
        assertEquals("1981", president.birth());
    }

    @Test
    void convertsCroatianMonths() {
        assertEquals("1972-05-08", HrPepInstitutionsParser.iso("8", "svibnja", "1972"));
        assertEquals("1972-11-27", HrPepInstitutionsParser.iso("27", "studenoga", "1972"));
        assertEquals("1950-11-03", HrPepInstitutionsParser.iso("3", "studenog", "1950"));
        assertNull(HrPepInstitutionsParser.iso("3", "november", "1950"));
    }

    @Test
    void downloadsEveryPage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        HrPepInstitutionsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, HrPepInstitutionsParser.SOURCES, rawDir);

        assertEquals(6, calls.size());
        assertTrue(Files.exists(rawDir.resolve("hnb-vice.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        for (var s : HrPepInstitutionsParser.SOURCES) {
            Files.writeString(rawDir.resolve(s.file()), resource(s.file()), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(HrPepInstitutionsParser.ID_PREFIX, HrPepInstitutionsParser.TOPIC);

        String message = new HrPepInstitutionsParser().convert(rawDir, HrPepInstitutionsParser.SOURCES, dataset);

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertTrue(persons >= 17, message);
        assertEquals(persons, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenAPageChanged() {
        assertThrows(IllegalStateException.class, () -> HrPepInstitutionsParser.president("<html/>", URL));
        assertThrows(IllegalStateException.class, () -> HrPepInstitutionsParser.hnb("<html/>", URL, "x"));
        assertThrows(IllegalStateException.class, () -> HrPepInstitutionsParser.court("<html/>", URL, "x"));
    }

    @Test
    void rejectsAnUnknownSource() {
        assertThrows(IllegalArgumentException.class, () -> HrPepInstitutionsParser.selected(Map.of("keys", "xyz")));
    }

    private static String resource(String name) {
        try (InputStream in = HrPepInstitutionsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
