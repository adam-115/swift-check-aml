package com.swiftcheck.aml.plugins.nlpep.parliament;

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
 * Extraits réels de l'API OData de la Tweede Kamer et de la liste officielle de l'Eerste Kamer (5 novembre 2026) :
 * deux députés en fonction, deux anciens députés (sortis en novembre 2025 et en mars 2026) et trois sénateurs, dont un
 * sans fiche dans l'API.
 */
class NlPepParliamentParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final String ODATA = "https://gegevensmagazijn.tweedekamer.nl/OData/v4/2.0";
    private static final String SENATE = "https://www.eerstekamer.nl/alle_leden";

    @Test
    void keepsCurrentAndRecentlyFormerMembersOfTheTweedeKamer(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 12);

        FtmEntity current = person(dataset, "Clemminck");
        assertEquals(Set.of("role.pep"), current.getValues("topics"));
        assertTrue(current.getValues("position").contains(NlPepParliamentParser.TWEEDE));
        assertEquals(1, current.getValues("birthDate").size());
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("status").contains("current") && e.getValues("startDate").contains("2025-11-12")
                && e.getValues("description").stream().anyMatch(d -> d.startsWith("Fractie : "))));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("endDate").contains("2026-03-30") && e.getValues("status").contains("ended")), "Rajkowski");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void dropsFormerMembersAfterTheRetentionWindow(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 3);

        assertTrue(dataset.entities().stream().noneMatch(e -> "Occupancy".equals(e.getSchema()) && e.getValues("status").contains("ended")),
                "fins de mandat en novembre 2025 et mars 2026 : hors d'une fenêtre de 3 mois");
    }

    @Test
    void readsTheSenatorsAndMatchesThemWithTheirApiRecord(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 12);

        FtmEntity matched = person(dataset, "Apeldoorn");
        assertTrue(matched.getValues("position").contains(NlPepParliamentParser.EERSTE));
        assertEquals(Set.of("1970-03-22"), matched.getValues("birthDate"), "naissance de la liste officielle = celle de la fiche");
        assertTrue(matched.getValues("name").iterator().next().contains("Apeldoorn"));
        FtmEntity alone = person(dataset, "Aelst");
        assertEquals(Set.of("1988-12-01"), alone.getValues("birthDate"), "sénateur absent de l'API : publié depuis la liste");
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("description").contains("Groupe : SP")));
    }

    @Test
    void parsesTheOfficialSenatePage() throws Exception {
        List<NlPepParliamentParser.Senator> senators = NlPepParliamentParser.senators(resource("eerste-kamer.html"));

        assertEquals(3, senators.size());
        assertEquals("R. van Aelst-den Uijl MA", senators.get(2).name());
        assertEquals(997, senators.get(2).days());
        assertEquals("SP", senators.get(2).party());
    }

    @Test
    void deceasedMembersAreSkipped(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("persoon-000.json"), "{\"value\":[{\"Id\":\"a\",\"Achternaam\":\"Levend\",\"Voornamen\":\"Jan\","
                + "\"Geslacht\":\"man\",\"Geboortedatum\":\"1970-01-01\"},{\"Id\":\"b\",\"Achternaam\":\"Overleden\",\"Voornamen\":\"Piet\","
                + "\"Geslacht\":\"man\",\"Geboortedatum\":\"1950-01-01\",\"Overlijdensdatum\":\"2026-01-01\"}]}");
        Files.writeString(rawDir.resolve("zetel-000.json"), "{\"value\":[{\"Persoon_Id\":\"a\",\"Van\":\"2025-11-12T00:00:00+01:00\",\"TotEnMet\":null},"
                + "{\"Persoon_Id\":\"b\",\"Van\":\"2020-01-01T00:00:00+01:00\",\"TotEnMet\":\"2026-01-01T00:00:00+01:00\"}]}");
        Files.writeString(rawDir.resolve(NlPepParliamentParser.SENATE_FILE), resource("eerste-kamer.html"));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(NlPepParliamentParser.ID_PREFIX, NlPepParliamentParser.TOPIC);

        NlPepParliamentParser.Report report = new NlPepParliamentParser().convert(rawDir, TODAY, 12, dataset);

        assertEquals(1, report.lowerCurrent());
        assertEquals(1, report.skippedDead());
        assertTrue(dataset.entities().stream().noneMatch(e -> e.getValues("name").stream().anyMatch(n -> n.contains("Overleden"))));
    }

    @Test
    void downloadsPagesUntilTheLastOne(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        NlPepParliamentParser.download(url -> {
            calls.add(url);
            if (url.equals(SENATE)) {
                return resource("eerste-kamer.html");
            }
            return "{\"value\":[]}";
        }, ODATA, SENATE, TODAY, 12, rawDir);

        assertEquals(3, calls.size(), "une page vide de personnes, une de sièges, la liste du Sénat");
        assertTrue(calls.get(1).contains("TotEnMet%20ge%202025-11-05T00:00:00Z"), calls.get(1));
        assertThrows(IllegalStateException.class, () -> NlPepParliamentParser.download(url -> "{}", ODATA, SENATE, TODAY, 12, rawDir));
    }

    private static FtmDatasetBuilder run(Path rawDir, int months) throws IOException {
        for (String f : List.of("persoon-000.json", "zetel-000.json", NlPepParliamentParser.SENATE_FILE)) {
            Files.writeString(rawDir.resolve(f), resource(f.equals(NlPepParliamentParser.SENATE_FILE) ? f : f), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(NlPepParliamentParser.ID_PREFIX, NlPepParliamentParser.TOPIC);
        new NlPepParliamentParser().convert(rawDir, TODAY, months, dataset);
        return dataset;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String lastNamePart) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").stream().anyMatch(n -> n.contains(lastNamePart))).findFirst()
                .orElseThrow(() -> new AssertionError("Personne absente : " + lastNamePart));
    }

    private static String resource(String name) {
        try (InputStream in = NlPepParliamentParserTest.class.getResourceAsStream("/parliament/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
