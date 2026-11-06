package com.swiftcheck.aml.plugins.eepep.riigikogu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Réponses réelles de l'API du Riigikogu (6 novembre 2026) : la liste des compositions 7 à 15, et quelques membres des
 * compositions 15, 14 et 13 (un président, un vice-président, deux membres sortis, un décédé, un membre présent dans deux compositions,
 * un membre sans date de naissance). La liste est celle demandée en estonien (en anglais, les noms des anciens membres sont masqués). Courriels, téléphones et photos retirés des fichiers de test.
 */
class EePepRiigikoguParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final String API = "https://api.riigikogu.ee/api";

    @Test
    void readsCompositions() throws Exception {
        var compositions = EePepRiigikoguParser.compositions(resource("memberships.json"));

        assertEquals(9, compositions.size());
        assertEquals(7, compositions.get(0).number());
        assertEquals(LocalDate.of(1992, 9, 28), compositions.get(0).start());
        assertEquals(15, compositions.get(8).number());
    }

    @Test
    void downloadsOnlyTheCompositionsInTheWindow(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        EePepRiigikoguParser.download(url -> {
            calls.add(url);
            return url.endsWith("/memberships") ? resource("memberships.json") : "[]";
        }, API + "/", TODAY, 12, rawDir);

        assertEquals(List.of(API + "/memberships", API + "/plenary-members?lang=et&status=ALL&membership=15"), calls);

        List<String> wide = new ArrayList<>();
        EePepRiigikoguParser.download(url -> {
            wide.add(url);
            return url.endsWith("/memberships") ? resource("memberships.json") : "[]";
        }, API, TODAY, 600, rawDir);
        assertEquals(10, wide.size(), wide.toString());
        assertTrue(wide.contains(API + "/plenary-members?lang=et&status=ALL&membership=7"));
    }

    @Test
    void standardWindowKeepsServingMembersOnly(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EePepRiigikoguParser.ID_PREFIX, EePepRiigikoguParser.TOPIC);
        String message = run(rawDir, 12, dataset);

        assertEquals(Set.of("Lauri Hussar", "Jaak Aab", "Arvo Aller"), names(dataset));
        assertTrue(message.contains("2 mandats antérieurs"), message);
        assertTrue(message.contains("0 entrées sans nom ignorées"), message);
        FtmEntity hussar = person(dataset, "Lauri Hussar");
        assertEquals(Set.of("role.pep"), hussar.getValues("topics"));
        assertTrue(hussar.getValues("position").contains("Membre du Riigikogu (Riigikogu liige)"));
        assertTrue(hussar.getValues("position").stream().anyMatch(p -> p.contains("Président du Riigikogu")),
                hussar.getValues("position").toString());
        assertEquals(1, hussar.getValues("birthDate").size());
        assertEquals(Set.of("male"), hussar.getValues("gender"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("status").contains("current")
                && e.getValues("description").stream().anyMatch(d -> d.contains("Groupe : "))));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void historyAddsFormerMembersAndMergesMandatesAndSkipsTheDead(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EePepRiigikoguParser.ID_PREFIX, EePepRiigikoguParser.TOPIC);
        run(rawDir, 600, dataset);

        Set<String> names = names(dataset);
        assertEquals(9, names.size(), names.toString());
        assertTrue(names.containsAll(Set.of("Hele Everaus", "Maksim Iljin", "Merry Aart", "Arto Aas", "Jaak Aaviksoo")), names.toString());
        assertTrue(!names.contains("Siim Kallas"), "décès enregistré (2026-08-22) : écarté");
        assertTrue(!names.contains("Andres Ammas"), "décès enregistré (2018) : écarté");

        FtmEntity aab = person(dataset, "Jaak Aab");
        assertEquals(2, occupanciesOf(dataset, aab).size(), "deux compositions, une seule personne");
        assertEquals(1, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Arto Aas")).count());
        assertEquals(2, occupanciesOf(dataset, person(dataset, "Arto Aas")).size());
        assertTrue(occupanciesOf(dataset, person(dataset, "Hele Everaus")).get(0).getValues("endDate").contains("2025-04-08"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void aMemberWithoutNameIsIgnored() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resource("members-15.json"));
        var hidden = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"uuid\":\"u\",\"fullName\":null,\"firstName\":null,\"lastName\":null,\"plenaryMembership\":{\"startDate\":\"2025-03-25\"}}");
        assertNull(EePepRiigikoguParser.member(hidden, 15), "nom absent de la réponse (cas de la liste en anglais)");
        assertEquals("Lauri Hussar", EePepRiigikoguParser.member(json.get(0), 15).name());
    }

    @Test
    void failsClearlyWithoutDownloadedFiles(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new EePepRiigikoguParser().convert(rawDir, TODAY, 12,
                new FtmDatasetBuilder("x", "role.pep")));
    }

    @Test
    void rejectsAnInvalidMonthsParameter() {
        assertThrows(IllegalArgumentException.class, () -> EePepRiigikoguParser.monthsAfterEnd(Map.of("monthsAfterEnd", "douze")));
    }

    private static String run(Path rawDir, int months, FtmDatasetBuilder dataset) throws Exception {
        for (String f : List.of("memberships.json", "members-15.json", "members-14.json", "members-13.json")) {
            Files.writeString(rawDir.resolve(f), resource(f), StandardCharsets.UTF_8);
        }
        return new EePepRiigikoguParser().convert(rawDir, TODAY, months, dataset);
    }

    private static Set<String> names(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow();
    }

    private static List<FtmEntity> occupanciesOf(FtmDatasetBuilder dataset, FtmEntity person) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(person.getId())).toList();
    }

    private static String resource(String name) {
        try (InputStream in = EePepRiigikoguParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
