package com.swiftcheck.aml.plugins.bepep.chamber;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
 * Pages réelles de dekamer.be (02/11/2026) : la liste des 150 députés et quatre fiches, Staf Aerts, Irina De Knop
 * (nom composé, née), Michel De Maegd et Denis Ducarme. Les autres députés n'ont pas de fiche dans l'extrait.
 */
class BePepChamberParserTest {

    private static final List<String> DETAILS = List.of("07757", "06246", "07122", "01056");

    @Test
    void readsTheListAndTheBirthOfEachMemberFromHisPage(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();

        BePepChamberParser.Report report = new BePepChamberParser().convert(rawDir, dataset);

        assertEquals(150, report.members());
        assertEquals(4, report.withBirthDate());
        assertEquals(146, report.withoutDetail().size());
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity aerts = person(dataset, "07757");
        assertEquals(Set.of("Staf Aerts"), aerts.getValues("name"));
        assertEquals(Set.of("1981-09-08"), aerts.getValues("birthDate"));
        assertEquals(Set.of("Duffel"), aerts.getValues("birthPlace"));
        assertEquals(Set.of("male"), aerts.getValues("gender"));
        assertEquals(Set.of("role.pep"), aerts.getValues("topics"));
        FtmEntity mandate = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(aerts.getId())).findFirst().orElseThrow();
        assertEquals(Set.of("2024-06-09"), mandate.getValues("startDate"));
        assertEquals(Set.of("Anvers"), mandate.getValues("constituency"));
        assertEquals(Set.of("Ecolo-Groen"), mandate.getValues("politicalGroup"));

        FtmEntity deKnop = person(dataset, "06246");
        assertEquals(Set.of("Irina"), deKnop.getValues("firstName"));
        assertEquals(Set.of("De Knop"), deKnop.getValues("lastName"), "nom composé : coupure retrouvée grâce à la fiche");
        assertEquals(Set.of("female"), deKnop.getValues("gender"));
        assertEquals(Set.of("1978-07-06"), deKnop.getValues("birthDate"));
    }

    /** Fiches réelles d'un suppléant (« Né le 16/05/1983 », sans lieu) et d'une députée (« Née à Vilvorde », sans date). */
    @Test
    void readsIncompleteBirthLinesAndTheStartOfASubstitute() throws Exception {
        BePepChamberParser.Detail diNunzio = BePepChamberParser.detail(
                new String(bytes("chambre-depute-07901.html"), BePepChamberParser.LATIN1));
        assertEquals("1983-05-16", diNunzio.birthDate());
        assertEquals(null, diNunzio.birthPlace());
        assertEquals("Flandre orientale", diNunzio.constituency());
        assertEquals("2025-10-23", diNunzio.termStart(), "suppléant entré en cours de législature");

        BePepChamberParser.Detail ramlot = BePepChamberParser.detail(
                new String(bytes("chambre-depute-07288.html"), BePepChamberParser.LATIN1));
        assertEquals(null, ramlot.birthDate());
        assertEquals("Vilvorde", ramlot.birthPlace());
        assertEquals("female", ramlot.gender());
        assertEquals("Luxembourg", ramlot.constituency());
    }

    @Test
    void contactDetailsOfThePagesAreNeverKept(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();
        new BePepChamberParser().convert(rawDir, dataset);

        for (FtmEntity e : dataset.entities()) {
            assertTrue(e.getValues("email").isEmpty() && e.getValues("address").isEmpty(), e.getId());
        }
    }

    @Test
    void namesAreSplitWithThePageOrOnTheFirstWord() {
        assertArrayEquals(new String[] { "Vincent", "Van Peteghem" },
                BePepChamberParser.split("Van Peteghem Vincent", "Vincent Van Peteghem"));
        assertArrayEquals(new String[] { "Staf", "Aerts" }, BePepChamberParser.split("Aerts Staf", null));
    }

    @Test
    void downloadsTheListThenEachPage(@TempDir Path rawDir) throws Exception {
        byte[] list = bytes("chambre-liste.html");
        List<String> calls = new ArrayList<>();
        BePepChamberParser.download(url -> {
            calls.add(url);
            if (url.equals("list")) {
                return list;
            }
            throw new IOException("HTTP 404");             // fiche absente : le député reste publié
        }, "list", "detail/{key}", 0, rawDir);

        assertEquals(151, calls.size());
        assertEquals("detail/07757", calls.stream().filter(c -> c.endsWith("07757")).findFirst().orElseThrow());
        assertTrue(Files.exists(rawDir.resolve(BePepChamberParser.LIST_FILE)));
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String key) {
        String id = dataset.buildEntityId(key);
        return dataset.entities().stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow();
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(BePepChamberParser.ID_PREFIX, BePepChamberParser.TOPIC);
    }

    private static void copyFixture(Path rawDir) throws IOException {
        Files.write(rawDir.resolve(BePepChamberParser.LIST_FILE), bytes("chambre-liste.html"));
        for (String key : DETAILS) {
            String file = BePepChamberParser.DETAIL_PREFIX + key + ".html";
            Files.write(rawDir.resolve(file), bytes(file));
        }
    }

    private static byte[] bytes(String file) throws IOException {
        try (InputStream in = BePepChamberParserTest.class.getResourceAsStream("/" + file)) {
            return in.readAllBytes();
        }
    }
}
