package com.swiftcheck.aml.plugins.dkpep.folketing;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Onze fiches réelles de l'API du Folketing (6 novembre 2026) : Mette Frederiksen, Nicolai Wammen, Alex Ahrendtsen, Astrid Krag,
 * Sophie Løhde, Søren Espersen, Lotte Bundsgaard, Laue Traberg Smidt, Lone Dybkjær (décédée), Benny Engelbrecht (Statsrevisor) et
 * Pia Kjærsgaard (ancienne présidente du Folketing).
 */
class DkPepFolketingParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Test
    void readsDatedCareerLines() {
        var minister = DkPepFolketingParser.term("Forsvarsminister, 9. august 2013 – 28. juni 2015. ");
        assertEquals("Forsvarsminister", minister.label());
        assertEquals(LocalDate.of(2013, 8, 9), minister.start());
        assertEquals(LocalDate.of(2015, 6, 28), minister.end());

        var open = DkPepFolketingParser.term("Justitsminister fra 3. juni 2026.");
        assertEquals("Justitsminister", open.label());
        assertNull(open.end());

        var speaker = DkPepFolketingParser.term("Folketingets formand 21. juni 2019 - 1. november 2022.");
        assertEquals("Folketingets formand", speaker.label());
        assertEquals(LocalDate.of(2022, 11, 1), speaker.end());

        var seat = DkPepFolketingParser.term("Folketingsmedlem for Socialdemokratiet i Nordjyllands Storkreds fra 5. juni 2019. ");
        assertNull(seat.end());
        assertNull(DkPepFolketingParser.term("Ingen datoer her"));
    }

    @Test
    void readsBirthDeathPartyAndCareer() throws Exception {
        var members = members();

        assertEquals(11, members.size());
        var mette = members.stream().filter(m -> m.name().equals("Mette Frederiksen")).findFirst().orElseThrow();
        assertEquals("1977-11-19", mette.birthDate());
        assertEquals("Kvinde", mette.sex());
        assertEquals("Socialdemokratiet", mette.party());
        assertTrue(mette.ministries().stream().anyMatch(t -> t.label().startsWith("Statsminister") && t.end() == null));
        assertTrue(mette.seats().size() >= 3, "trois circonscriptions successives");
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Lone Dybkjær") && m.dead()));
    }

    @Test
    void parliamentKeepsServingMembersAndNotOldOnes(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 12, "parliament");

        Set<String> names = names(dataset);
        assertTrue(names.containsAll(Set.of("Mette Frederiksen", "Alex Ahrendtsen", "Astrid Krag")), names.toString());
        assertTrue(names.stream().noneMatch(n -> n.equals("Lotte Bundsgaard") || n.equals("Laue Traberg Smidt") || n.equals("Lone Dybkjær")),
                names.toString());
        FtmEntity alex = person(dataset, "Alex Ahrendtsen");
        assertEquals(Set.of("role.pep"), alex.getValues("topics"));
        assertTrue(alex.getValues("position").contains("Député au Folketing (folketingsmedlem)"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void historyLayerAddsFormerMembersAndSpeakers(@TempDir Path rawDir) throws Exception {
        Set<String> names = names(run(rawDir, 600, "parliament"));

        assertTrue(names.containsAll(Set.of("Lotte Bundsgaard", "Laue Traberg Smidt", "Pia Kjærsgaard")), names.toString());
        assertTrue(!names.contains("Lone Dybkjær"), "décédée");
        assertTrue(person(run(rawDir, 600, "parliament"), "Pia Kjærsgaard").getValues("position")
                .contains("Président du Folketing (Folketingets formand)"));
    }

    @Test
    void governmentLayerReadsMinisters(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder current = run(rawDir, 12, "government");
        assertTrue(names(current).containsAll(Set.of("Mette Frederiksen", "Nicolai Wammen")), names(current).toString());
        assertTrue(person(current, "Mette Frederiksen").getValues("position").contains("Premier ministre (statsminister)"));

        FtmDatasetBuilder history = run(rawDir, 600, "government");
        assertTrue(names(history).contains("Lotte Bundsgaard"));
        assertTrue(person(history, "Nicolai Wammen").getValues("position").stream().anyMatch(p -> p.startsWith("Ministre : ")));
    }

    @Test
    void oversightLayerReadsStateAuditors(@TempDir Path rawDir) throws Exception {
        Set<String> names = names(run(rawDir, 12, "oversight"));

        assertTrue(names.contains("Benny Engelbrecht"), names.toString());
        assertTrue(person(run(rawDir, 12, "oversight"), "Benny Engelbrecht").getValues("position").stream()
                .anyMatch(p -> p.startsWith("Statsrevisor")));
    }

    @Test
    void downloadsPagesUntilAShortPage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        DkPepFolketingParser.download(url -> {
            calls.add(url);
            return resource();
        }, "https://oda.ft.dk/api/", rawDir);

        assertEquals(1, calls.size());
        assertTrue(calls.get(0).startsWith("https://oda.ft.dk/api/Akt%C3%B8r?$filter=typeid%20eq%205&$orderby=id&$top=100&$skip=0"), calls.get(0));
        assertTrue(Files.exists(rawDir.resolve("aktoerer-000.json")));
    }

    @Test
    void rejectsAnUnknownScope() {
        assertThrows(IllegalArgumentException.class, () -> DkPepFolketingParser.scope(Map.of("scope", "senate")));
    }

    @Test
    void failsClearlyWithoutDownloadedPages(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new DkPepFolketingParser().convert(rawDir, TODAY, 12, "parliament",
                new FtmDatasetBuilder("x", "role.pep")));
    }

    private static List<DkPepFolketingParser.Member> members() throws IOException {
        var out = new ArrayList<DkPepFolketingParser.Member>();
        for (var row : new ObjectMapper().readTree(resource()).path("value")) {
            out.add(DkPepFolketingParser.member(row.path("id").asText(), row.path("navn").asText(), row.path("biografi").asText()));
        }
        return out;
    }

    private static FtmDatasetBuilder run(Path rawDir, int months, String scope) throws Exception {
        Files.writeString(rawDir.resolve("aktoerer-000.json"), resource(), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DkPepFolketingParser.prefix(scope), DkPepFolketingParser.TOPIC);
        new DkPepFolketingParser().convert(rawDir, TODAY, months, scope, dataset);
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
        try (InputStream in = DkPepFolketingParserTest.class.getResourceAsStream("/aktoerer-sample.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
