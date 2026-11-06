package com.swiftcheck.aml.plugins.sepep.riksdagen;

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
 * Dix fiches réelles de l'API du Riksdag (6 novembre 2026) : Ulf Kristersson (Premier ministre), Elisabeth Thand Ringqvist
 * (cheffe de parti), Lars Leijonborg et Andreas Norlén (députés en fonction), Christina Gellerbrant Hagberg (Riksrevisor),
 * Thomas Norling (médiateur), Mats Hellström (décédé), Bosse Ringholm (ancien ministre), Roland Utbult (ancien député
 * récent) et Tore Claeson (ancien député, années 1990).
 */
class SePepRiksdagenParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Test
    void downloadsTheWholeListInOneCall(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        SePepRiksdagenParser.download(url -> {
            calls.add(url);
            return resource();
        }, "https://data.riksdagen.se/", rawDir);

        assertEquals(List.of("https://data.riksdagen.se/personlista/?utformat=json&rdlstatus=samtliga"), calls);
        assertTrue(Files.exists(rawDir.resolve("personlista.json")));
    }

    @Test
    void readsBirthYearDeathAndDatedMissions() throws Exception {
        List<SePepRiksdagenParser.Member> members = SePepRiksdagenParser.members(resource());

        assertEquals(10, members.size());
        var kristersson = members.stream().filter(m -> m.lastName().equals("Kristersson")).findFirst().orElseThrow();
        assertEquals("1963", kristersson.birthYear());
        assertTrue(kristersson.terms().stream().anyMatch(t -> "Statsminister".equals(t.role()) && t.end().isAfter(TODAY)));
        assertTrue(members.stream().anyMatch(m -> m.lastName().equals("Hellström") && m.dead()));
        assertEquals(null, members.stream().filter(m -> m.lastName().equals("Gellerbrant Hagberg")).findFirst().orElseThrow().birthYear(),
                "année de naissance absente de la source");
    }

    @Test
    void parliamentKeepsServingAndRecentMembersOnly(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 12, "parliament");

        Set<String> names = names(dataset);
        assertTrue(names.containsAll(Set.of("Lars Leijonborg", "Andreas Norlén", "Elisabeth Thand Ringqvist", "Roland Utbult")), names.toString());
        assertTrue(names.stream().noneMatch(n -> n.equals("Bosse Ringholm") || n.equals("Tore Claeson") || n.equals("Mats Hellström")), names.toString());
        FtmEntity norlen = person(dataset, "Andreas Norlén");
        assertEquals(Set.of("role.pep"), norlen.getValues("topics"));
        assertEquals(Set.of("1973"), norlen.getValues("birthDate"));
        assertTrue(norlen.getValues("position").contains("Président du Riksdag (talman)"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void historyLayerAddsFormerMembersOfTheLastFiftyYears(@TempDir Path rawDir) throws Exception {
        Set<String> names = names(run(rawDir, 600, "parliament"));

        assertTrue(names.containsAll(Set.of("Bosse Ringholm", "Tore Claeson")), names.toString());
        assertTrue(!names.contains("Mats Hellström"), "décédé");
    }

    @Test
    void governmentLayerReadsMinistersWithTheirDepartment(@TempDir Path rawDir) throws Exception {
        Set<String> current = names(run(rawDir, 12, "government"));
        assertEquals(Set.of("Ulf Kristersson"), current);
        FtmDatasetBuilder history = run(rawDir, 600, "government");
        assertTrue(names(history).containsAll(Set.of("Ulf Kristersson", "Bosse Ringholm")));
        assertTrue(person(history, "Ulf Kristersson").getValues("position").contains("Premier ministre (statsminister)"));
        assertTrue(person(history, "Bosse Ringholm").getValues("position").stream().anyMatch(p -> p.startsWith("Ministre (")));
    }

    @Test
    void partiesLayerKeepsLeadersAndOversightLayerKeepsAuditorAndOmbudsmen(@TempDir Path rawDir) throws Exception {
        assertEquals(Set.of("Ulf Kristersson", "Elisabeth Thand Ringqvist"), names(run(rawDir, 12, "parties")));
        Set<String> oversight = names(run(rawDir, 12, "oversight"));
        assertEquals(Set.of("Christina Gellerbrant Hagberg", "Thomas Norling"), oversight);
        assertTrue(person(run(rawDir, 12, "oversight"), "Thomas Norling").getValues("position").stream()
                .anyMatch(p -> p.startsWith("Médiateur parlementaire")));
    }

    @Test
    void rejectsAnUnknownScope() {
        assertThrows(IllegalArgumentException.class, () -> SePepRiksdagenParser.scope(Map.of("scope", "senate")));
    }

    @Test
    void failsClearlyWithoutDownloadedFile(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new SePepRiksdagenParser().convert(rawDir, TODAY, 12, "parliament",
                new FtmDatasetBuilder("x", "role.pep")));
    }

    private static FtmDatasetBuilder run(Path rawDir, int months, String scope) throws Exception {
        Files.writeString(rawDir.resolve("personlista.json"), resource(), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(SePepRiksdagenParser.prefix(scope), SePepRiksdagenParser.TOPIC);
        new SePepRiksdagenParser().convert(rawDir, TODAY, months, scope, dataset);
        return dataset;
    }

    private static Set<String> names(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow();
    }

    private static String resource() {
        try (InputStream in = SePepRiksdagenParserTest.class.getResourceAsStream("/personlista-sample.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
