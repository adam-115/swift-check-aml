package com.swiftcheck.aml.plugins.fipep.ambassadors;

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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Pages réelles d'um.fi (6 novembre 2026) : listes, quatre fiches (Vienne, New York, Vatican, Ramallah) et ambassadeurs itinérants. */
class FiPepAmbassadorsParserTest {

    @Test
    void listsMissionsWithoutHonoraryConsulates() {
        var byCountry = FiPepAmbassadorsParser.missions(resource("list-by-country.html"));
        var orgs = FiPepAmbassadorsParser.missions(resource("list-organisations.html"));

        assertEquals(87, byCountry.size(), "ambassades, consulats généraux et bureaux, sans consulats honoraires");
        assertTrue(byCountry.stream().noneMatch(m -> m.name().contains("Honorary")));
        assertTrue(byCountry.stream().anyMatch(m -> m.id().equals("120092") && m.name().equals("Embassy of Finland, Vienna")
                && m.url().startsWith("https://um.fi/finland-s-representation-abroad-by-country/")));
        assertEquals(6, orgs.size(), "ONU New York, Genève, OTAN, Conseil de l'Europe, UE, OCDE");
        assertTrue(orgs.get(0).url().startsWith("https://um.fi/missions-to-international-organisations/"));
    }

    @Test
    void readsTheHeadOfMissionOnly() {
        var vienna = FiPepAmbassadorsParser.readHeads(resource("mission-vienna.html"));
        assertEquals(1, vienna.size());
        assertEquals("Nina Vaskunlahti", vienna.get(0).name());
        assertEquals("Head of Mission", vienna.get(0).title());

        var newYork = FiPepAmbassadorsParser.readHeads(resource("mission-new-york.html"));
        assertEquals("Miia Rantanen", newYork.get(0).name());
        assertEquals("Consul General, Ambassador", newYork.get(0).title());

        var ramallah = FiPepAmbassadorsParser.readHeads(resource("mission-ramallah.html"));
        assertEquals(List.of("Tarja Kangaskorte"), ramallah.stream().map(FiPepAmbassadorsParser.Head::name).toList(),
                "le chef adjoint n'est pas repris");

        assertTrue(FiPepAmbassadorsParser.readHeads(resource("mission-vatican.html")).isEmpty(), "fiche sans personnel");
    }

    @Test
    void readsRovingAmbassadors() {
        var roving = FiPepAmbassadorsParser.readHeads(resource("roving.html"));

        assertTrue(roving.size() >= 1, roving.size() + " ambassadeurs itinérants");
        assertTrue(roving.stream().anyMatch(h -> h.name().equals("Kirsti Narinen") && h.title().startsWith("Roving Ambassador")));
        assertTrue(roving.stream().noneMatch(h -> h.name().equals("Anna Azcuy Sanchez")), "attachée");
    }

    @Test
    void downloadsListsThenMissionsAtTheRequestedPace(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        FiPepAmbassadorsParser.download(url -> {
            calls.add(url);
            if (url.equals(FiPepAmbassadorsParser.LIST_BY_COUNTRY)) {
                return resource("list-by-country.html");
            }
            if (url.equals(FiPepAmbassadorsParser.LIST_ORGANISATIONS)) {
                return resource("list-organisations.html");
            }
            return resource("mission-vienna.html");
        }, rawDir, 3);

        assertEquals(3 + 3, calls.size(), "trois pages de liste ou itinérants, puis trois fiches (maxMissions = 3)");
        assertEquals(FiPepAmbassadorsParser.LIST_BY_COUNTRY, calls.get(0));
        assertEquals(FiPepAmbassadorsParser.ROVING, calls.get(2));
        assertTrue(Files.exists(rawDir.resolve("mission-122152.html")));
    }

    @Test
    void neverGoesBelowTheRobotsCrawlDelay() {
        assertEquals(20_000, FiPepAmbassadorsParser.delayMs(Map.of("delayMs", "500")));
        assertEquals(20_000, FiPepAmbassadorsParser.delayMs(Map.of()));
        assertEquals(30_000, FiPepAmbassadorsParser.delayMs(Map.of("delayMs", "30000")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("list-by-country.html"), resource("list-by-country.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("mission-120092.html"), resource("mission-vienna.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("mission-120062.html"), resource("mission-new-york.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("mission-120002.html"), resource("mission-vatican.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("roving.html"), resource("roving.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FiPepAmbassadorsParser.ID_PREFIX, FiPepAmbassadorsParser.TOPIC);

        String message = new FiPepAmbassadorsParser().convert(rawDir, dataset);

        assertTrue(message.contains("Sans chef de mission : Holy See (Vatican)"), message);
        FtmEntity nina = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Nina Vaskunlahti")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), nina.getValues("topics"));
        assertEquals(Set.of("Ambassadeur (chef de mission) – Embassy of Finland, Vienna"), nina.getValues("position"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Person".equals(e.getSchema())
                && e.getValues("position").contains("Consul général – Consulate General of Finland, New York")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWithoutDownloadedList(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new FiPepAmbassadorsParser().convert(rawDir, new FtmDatasetBuilder("x", "role.pep")));
    }

    private static String resource(String name) {
        try (InputStream in = FiPepAmbassadorsParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
