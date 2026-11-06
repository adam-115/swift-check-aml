package com.swiftcheck.aml.plugins.eepep.institutions;

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
 * Pages réelles de president.ee, riigikohus.ee, riigikontroll.ee et eestipank.ee (6 novembre 2026). Seul le contenu principal de
 * chaque page est conservé ; images, scripts et styles retirés.
 */
class EePepInstitutionsParserTest {

    private static final String URL = "https://example.test/page";

    @Test
    void readsThePresident() {
        var h = EePepInstitutionsParser.president(resource("president.html"), URL).get(0);

        assertEquals("Ülle Madise", h.name());
        assertTrue(h.role().startsWith("Président de la République"));
        assertEquals("2026-10-12", h.start());
        assertEquals("1974-12-11", h.birth());
        assertEquals("Tartu", h.birthPlace());
        assertEquals("gov.head", h.topic());
    }

    @Test
    void readsTheSupremeCourtByChamber() {
        var judges = EePepInstitutionsParser.supremeCourt(resource("supreme-court.html"), URL);

        assertEquals(18, judges.size());
        var president = judges.stream().filter(j -> j.name().equals("Villu Kõve")).findFirst().orElseThrow();
        assertTrue(president.role().startsWith("Président de la Cour suprême"));
        assertEquals("1971-08-26", president.birth());
        assertEquals("Saaremaa", president.birthPlace());

        var paal = judges.stream().filter(j -> j.name().equals("Kaupo Paal")).findFirst().orElseThrow();
        assertEquals("Juge et président de la chambre « Civil Chamber » de la Cour suprême", paal.role());
        var laffranque = judges.stream().filter(j -> j.name().equals("Julia Laffranque")).findFirst().orElseThrow();
        assertEquals("Juge de la Cour suprême (Riigikohtu liige)", laffranque.role());
        assertEquals("Chambre : Administrative Law Chamber", laffranque.note());
        assertNull(laffranque.birth(), "la page ne donne la naissance que du président");
        assertEquals(3, judges.stream().filter(j -> j.role().startsWith("Juge et président de la chambre")).count());
    }

    @Test
    void readsTheAuditorGeneral() {
        var h = EePepInstitutionsParser.auditorGeneral(resource("auditor-general.html"), URL).get(0);

        assertEquals("Janar Holm", h.name());
        assertEquals("2018-04-09", h.start());
        assertEquals("1976-08-17", h.birth());
        assertEquals("Kuressaare", h.birthPlace());
    }

    @Test
    void readsTheCentralBankExecutiveBoard() {
        var board = EePepInstitutionsParser.eestiPankExecutive(resource("eesti-pank-executive.html"), URL);

        assertEquals(3, board.size());
        assertEquals("Ülo Kaasik", board.get(0).name());
        assertTrue(board.get(0).role().startsWith("Gouverneur"));
        assertEquals("2026-06", board.get(0).start());
        assertEquals("Andrus Alber", board.get(1).name());
        assertTrue(board.get(1).role().startsWith("Sous-gouverneur"));
        assertEquals("2026-01", board.get(1).start());
        assertEquals("2026-06", board.get(2).start());
    }

    @Test
    void readsTheCentralBankSupervisoryBoard() {
        var board = EePepInstitutionsParser.eestiPankSupervisory(resource("eesti-pank-supervisory.html"), URL);

        assertEquals(11, board.size());
        var chair = board.stream().filter(h -> h.name().equals("Urmas Varblane")).findFirst().orElseThrow();
        assertEquals("2023-06-13", chair.start());
        assertTrue(chair.role().startsWith("Président du conseil"));
        var mp = board.stream().filter(h -> h.name().equals("Aivar Kokk")).findFirst().orElseThrow();
        assertTrue(mp.note().contains("membre du riigikogu"), mp.note());
        var expert = board.stream().filter(h -> h.name().equals("Toomas Tamsar")).findFirst().orElseThrow();
        assertTrue(expert.note().contains("expert"), expert.note());
    }

    @Test
    void downloadsEveryPage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        EePepInstitutionsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, EePepInstitutionsParser.SOURCES, rawDir);

        assertEquals(5, calls.size());
        assertTrue(Files.exists(rawDir.resolve("supreme-court.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        for (var s : EePepInstitutionsParser.SOURCES) {
            Files.writeString(rawDir.resolve(s.file()), resource(s.file()), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EePepInstitutionsParser.ID_PREFIX, EePepInstitutionsParser.TOPIC);

        String message = new EePepInstitutionsParser().convert(rawDir, EePepInstitutionsParser.SOURCES, dataset);

        assertTrue(message.startsWith("34 personnes"), message);
        assertEquals(34, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        assertEquals(34, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenAPageChanged() {
        assertThrows(IllegalStateException.class, () -> EePepInstitutionsParser.president("<html/>", URL));
        assertThrows(IllegalStateException.class, () -> EePepInstitutionsParser.supremeCourt("<html/>", URL));
        assertThrows(IllegalStateException.class, () -> EePepInstitutionsParser.auditorGeneral("<html/>", URL));
        assertThrows(IllegalStateException.class, () -> EePepInstitutionsParser.eestiPankExecutive("<html/>", URL));
        assertThrows(IllegalStateException.class, () -> EePepInstitutionsParser.eestiPankSupervisory("<html/>", URL));
    }

    @Test
    void rejectsAnUnknownSource() {
        assertThrows(IllegalArgumentException.class, () -> EePepInstitutionsParser.selected(Map.of("keys", "xyz")));
    }

    private static String resource(String name) {
        try (InputStream in = EePepInstitutionsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
