package com.swiftcheck.aml.plugins.cypep.parliament;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Page réelle « Members of the House / Biographical notes » de www.parliament.cy (8 novembre 2026), scripts retirés : les 56 députés
 * de la 13<sup>e</sup> législature, écrits « NOM Prénom ».
 */
class CyPepParliamentParserTest {

    @Test
    void readsTheFiftySixMembersAndPutsTheGivenNameFirst() {
        var members = Fixture.members();

        assertEquals(56, members.size());
        assertEquals("Annita Demetriou", members.get(0).name());
        assertEquals("DEMETRIOU Annita", members.get(0).published());
        assertEquals("demetriou-annita", members.get(0).slug());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("George P. Pamboridis")), "initiale gardée avec le prénom");
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Linos Ioannis Hadjigeorgiou")), "deux prénoms");
        assertTrue(members.stream().noneMatch(m -> m.name().equals(m.name().toUpperCase())));
    }

    @Test
    void addsTheSurnameFirstOrderAsAlias() {
        var members = Fixture.members();
        var first = members.get(0);
        assertEquals(Set.of("Demetriou Annita"), CyPepParliamentParser.aliases(first));
    }

    @Test
    void stopsWhenTheCloudflareCheckReplacesThePage() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CyPepParliamentParser.members(
                "<html><title>Βουλή των Αντιπροσώπων - Έλεγχος Ασφαλείας</title>Protected by Cloudflare</html>"));
        assertTrue(e.getMessage().contains("Cloudflare"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> CyPepParliamentParser.members("<html/>"));
    }

    @Test
    void downloadsTheListWithASingleRequest(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        CyPepParliamentParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, CyPepParliamentParser.DEFAULT_URL, rawDir);

        assertEquals(List.of(CyPepParliamentParser.DEFAULT_URL), calls);
        assertTrue(Files.exists(rawDir.resolve("members.html")));
    }

    @Test
    void writesPersonsPositionAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("members.html"), Fixture.html(), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(CyPepParliamentParser.ID_PREFIX, CyPepParliamentParser.TOPIC);

        String message = new CyPepParliamentParser().convert(rawDir, CyPepParliamentParser.DEFAULT_URL, dataset);

        assertTrue(message.startsWith("56 députés"), message);
        assertEquals(56, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        FtmEntity annita = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Annita Demetriou")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), annita.getValues("topics"));
        assertEquals(Set.of("Demetriou Annita"), annita.getValues("alias"));
        assertEquals(56, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWithoutTheDownloadedPage(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new CyPepParliamentParser().convert(rawDir, "x", new FtmDatasetBuilder("x", "role.pep")));
    }

    /** Accès à la fixture. */
    private static final class Fixture {
        static String html() {
            try (InputStream in = CyPepParliamentParserTest.class.getResourceAsStream("/members.html")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static List<CyPepParliamentParser.Member> members() {
            return CyPepParliamentParser.members(html());
        }
    }
}
