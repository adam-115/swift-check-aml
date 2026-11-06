package com.swiftcheck.aml.plugins.fipep.parliament;

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
 * Sept fiches réelles de l'API de l'Eduskunta (6 novembre 2026) : trois députés en fonction (dont Sari Essayah, ministre),
 * Matti Ahde (décédé), Pekka Haavisto (mandat terminé le 6 mars 2026), Kari Häkämies (1998) et Markus Aaltonen (1999).
 */
class FiPepParliamentParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final String API = "https://avoindata.eduskunta.fi/api/v1";

    @Test
    void downloadsPagesUntilThereIsNoMore(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        String last = resource();
        String first = last.replace("\"hasMore\":false", "\"hasMore\":true");
        FiPepParliamentParser.download(url -> {
            calls.add(url);
            return calls.size() == 1 ? first : last;
        }, API + "/", rawDir);

        assertEquals(List.of(API + "/tables/MemberOfParliament/rows?perPage=100&page=0",
                API + "/tables/MemberOfParliament/rows?perPage=100&page=1"), calls);
        assertTrue(Files.exists(rawDir.resolve("members-000.json")));
        assertTrue(Files.exists(rawDir.resolve("members-001.json")));
    }

    @Test
    void readsDatesBirthYearAndMinistries() throws Exception {
        List<FiPepParliamentParser.Member> members = FiPepParliamentParser.members(resource());

        assertEquals(7, members.size());
        var haavisto = members.stream().filter(m -> m.lastName().equals("Haavisto")).findFirst().orElseThrow();
        assertEquals("1958", haavisto.birthYear());
        assertEquals(1, haavisto.seats().stream().filter(t -> t.end() != null && t.end().equals(LocalDate.of(2026, 3, 6))).count());
        assertTrue(haavisto.ministries().stream().anyMatch(t -> "ulkoministeri".equals(t.title()) && "Marin".equals(t.government())
                && LocalDate.of(2019, 12, 10).equals(t.start()) && LocalDate.of(2023, 6, 20).equals(t.end())));
        assertEquals("Ben Zyskowicz", members.stream().filter(m -> m.lastName().equals("Zyskowicz")).findFirst().orElseThrow().displayName(),
                "espace initial du nom de famille retiré");
    }

    @Test
    void parliamentKeepsCurrentAndRecentMembersButNotDeadOrOldOnes(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 12, "parliament");

        Set<String> names = personNames(dataset);
        assertEquals(Set.of("Sari Essayah", "Ben Zyskowicz", "Kimmo Kiljunen", "Pekka Haavisto"), names);
        FtmEntity ben = person(dataset, "Ben Zyskowicz");
        assertEquals(Set.of("role.pep"), ben.getValues("topics"));
        assertEquals(Set.of("1954"), ben.getValues("birthDate"));
        assertEquals(Set.of("male"), ben.getValues("gender"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("endDate").contains("2026-03-06") && e.getValues("status").contains("ended")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void historyLayerAddsFormerMembersOfTheLastFiftyYears(@TempDir Path rawDir) throws Exception {
        Set<String> names = personNames(run(rawDir, 600, "parliament"));

        assertEquals(Set.of("Sari Essayah", "Ben Zyskowicz", "Kimmo Kiljunen", "Pekka Haavisto", "Kari Häkämies", "Markus Aaltonen"), names);
        assertTrue(!names.contains("Matti Ahde"), "décédé");
    }

    @Test
    void governmentLayerKeepsMinistersWithExactDates(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder current = run(rawDir, 12, "government");
        assertEquals(Set.of("Sari Essayah"), personNames(current));

        FtmDatasetBuilder history = run(rawDir, 600, "government");
        assertEquals(Set.of("Sari Essayah", "Pekka Haavisto", "Kari Häkämies"), personNames(history));
        assertTrue(person(history, "Pekka Haavisto").getValues("position").contains("Ministre : ulkoministeri"));
        assertTrue(history.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("startDate").contains("2019-12-10") && e.getValues("endDate").contains("2023-06-20")
                && e.getValues("description").stream().anyMatch(d -> d.contains("Marin"))));
    }

    @Test
    void rejectsAnUnknownScope() {
        assertThrows(IllegalArgumentException.class, () -> FiPepParliamentParser.scope(java.util.Map.of("scope", "senate")));
    }

    @Test
    void failsClearlyWithoutDownloadedPages(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new FiPepParliamentParser().convert(rawDir, TODAY, 12, "parliament",
                new FtmDatasetBuilder("x", "role.pep")));
    }

    private static FtmDatasetBuilder run(Path rawDir, int months, String scope) throws Exception {
        Files.writeString(rawDir.resolve("members-000.json"), resource(), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FiPepParliamentParser.prefix(scope), FiPepParliamentParser.TOPIC);
        new FiPepParliamentParser().convert(rawDir, TODAY, months, scope, dataset);
        return dataset;
    }

    private static Set<String> personNames(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(java.util.stream.Collectors.toSet());
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow();
    }

    private static String resource() {
        try (InputStream in = FiPepParliamentParserTest.class.getResourceAsStream("/members-sample.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
