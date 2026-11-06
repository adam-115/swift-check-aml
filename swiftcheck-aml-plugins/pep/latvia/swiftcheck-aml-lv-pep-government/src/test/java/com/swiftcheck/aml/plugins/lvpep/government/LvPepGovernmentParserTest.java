package com.swiftcheck.aml.plugins.lvpep.government;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/**
 * Pages réelles de mk.gov.lv (6 novembre 2026) : les liens de la page « Ministru kabineta sastāvs » et, pour trois membres,
 * le titre de la page (nom) et le titre de niveau 1 (fonction). Le reste de chaque fiche est retiré.
 */
class LvPepGovernmentParserTest {

    @Test
    void listsTheFourteenMembersOnce() {
        var listed = LvPepGovernmentParser.listed(resource("composition.html"));

        assertEquals(14, listed.size(), listed.toString());
        assertEquals("/lv/darbinieks/andris-kulbergs", listed.get(0).path());
        assertEquals("Ministru prezidents", listed.get(0).role());
        assertTrue(listed.stream().anyMatch(l -> l.role().equals("Ārlietu ministre")));
    }

    @Test
    void readsNameFromThePageTitleAndRoleFromTheHeading() {
        var listed = LvPepGovernmentParser.listed(resource("composition.html"));
        var braze = LvPepGovernmentParser.member(resource("braze.html"),
                listed.stream().filter(l -> l.path().endsWith("baiba-braze")).findFirst().orElseThrow());

        assertEquals("Baiba Braže", braze.name(), "diacritiques conservés");
        assertEquals("Ārlietu ministre", braze.role());
        assertEquals("https://www.mk.gov.lv/lv/darbinieks/baiba-braze", braze.url());
    }

    @Test
    void downloadsTheCompositionAndEveryMemberPage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        LvPepGovernmentParser.download(url -> {
            calls.add(url);
            return url.endsWith("sastavs") ? resource("composition.html") : "<html/>";
        }, "https://www.mk.gov.lv/lv/ministru-kabineta-sastavs", rawDir);

        assertEquals(15, calls.size());
        assertEquals("https://www.mk.gov.lv/lv/darbinieks/andris-kulbergs", calls.get(1));
        assertTrue(Files.exists(rawDir.resolve("member-14.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("composition.html"), resource("composition.html"), StandardCharsets.UTF_8);
        // 1 = premier ministre, 2 = ministre de la Défense, 4 = ministre des Affaires étrangères (ordre de la page)
        var listed = LvPepGovernmentParser.listed(resource("composition.html"));
        int pm = index(listed, "andris-kulbergs");
        int defence = index(listed, "raivis-melnis");
        int foreign = index(listed, "baiba-braze");
        Files.writeString(rawDir.resolve("member-" + pm + ".html"), resource("kulbergs.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("member-" + defence + ".html"), resource("melnis.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("member-" + foreign + ".html"), resource("braze.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("member-99.html"), "<html/>", StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LvPepGovernmentParser.ID_PREFIX, LvPepGovernmentParser.TOPIC);

        String message = new LvPepGovernmentParser().convert(rawDir, dataset);

        assertTrue(message.startsWith("3 membres"), message);
        assertTrue(message.contains("1 fiche(s) illisible(s)"), message);
        Set<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
        assertEquals(Set.of("Andris Kulbergs", "Raivis Melnis", "Baiba Braže"), names);
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("topics").contains("gov.head")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) throws Exception {
        assertThrows(IllegalStateException.class, () -> new LvPepGovernmentParser().convert(rawDir,
                new FtmDatasetBuilder("x", "role.pep")));
        Files.writeString(rawDir.resolve("composition.html"), "<html/>", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new LvPepGovernmentParser().convert(rawDir,
                new FtmDatasetBuilder("x", "role.pep")));
        assertNull(LvPepGovernmentParser.member("<html/>", new LvPepGovernmentParser.Listed("/x", "y")));
    }

    private static int index(List<LvPepGovernmentParser.Listed> listed, String slug) {
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).path().endsWith(slug)) {
                return i + 1;
            }
        }
        throw new IllegalStateException(slug);
    }

    private static String resource(String name) {
        try (InputStream in = LvPepGovernmentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
