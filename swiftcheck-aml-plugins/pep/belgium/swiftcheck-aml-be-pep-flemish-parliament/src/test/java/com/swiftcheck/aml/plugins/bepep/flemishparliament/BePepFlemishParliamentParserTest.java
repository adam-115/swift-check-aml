package com.swiftcheck.aml.plugins.bepep.flemishparliament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Réponses réelles de l'API du Parlement flamand (02/11/2026) : la liste des 124 membres et deux fiches, Hannes Anaf
 * et Roosmarijn Beckers. Les autres membres n'ont pas de fiche dans l'extrait.
 */
class BePepFlemishParliamentParserTest {

    @Test
    void readsTheMembersAndTheirBirthFromTheirRecord(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();

        BePepFlemishParliamentParser.Report report = new BePepFlemishParliamentParser().convert(rawDir, dataset);

        assertEquals(124, report.members());
        assertEquals(2, report.withBirthDate());
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity anaf = person(dataset, "4383");
        assertEquals(Set.of("Hannes Anaf"), anaf.getValues("name"));
        assertEquals(Set.of("1985-03-04"), anaf.getValues("birthDate"));
        assertEquals(Set.of("Turnhout"), anaf.getValues("birthPlace"));
        assertEquals(Set.of("male"), anaf.getValues("gender"));
        FtmEntity mandate = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(anaf.getId())).findFirst().orElseThrow();
        assertEquals(Set.of("2024-06-09"), mandate.getValues("startDate"), "mandat en cours, pas celui de 2019");
        assertEquals(Set.of("Antwerpen"), mandate.getValues("constituency"));
        assertEquals(Set.of("Vooruit"), mandate.getValues("politicalGroup"));

        assertEquals(Set.of("female"), person(dataset, "4384").getValues("gender"));
        assertEquals(124, dataset.media().size(), "un portrait officiel par membre");
        assertEquals(BePepFlemishParliamentParser.PHOTO_LICENCE, dataset.media().get(0).licence());
    }

    @Test
    void contactDetailsAreNeverKept(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();
        new BePepFlemishParliamentParser().convert(rawDir, dataset);

        for (FtmEntity e : dataset.entities()) {
            assertTrue(e.getValues("email").isEmpty() && e.getValues("address").isEmpty()
                    && e.getValues("website").isEmpty(), e.getId());
        }
    }

    @Test
    void downloadsTheListThenEachRecord(@TempDir Path rawDir) throws Exception {
        byte[] list = bytes(BePepFlemishParliamentParser.LIST_FILE);
        List<String> calls = new ArrayList<>();
        BePepFlemishParliamentParser.download(url -> {
            calls.add(url);
            if (url.equals("list")) {
                return list;
            }
            throw new IOException("HTTP 500");
        }, "list", "vv/{id}", 0, rawDir);

        assertEquals(125, calls.size());
        assertTrue(calls.contains("vv/4383"));
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String id) {
        String entityId = dataset.buildEntityId(id);
        return dataset.entities().stream().filter(e -> e.getId().equals(entityId)).findFirst().orElseThrow();
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(BePepFlemishParliamentParser.ID_PREFIX, BePepFlemishParliamentParser.TOPIC);
    }

    private static void copyFixture(Path rawDir) throws IOException {
        for (String file : List.of(BePepFlemishParliamentParser.LIST_FILE, "vlaams-parlement-lid-4383.json",
                "vlaams-parlement-lid-4384.json")) {
            Files.write(rawDir.resolve(file), bytes(file));
        }
    }

    private static byte[] bytes(String file) throws IOException {
        try (InputStream in = BePepFlemishParliamentParserTest.class.getResourceAsStream("/" + file)) {
            return in.readAllBytes();
        }
    }

    @Test
    void keepsAFormerMemberWhoseMandateEndedDuringTheRetention(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        for (String file : List.of(BePepFlemishParliamentParser.FORMER_LIST_FILE, "vlaams-parlement-lid-4381.json")) {
            Files.write(rawDir.resolve(file), bytes(file));
        }
        FtmDatasetBuilder dataset = dataset();
        BePepFlemishParliamentParser.Report report = new BePepFlemishParliamentParser().convert(rawDir, dataset,
                java.time.LocalDate.of(2025, 3, 1), 12);

        assertEquals(1, report.former());
        FtmEntity almaci = person(dataset, "4381");
        assertEquals("Meyrem Almaci", almaci.getValues("name").iterator().next());
        FtmEntity occupancy = dataset.entities().stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(almaci.getId()))
                .findFirst().orElseThrow();
        assertEquals(Set.of("ended"), Set.copyOf(occupancy.getValues("status")));
        assertEquals(Set.of("2024-06-08"), Set.copyOf(occupancy.getValues("endDate")));
    }

    @Test
    void dropsAFormerMemberOnceTheRetentionIsOver(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        for (String file : List.of(BePepFlemishParliamentParser.FORMER_LIST_FILE, "vlaams-parlement-lid-4381.json")) {
            Files.write(rawDir.resolve(file), bytes(file));
        }
        FtmDatasetBuilder dataset = dataset();
        BePepFlemishParliamentParser.Report report = new BePepFlemishParliamentParser().convert(rawDir, dataset,
                java.time.LocalDate.of(2026, 11, 4), 12);

        assertEquals(0, report.former());
    }
}
