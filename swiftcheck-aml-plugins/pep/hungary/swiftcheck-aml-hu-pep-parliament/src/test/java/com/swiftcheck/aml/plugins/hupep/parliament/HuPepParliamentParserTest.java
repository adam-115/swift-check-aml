package com.swiftcheck.aml.plugins.hupep.parliament;

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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Fichiers JSON réels du registre des représentants (vtr.valasztas.hu, 7 novembre 2026) : la liste des cycles, la version du cycle
 * 130 (2026-2030), quatre députés en fonction et deux mandats terminés (Orbán Viktor et Kövér László, démissionnaires).
 */
class HuPepParliamentParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);
    private static final String BASE = "https://vtr.valasztas.hu/kepviselo/data";

    @Test
    void picksTheCurrentParliamentaryCycle() throws Exception {
        assertEquals("130", HuPepParliamentParser.currentCycle(resource("cycles.json"), TODAY));
        assertEquals("90", HuPepParliamentParser.currentCycle(resource("cycles.json"), LocalDate.of(2025, 1, 1)),
                "avant le 9 mai 2026 : la législature 2022-2026");
        assertThrows(IllegalStateException.class, () -> HuPepParliamentParser.currentCycle("{\"list\":[]}", TODAY));
    }

    @Test
    void readsActiveAndEndedMandates() throws Exception {
        var active = HuPepParliamentParser.mandates(resource("active.json"), false);
        assertEquals(4, active.size());
        var magyar = active.stream().filter(m -> m.name().equals("Magyar Péter")).findFirst().orElseThrow();
        assertEquals("TISZA", magyar.party());
        assertEquals(LocalDate.of(2026, 4, 12), magyar.start());
        assertEquals("Egyéni választókerületi képviselő", magyar.kind());
        assertTrue(magyar.district().startsWith("Budapest főváros, 03. OEVK"), magyar.district());
        assertTrue(active.stream().filter(m -> m.name().equals("Koncz Áron")).findFirst().orElseThrow().titled(), "indicateur Dr.");

        var ended = HuPepParliamentParser.mandates(resource("ended.json"), true);
        var orban = ended.stream().filter(m -> m.name().equals("Orbán Viktor")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 4, 28), orban.end());
        assertEquals("Lemondás", orban.endReason());
        assertEquals("Országos pártlistás képviselő", orban.kind());
    }

    @Test
    void addsTheGivenNameFirstAliases() {
        assertEquals(Set.of("Péter Magyar"), HuPepParliamentParser.aliases("Magyar Péter"));
        assertTrue(HuPepParliamentParser.aliases("Fónagy János Vilmos").containsAll(Set.of("János Vilmos Fónagy", "Vilmos Fónagy János")));
        assertTrue(HuPepParliamentParser.aliases("Hallerné Dr. Nagy Anikó").contains("Anikó Hallerné Nagy"), "« Dr. » ignoré");
        assertTrue(HuPepParliamentParser.aliases("Mononyme").isEmpty());
    }

    @Test
    void downloadsCyclesVersionThenBothLists(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        HuPepParliamentParser.download(url -> {
            calls.add(url);
            if (url.endsWith("Ciklusok.json")) {
                return resource("cycles.json");
            }
            return url.endsWith("config.json") ? resource("config.json") : "{\"list\":[]}";
        }, BASE + "/", TODAY, rawDir);

        assertEquals(List.of(BASE + "/Ciklusok.json", BASE + "/130/config.json",
                BASE + "/130/20261106080000_506256/AktivKepv.json", BASE + "/130/20261106080000_506256/MegszuntKepv.json"), calls);
        assertTrue(Files.exists(rawDir.resolve("ended.json")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        for (String f : List.of("active.json", "ended.json")) {
            Files.writeString(rawDir.resolve(f), resource(f), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(HuPepParliamentParser.ID_PREFIX, HuPepParliamentParser.TOPIC);

        String message = new HuPepParliamentParser().convert(rawDir, TODAY, 12, dataset);

        assertTrue(message.startsWith("4 députés en fonction, 2 mandats terminés lus"), message);
        Set<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
        assertEquals(Set.of("Magyar Péter", "Tanács Zoltán", "Koncz Áron", "Hallerné Dr. Nagy Anikó", "Orbán Viktor", "Kövér László"), names);
        FtmEntity orban = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Orbán Viktor")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), orban.getValues("topics"));
        assertEquals(Set.of("Viktor Orbán"), orban.getValues("alias"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("status").contains("ended") && e.getValues("endDate").contains("2026-04-28")
                && e.getValues("description").stream().anyMatch(d -> d.contains("Lemondás"))));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void aShortWindowDropsEndedMandatesOlderThanTheCutoff(@TempDir Path rawDir) throws Exception {
        for (String f : List.of("active.json", "ended.json")) {
            Files.writeString(rawDir.resolve(f), resource(f), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(HuPepParliamentParser.ID_PREFIX, HuPepParliamentParser.TOPIC);

        String message = new HuPepParliamentParser().convert(rawDir, LocalDate.of(2026, 11, 7), 1, dataset);

        assertTrue(message.contains("2 antérieurs à la fenêtre de 1 mois écartés"), message);
    }

    @Test
    void failsClearlyWithoutFilesOrActiveMembers(@TempDir Path rawDir) throws Exception {
        assertThrows(IllegalStateException.class, () -> new HuPepParliamentParser().convert(rawDir, TODAY, 12, new FtmDatasetBuilder("x", "role.pep")));
        Files.writeString(rawDir.resolve("active.json"), "{\"list\":[]}", StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("ended.json"), "{\"list\":[]}", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new HuPepParliamentParser().convert(rawDir, TODAY, 12, new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalArgumentException.class, () -> HuPepParliamentParser.monthsAfterEnd(Map.of("monthsAfterEnd", "douze")));
    }

    private static String resource(String name) {
        try (InputStream in = HuPepParliamentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
