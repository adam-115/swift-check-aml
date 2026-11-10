package com.swiftcheck.aml.plugins.bgpep.institutions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Pages réelles du 8 novembre 2026, scripts retirés : la biographie de la présidente Илияна Йотова (president.bg) et la page des
 * douze juges de la Cour constitutionnelle (constcourt.bg).
 */
class BgPepInstitutionsParserTest {

    @Test
    void readsThePresidentWithBirthAndStartOfOffice() {
        var president = BgPepInstitutionsParser.president(resource("president.html"));

        assertEquals("Илияна Йотова", president.name());
        assertEquals("Президент на Република България", president.role());
        assertEquals(LocalDate.of(1964, 10, 24), president.birthDate());
        assertEquals(LocalDate.of(2026, 1, 23), president.start());
    }

    @Test
    void readsTheTwelveJudgesWithTheirBirth() {
        var judges = BgPepInstitutionsParser.judges(resource("judges.html"));

        assertEquals(12, judges.size());
        var chair = judges.get(0);
        assertEquals("Павлина Панова", chair.name());
        assertEquals("Председател на Конституционния съд", chair.role());
        assertEquals(LocalDate.of(1967, 2, 12), chair.birthDate());
        assertEquals("Грамада", chair.birthPlace());
        assertEquals(11, judges.stream().filter(j -> j.role().equals("Съдия от Конституционния съд")).count());
        var numeric = judges.stream().filter(j -> j.name().equals("Борислав Белазелков")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(1956, 5, 16), numeric.birthDate(), "date écrite 16.05.1956г.");
        var plain = judges.stream().filter(j -> j.name().equals("Орлин Колев")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(1978, 12, 14), plain.birthDate(), "« Орлин Колев е роден на … »");
        assertTrue(judges.stream().allMatch(j -> j.birthDate() != null && j.birthPlace() != null));
    }

    @Test
    void addsReverseOrderAndOfficialLatinTransliteration() {
        assertTrue(BgPepInstitutionsParser.aliases("Илияна Йотова").containsAll(Set.of("Йотова Илияна", "Iliyana Yotova", "Iliana Yotova", "Yotova Iliana")));
        assertEquals("Nadezhda Dzhelepova", BgPepInstitutionsParser.transliterate("Надежда Джелепова"));
    }

    @Test
    void downloadsBothPages(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        BgPepInstitutionsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, BgPepInstitutionsParser.DEFAULT_PRESIDENT_URL, BgPepInstitutionsParser.DEFAULT_JUDGES_URL, rawDir);

        assertEquals(List.of(BgPepInstitutionsParser.DEFAULT_PRESIDENT_URL, BgPepInstitutionsParser.DEFAULT_JUDGES_URL), calls);
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("president.html"), resource("president.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("judges.html"), resource("judges.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BgPepInstitutionsParser.ID_PREFIX, BgPepInstitutionsParser.TOPIC);

        String message = new BgPepInstitutionsParser().convert(rawDir, BgPepInstitutionsParser.DEFAULT_PRESIDENT_URL,
                BgPepInstitutionsParser.DEFAULT_JUDGES_URL, dataset);

        assertTrue(message.startsWith("1 président de la République et 12 juges"), message);
        assertEquals(13, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        FtmEntity yotova = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Илияна Йотова")).findFirst().orElseThrow();
        assertEquals(Set.of("1964-10-24"), yotova.getValues("birthDate"));
        assertEquals(Set.of("role.pep"), yotova.getValues("topics"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.head")));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.judicial")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePagesChangedOrAreMissing(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new BgPepInstitutionsParser().convert(rawDir, "x", "y", new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> BgPepInstitutionsParser.president("<html/>"));
        assertThrows(IllegalStateException.class, () -> BgPepInstitutionsParser.judges("<html/>"));
    }

    private static String resource(String name) {
        try (InputStream in = BgPepInstitutionsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
