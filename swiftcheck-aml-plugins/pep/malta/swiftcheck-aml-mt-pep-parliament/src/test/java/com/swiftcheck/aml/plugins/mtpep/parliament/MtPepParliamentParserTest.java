package com.swiftcheck.aml.plugins.mtpep.parliament;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Pages réelles de parlament.mt (9 novembre 2026), scripts retirés : les groupes parlementaires de la 15<sup>e</sup> législature (2026-) et
 * de la 14<sup>e</sup> (2022-2026), et la fiche du président de la Chambre. 79 députés dans chaque législature, 56 réélus.
 */
class MtPepParliamentParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Test
    void readsTheMembersGroupsAndRoles() {
        var current = MtPepParliamentParser.legislature(resource("current.html"));

        assertEquals(79, current.members().size());
        assertEquals(2026, current.startYear());
        assertEquals(0, current.endYear(), "législature en cours");
        var pm = current.members().stream().filter(m -> m.slug().equals("abela-robert")).findFirst().orElseThrow();
        assertEquals("Robert Abela", pm.name());
        assertEquals("Abela, Robert", pm.published());
        assertEquals("Labour Party", pm.group());
        assertEquals("Prime Minister", pm.role());
        assertTrue(current.members().stream().anyMatch(m -> "Opposition Leader".equals(m.role())));
        assertEquals(37, current.members().stream().filter(m -> m.group().equals("Nationalist Party")).count());

        var previous = MtPepParliamentParser.legislature(resource("previous.html"));
        assertEquals(79, previous.members().size());
        assertEquals(2022, previous.startYear());
        assertEquals(2026, previous.endYear());
    }

    @Test
    void readsTheSpeaker() {
        assertEquals("Carmelo Abela", MtPepParliamentParser.speaker(resource("speaker.html")));
        assertThrows(IllegalStateException.class, () -> MtPepParliamentParser.speaker("<html/>"));
    }

    @Test
    void downloadsTheThreePages(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        MtPepParliamentParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, MtPepParliamentParser.DEFAULT_CURRENT_URL, MtPepParliamentParser.DEFAULT_PREVIOUS_URL, MtPepParliamentParser.DEFAULT_SPEAKER_URL, rawDir);

        assertEquals(List.of(MtPepParliamentParser.DEFAULT_CURRENT_URL, MtPepParliamentParser.DEFAULT_PREVIOUS_URL,
                MtPepParliamentParser.DEFAULT_SPEAKER_URL), calls);
    }

    @Test
    void writesOnePersonPerHumanWithAllMandates(@TempDir Path rawDir) throws Exception {
        copy(rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(MtPepParliamentParser.ID_PREFIX, MtPepParliamentParser.TOPIC);

        String message = new MtPepParliamentParser().convert(rawDir, TODAY, 12, dataset);

        assertTrue(message.startsWith("79 députés en fonction, 79 de la législature précédente"), message);
        assertEquals(102, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count(), "102 députés, dont le président de la Chambre (député de la 14e législature)");
        FtmEntity pm = person(dataset, "Robert Abela");
        assertEquals(Set.of("role.pep"), pm.getValues("topics"));
        assertEquals(Set.of("Abela Robert", "Abela, Robert"), pm.getValues("alias"));
        List<FtmEntity> stints = occupancies(dataset, pm);
        assertEquals(3, stints.size(), "15e et 14e législatures, plus le poste de Premier ministre");
        assertTrue(stints.stream().anyMatch(o -> o.getValues("status").contains("ended")
                && o.getValues("description").stream().anyMatch(d -> d.contains("Quatorzième"))));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.head")));
        FtmEntity speaker = person(dataset, "Carmelo Abela");
        assertTrue(speaker.getValues("alias").contains("Abela Carmelo"));
        assertTrue(occupancies(dataset, speaker).stream().anyMatch(o -> o.getValues("startDate").contains("2026-06-20")), "mandat de président de la Chambre");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void dropsThePreviousLegislatureWhenItEndedBeforeTheWindow(@TempDir Path rawDir) throws Exception {
        copy(rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(MtPepParliamentParser.ID_PREFIX, MtPepParliamentParser.TOPIC);

        String message = new MtPepParliamentParser().convert(rawDir, LocalDate.of(2028, 1, 1), 12, dataset);

        assertTrue(message.contains("écartée"), message);
        assertEquals(80, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count(), "79 en fonction + le président");
    }

    @Test
    void failsClearlyWithoutPagesOrWhenTheFormatChanged(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new MtPepParliamentParser().convert(rawDir, TODAY, 12, new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> MtPepParliamentParser.legislature("<html/>"));
        assertThrows(IllegalArgumentException.class, () -> MtPepParliamentParser.monthsAfterEnd(Map.of("monthsAfterEnd", "douze")));
    }

    private static void copy(Path rawDir) throws IOException {
        for (String f : List.of("current.html", "previous.html", "speaker.html")) {
            Files.writeString(rawDir.resolve(f), resource(f), StandardCharsets.UTF_8);
        }
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name)).findFirst().orElseThrow();
    }

    private static List<FtmEntity> occupancies(FtmDatasetBuilder dataset, FtmEntity person) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(person.getId())).toList();
    }

    private static String resource(String name) {
        try (InputStream in = MtPepParliamentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
