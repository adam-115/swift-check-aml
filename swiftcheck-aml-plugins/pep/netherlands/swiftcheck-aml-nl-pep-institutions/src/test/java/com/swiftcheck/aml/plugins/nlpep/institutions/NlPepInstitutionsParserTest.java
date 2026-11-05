package com.swiftcheck.aml.plugins.nlpep.institutions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Pages réelles de la Raad van State, de la Hoge Raad (kamers) et de l'Algemene Rekenkamer (5 novembre 2026). */
class NlPepInstitutionsParserTest {

    private static final NlPepInstitutionsParser.Source RVS = new NlPepInstitutionsParser.Source("raad_van_state", "Raad van State", "https://rvs");
    private static final NlPepInstitutionsParser.Source HR = new NlPepInstitutionsParser.Source("hoge_raad", "Hoge Raad", "https://hr");
    private static final NlPepInstitutionsParser.Source RK = new NlPepInstitutionsParser.Source("algemene_rekenkamer", "Algemene Rekenkamer", "https://rk");

    @Test
    void readsTheRaadVanStateWithTheFirstNameFromTheParentheses() {
        List<NlPepInstitutionsParser.Member> list = NlPepInstitutionsParser.extract(RVS, resource("raad-van-state.html"));

        assertTrue(list.size() >= 40, "leden et staatsraden : " + list.size());
        var buma = list.stream().filter(m -> m.name().equals("Sybrand van Haersma Buma")).findFirst().orElseThrow();
        assertEquals("Vice-President", buma.role());
        assertEquals("S. van Haersma Buma", buma.initialsName());
        assertTrue(list.stream().anyMatch(m -> m.name().equals("Rosa Uylenburg") && m.role().startsWith("Voorzitter Afdeling")));
        assertTrue(list.stream().anyMatch(m -> m.name().equals("Kees Bangma") && m.role().equals("Staatsraad")));
        assertTrue(list.stream().anyMatch(m -> m.name().startsWith("Paul Comenencia") && m.role().startsWith("Staatsraad van het Koninkrijk")),
                "titre « MA » retiré");
    }

    @Test
    void readsTheHogeRaadChambersWithPresidentAndVicePresidents() {
        List<NlPepInstitutionsParser.Member> list = NlPepInstitutionsParser.extract(HR, resource("hoge-raad.html"));

        assertTrue(list.size() >= 30, "raadsheren : " + list.size());
        assertTrue(list.stream().anyMatch(m -> m.name().equals("M.J. Kroeze") && m.role().equals("President")));
        assertTrue(list.stream().anyMatch(m -> m.name().equals("M.V. Polak") && m.role().equals("Vicepresident")));
        assertTrue(list.stream().anyMatch(m -> m.name().equals("K. Teuben") && m.role().equals("Raadsheer")), "« K.Teuben » espacé");
        assertTrue(list.stream().anyMatch(m -> m.name().equals("P. Cools") && m.role().contains("buitengewone dienst")));
    }

    @Test
    void readsTheThreeMembersOfTheRekenkamerCollege() {
        List<NlPepInstitutionsParser.Member> list = NlPepInstitutionsParser.extract(RK, resource("algemene-rekenkamer.html"));

        assertEquals(3, list.size());
        assertTrue(list.stream().anyMatch(m -> m.name().equals("Pieter Duisenberg") && m.role().equals("President")));
        assertTrue(list.stream().anyMatch(m -> m.name().equals("Barbara Joziasse") && m.role().equals("Collegelid")));
    }

    @Test
    void readsTheCentraleRaadVanBeroepBoardAndJudges() {
        var crvb = new NlPepInstitutionsParser.Source("centrale_raad_van_beroep", "Centrale Raad van Beroep", "https://crvb");
        List<NlPepInstitutionsParser.Member> list = NlPepInstitutionsParser.extract(crvb, resource("centrale-raad-van-beroep.html"));

        assertTrue(list.size() >= 70, "bestuur, senior raadsheren, raadsheren et plaatsvervangers : " + list.size());
        assertTrue(list.stream().anyMatch(m -> m.name().equals("J.C. Boeree") && m.role().equals("president")));
        assertTrue(list.stream().anyMatch(m -> m.name().equals("H.G. Rottier") && m.role().equals("Senior raadsheer")));
        assertTrue(list.stream().anyMatch(m -> m.name().equals("J.N.A. Bootsma") && m.role().equals("Raadsheer")));
        assertTrue(list.stream().anyMatch(m -> m.name().equals("B. Barentsen") && m.role().equals("Raadsheer-plaatsvervanger")));
        assertTrue(list.stream().noneMatch(m -> m.name().contains("*") || m.name().toLowerCase().startsWith("mr")), "titres et astérisques retirés");
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        List<NlPepInstitutionsParser.Source> sources = List.of(RVS, HR, RK);
        NlPepInstitutionsParser.download(url -> resource(url.contains("rvs") ? "raad-van-state.html"
                : url.contains("hr") ? "hoge-raad.html" : "algemene-rekenkamer.html"), sources, rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(NlPepInstitutionsParser.ID_PREFIX, NlPepInstitutionsParser.TOPIC);

        String message = new NlPepInstitutionsParser().convert(rawDir, sources, dataset);

        assertTrue(message.contains("Raad van State=") && message.contains("Algemene Rekenkamer=3"), message);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.financial")));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.judicial")));
    }

    @Test
    void sourcesParameterIsValidated() {
        assertEquals(2, NlPepInstitutionsParser.sources("hoge_raad|Hoge Raad|https://a; algemene_rekenkamer|Rekenkamer|https://b").size());
        assertThrows(IllegalStateException.class, () -> NlPepInstitutionsParser.sources(" "));
        assertThrows(IllegalStateException.class, () -> NlPepInstitutionsParser.sources("inconnu|X|https://x"));
        assertThrows(IllegalStateException.class, () -> NlPepInstitutionsParser.sources("hoge_raad|Hoge Raad"));
    }

    @Test
    void failsClearlyWhenAPageHasNoMember(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("hoge_raad.html"), "<html>Verifying your browser</html>");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new NlPepInstitutionsParser()
                .convert(rawDir, List.of(HR), new FtmDatasetBuilder(NlPepInstitutionsParser.ID_PREFIX)));
        assertTrue(e.getMessage().contains("Hoge Raad"));
    }

    private static String resource(String name) {
        try (InputStream in = NlPepInstitutionsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
