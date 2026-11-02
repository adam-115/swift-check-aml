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
}
