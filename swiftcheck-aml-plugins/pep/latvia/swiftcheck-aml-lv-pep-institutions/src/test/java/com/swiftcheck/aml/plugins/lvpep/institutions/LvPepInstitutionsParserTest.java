package com.swiftcheck.aml.plugins.lvpep.institutions;

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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/**
 * Extraits réels de president.lv, at.gov.lv, satversmestiesa.lv et bank.lv (6 novembre 2026) : le paragraphe de présentation du
 * président, la phrase du président de la Cour suprême, la liste des sénateurs par département, la liste des juges de la Cour
 * constitutionnelle et le conseil de la Banque de Lettonie.
 */
class LvPepInstitutionsParserTest {

    private static final String URL = "https://example.test/page";

    private static List<String> lines(String file) {
        return LvPepInstitutionsParser.lines(resource(file));
    }

    @Test
    void readsThePresident() {
        var h = LvPepInstitutionsParser.president(lines("president.html"), URL).get(0);

        assertEquals("Edgars Rinkēvičs", h.name());
        assertEquals("2023-07-08", h.start());
        assertEquals("gov.head", h.topic());
    }

    @Test
    void readsTheSupremeCourtPresidentWithDecodedEntities() {
        var h = LvPepInstitutionsParser.supremeCourtPresident(lines("supreme-court-president.html"), URL).get(0);

        assertEquals("Aigars Strupišs", h.name(), "&scaron; décodé");
        assertEquals("2020-06-16", h.start());
    }

    @Test
    void readsTheSenatorsByDepartment() {
        var senators = LvPepInstitutionsParser.senators(lines("senators.html"), URL);

        assertTrue(senators.size() >= 30, senators.size() + " sénateurs");
        var saleniek = senators.stream().filter(h -> h.name().equals("Normunds Salenieks")).findFirst().orElseThrow();
        assertEquals("Président du département civil de la Cour suprême (sénateur)", saleniek.role());
        var balodis = senators.stream().filter(h -> h.name().equals("Kaspars Balodis")).findFirst().orElseThrow();
        assertEquals("Sénateur de la Cour suprême (département civil)", balodis.role(), "titre « Dr. iur. » retiré");
        assertTrue(senators.stream().anyMatch(h -> h.name().equals("Erlens Kalniņš")), "&Scaron; décodé et casse corrigée");
        assertEquals(3, senators.stream().filter(h -> h.role().startsWith("Président du département")).count());
        assertTrue(senators.stream().anyMatch(h -> h.note().equals("Département : pénal")));
        assertTrue(senators.stream().anyMatch(h -> h.note().equals("Département : administratif")));
    }

    @Test
    void readsTheConstitutionalCourt() {
        var judges = LvPepInstitutionsParser.constitutionalCourt(lines("constitutional-court.html"), URL);

        assertEquals(7, judges.size());
        assertEquals("Irēna Kucina", judges.get(0).name());
        assertEquals("Président de la Cour constitutionnelle", judges.get(0).role());
        assertEquals("Vice-président de la Cour constitutionnelle", judges.get(1).role());
        assertTrue(judges.stream().anyMatch(h -> h.name().equals("Mārtiņš Mits") && h.role().startsWith("Juge")));
    }

    @Test
    void readsTheBankCouncil() {
        var council = LvPepInstitutionsParser.bank(lines("bank-of-latvia.html"), URL);

        assertEquals(6, council.size());
        assertEquals("Mārtiņš Kazāks", council.get(0).name());
        assertTrue(council.get(0).role().startsWith("Gouverneur"));
        assertEquals(2, council.stream().filter(h -> h.role().startsWith("Sous-gouverneur")).count());
        assertEquals(3, council.stream().filter(h -> h.role().startsWith("Membre du conseil")).count());
    }

    @Test
    void titleCaseKeepsLatvianLettersAndCompoundNames() {
        assertEquals("Kalniņš", LvPepInstitutionsParser.titleCase("KALNIŅŠ"));
        assertEquals("Ozola-Bērziņa", LvPepInstitutionsParser.titleCase("OZOLA-BĒRZIŅA"));
    }

    @Test
    void downloadsEveryPage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        LvPepInstitutionsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, LvPepInstitutionsParser.SOURCES, rawDir);

        assertEquals(5, calls.size());
        assertTrue(Files.exists(rawDir.resolve("senators.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        for (var s : LvPepInstitutionsParser.SOURCES) {
            Files.writeString(rawDir.resolve(s.file()), resource(s.file()), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LvPepInstitutionsParser.ID_PREFIX, LvPepInstitutionsParser.TOPIC);

        String message = new LvPepInstitutionsParser().convert(rawDir, LvPepInstitutionsParser.SOURCES, dataset);

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertTrue(persons >= 45, message);
        assertEquals(persons, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenAPageChanged() {
        assertThrows(IllegalStateException.class, () -> LvPepInstitutionsParser.president(List.of("x"), URL));
        assertThrows(IllegalStateException.class, () -> LvPepInstitutionsParser.supremeCourtPresident(List.of("x"), URL));
        assertThrows(IllegalStateException.class, () -> LvPepInstitutionsParser.senators(List.of("x"), URL));
        assertThrows(IllegalStateException.class, () -> LvPepInstitutionsParser.constitutionalCourt(List.of("x"), URL));
        assertThrows(IllegalStateException.class, () -> LvPepInstitutionsParser.bank(List.of("x"), URL));
    }

    @Test
    void rejectsAnUnknownSource() {
        assertThrows(IllegalArgumentException.class, () -> LvPepInstitutionsParser.selected(Map.of("keys", "xyz")));
    }

    private static String resource(String name) {
        try (InputStream in = LvPepInstitutionsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
