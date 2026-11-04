package com.swiftcheck.aml.plugins.bepep.walloonparliament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Fichier réel de l'open data du Parlement de Wallonie (02/11/2026) : les 75 députés en fonction. */
class BePepWalloonParliamentParserTest {

    @Test
    void readsTheDeputiesAndTheBureau(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();

        BePepWalloonParliamentParser.Report report = new BePepWalloonParliamentParser().convert(rawDir, dataset);

        assertEquals(75, report.members());
        assertTrue(report.bureau() > 0);
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity borsus = person(dataset, "1072");
        assertEquals(Set.of("Willy BORSUS"), borsus.getValues("name"));
        assertEquals(Set.of("male"), borsus.getValues("gender"));
        assertEquals(Set.of(BePepWalloonParliamentParser.POSITION, "Président du Parlement de Wallonie"),
                borsus.getValues("position"));
        assertEquals(2, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(borsus.getId())).count(), "membre et président");
        assertEquals(75, dataset.media().size(), "un portrait officiel par député");
    }

    @Test
    void addressPhoneEmailAndSocialNetworksAreNeverKept(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();
        new BePepWalloonParliamentParser().convert(rawDir, dataset);

        String all = dataset.entities().stream().map(e -> e.getId() + e.getValues("address") + e.getValues("email")
                + e.getValues("phone") + e.getValues("website")).reduce("", String::concat);
        assertFalse(all.contains("MARCHE-EN-FAMENNE") || all.contains("@parlement-wallonie.be")
                || all.contains("facebook"), "coordonnées personnelles du fichier");
        for (FtmEntity e : dataset.entities()) {
            assertTrue(e.getValues("address").isEmpty() && e.getValues("email").isEmpty()
                    && e.getValues("phone").isEmpty(), e.getId());
        }
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String id) {
        String entityId = dataset.buildEntityId(id);
        return dataset.entities().stream().filter(e -> e.getId().equals(entityId)).findFirst().orElseThrow();
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(BePepWalloonParliamentParser.ID_PREFIX, BePepWalloonParliamentParser.TOPIC);
    }

    private static void copyFixture(Path rawDir) throws IOException {
        try (InputStream in = BePepWalloonParliamentParserTest.class.getResourceAsStream("/" + BePepWalloonParliamentParser.FILE)) {
            Files.write(rawDir.resolve(BePepWalloonParliamentParser.FILE), in.readAllBytes());
        }
    }

    @Test
    void keepsAFormerDeputyWhoseMandateEndedDuringTheRetention(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        copyFormer(rawDir);
        FtmDatasetBuilder dataset = dataset();

        BePepWalloonParliamentParser.Report report = new BePepWalloonParliamentParser().convert(rawDir, dataset,
                java.time.LocalDate.of(2026, 4, 1), 12);

        assertEquals(1, report.former());
        FtmEntity baurain = person(dataset, "2690");
        assertEquals(Set.of("Pascal BAURAIN"), baurain.getValues("name"));
        FtmEntity occupancy = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(baurain.getId())).findFirst().orElseThrow();
        assertEquals(Set.of("ended"), occupancy.getValues("status"));
        assertEquals(Set.of("2024-06-25"), occupancy.getValues("startDate"));
        assertEquals(Set.of("2025-09-03"), occupancy.getValues("endDate"));
        assertEquals(Set.of("Les Engagés"), occupancy.getValues("politicalGroup"));
    }

    @Test
    void dropsAFormerDeputyOnceTheRetentionIsOver(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        copyFormer(rawDir);

        BePepWalloonParliamentParser.Report report = new BePepWalloonParliamentParser().convert(rawDir, dataset(),
                java.time.LocalDate.of(2026, 11, 4), 12);

        assertEquals(0, report.former());
    }

    @Test
    void onlyOpensTheFormerDeputiesWhoseMandateMayStillBeInRetention() throws Exception {
        java.util.List<WalloonFormer.Entry> entries = new java.util.ArrayList<>();
        for (int page = 1; page <= 9; page++) {
            entries.addAll(WalloonFormer.entries(resource(BePepWalloonParliamentParser.FORMER_PREFIX + page + ".html")));
        }
        assertEquals(517, entries.size());
        var candidates = WalloonFormer.candidates(entries, java.time.LocalDate.of(2025, 11, 4));
        assertEquals(12, candidates.size(), "les anciens de la législature 2024-2029");
        assertTrue(candidates.stream().anyMatch(c -> c.fullName().equals("BAURAIN Pascal")));
        
        
        assertEquals("ALBERT", entries.stream().filter(e -> e.id().equals("813")).findFirst().orElseThrow().lastName());
        assertEquals("Victor dit Vicky", entries.stream().filter(e -> e.id().equals("813")).findFirst().orElseThrow().firstName());
    }

    @Test
    void downloadsEveryPageOfTheListThenOnlyTheCandidatesRecords(@TempDir Path rawDir) throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        BePepWalloonParliamentParser.downloadFormer(url -> {
            calls.add(url);
            if (url.contains("composition_dep_det")) {
                return resource(BePepWalloonParliamentParser.FORMER_DETAIL_PREFIX + "2690.html");
            }
            int page = url.contains("pg-nb=") ? Integer.parseInt(url.substring(url.indexOf("pg-nb=") + 6)) : 1;
            return page <= 9 ? resource(BePepWalloonParliamentParser.FORMER_PREFIX + page + ".html") : "<html></html>";
        }, "list?p=composition_mandataires", "composition_dep_det&id={id}", java.time.LocalDate.of(2025, 11, 4), 0, rawDir);

        assertEquals(22, calls.size(), "10 pages de liste (la dixième est vide) et 12 fiches");
        assertTrue(calls.contains("composition_dep_det&id=2690"));
        assertTrue(Files.exists(rawDir.resolve(BePepWalloonParliamentParser.FORMER_DETAIL_PREFIX + "2690.html")));
    }

    private static String resource(String file) throws IOException {
        try (InputStream in = BePepWalloonParliamentParserTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void copyFormer(Path rawDir) throws IOException {
        java.util.List<String> files = new java.util.ArrayList<>();
        for (int page = 1; page <= 9; page++) {
            files.add(BePepWalloonParliamentParser.FORMER_PREFIX + page + ".html");
        }
        files.add(BePepWalloonParliamentParser.FORMER_DETAIL_PREFIX + "2690.html");
        for (String file : files) {
            Files.writeString(rawDir.resolve(file), resource(file));
        }
    }

    @Test
    void readsTheFeminineFormOfTheMandateLine() {
        var entry = new WalloonFormer.Entry("3680", "DALCQ Anne-Catherine", "Anne-Catherine", "DALCQ", null, null, 2029);
        var mandate = WalloonFormer.mandate(entry,
                "<p>Députée wallonne : du 25/06/2024 au 15/07/2024</p><p>Communauté française Députée : du 02/07/2024 au 15/07/2024</p>");
        assertEquals(java.time.LocalDate.of(2024, 7, 15), mandate.end());
        assertEquals(java.time.LocalDate.of(2024, 6, 25), mandate.start());
    }
}
