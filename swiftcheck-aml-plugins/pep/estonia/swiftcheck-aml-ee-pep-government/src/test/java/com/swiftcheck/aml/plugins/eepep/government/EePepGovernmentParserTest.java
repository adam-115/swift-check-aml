package com.swiftcheck.aml.plugins.eepep.government;

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
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Pages réelles de valitsus.ee (6 novembre 2026) : les liens de la page d'accueil et les fiches du Premier ministre, du ministre
 * de la Finance (naissance et lieu après la date), de la ministre de la Culture et du ministre de l'Énergie et de l'Environnement
 * (titre avec espace insécable, pas de lieu de naissance). Seul le contenu principal de chaque fiche est conservé.
 */
class EePepGovernmentParserTest {

    @Test
    void listsTheMemberPagesOnce() {
        List<String> links = EePepGovernmentParser.links(resource("home.html"));

        assertEquals(13, links.size(), links.toString());
        assertTrue(links.contains("/en/prime-minister-ministers/prime-minister-kristen-michal"));
        assertTrue(links.contains("/en/prime-minister-ministers/minister-finance-jurgen-ligi"));
    }

    @Test
    void readsThePrimeMinister() {
        var m = EePepGovernmentParser.member(resource("pm.html"), "pm");

        assertEquals("Kristen Michal", m.name());
        assertEquals("Prime Minister", m.role());
        assertEquals(LocalDate.of(2024, 7, 23), m.start());
        assertEquals(LocalDate.of(1975, 7, 12), m.birthDate());
        assertEquals("Tallinn", m.birthPlace());
    }

    @Test
    void readsMinistersWithDifferentSentenceShapes() {
        var finance = EePepGovernmentParser.member(resource("finance.html"), "finance");
        assertEquals("Jürgen Ligi", finance.name());
        assertEquals("Minister of Finance", finance.role());
        assertEquals(LocalDate.of(1959, 7, 16), finance.birthDate());
        assertEquals("Tartu", finance.birthPlace());

        var keldo = EePepGovernmentParser.member(resource("keldo.html"), "keldo");
        assertEquals("Minister of Energy and the Environment", keldo.role(), "espace insécable du titre");
        assertEquals(LocalDate.of(2026, 9, 16), keldo.start());
        assertEquals(LocalDate.of(1990, 11, 7), keldo.birthDate());
        assertNull(keldo.birthPlace());

        var herem = EePepGovernmentParser.member(resource("herem.html"), "herem");
        assertEquals("Minister of Defence", herem.role(), "« ... of the Republic of Estonia since ... »");
        assertEquals(LocalDate.of(2026, 9, 16), herem.start());
        assertEquals(LocalDate.of(1973, 12, 17), herem.birthDate());

        var vahtras = EePepGovernmentParser.member(resource("vahtras.html"), "vahtras");
        assertEquals("Kärdla", vahtras.birthPlace());

        var kallas = EePepGovernmentParser.member(resource("kallas.html"), "kallas");
        assertEquals("Minister of Education and Research", kallas.role(), "sans « of the Republic of Estonia »");
        assertEquals(LocalDate.of(2024, 7, 23), kallas.start());
        assertEquals("Kiviõli", kallas.birthPlace(), "lieu avant la date");

        var culture = EePepGovernmentParser.member(resource("culture.html"), "culture");
        assertEquals("Heidy Purga", culture.name());
        assertEquals(LocalDate.of(2023, 4, 17), culture.start());
    }

    @Test
    void downloadsTheHomePageAndEveryMemberPage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        EePepGovernmentParser.download(url -> {
            calls.add(url);
            return url.endsWith("/en") ? resource("home.html") : "<html/>";
        }, "https://valitsus.ee/en", rawDir);

        assertEquals(14, calls.size());
        assertEquals("https://valitsus.ee/en", calls.get(0));
        assertEquals("https://valitsus.ee/en/prime-minister-ministers/prime-minister-kristen-michal", calls.get(1));
        assertTrue(Files.exists(rawDir.resolve("member-13.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("home.html"), resource("home.html"), StandardCharsets.UTF_8);
        String[] pages = {"pm.html", "finance.html", "culture.html", "keldo.html"};
        for (int i = 0; i < pages.length; i++) {
            Files.writeString(rawDir.resolve("member-" + (i + 1) + ".html"), resource(pages[i]), StandardCharsets.UTF_8);
        }
        Files.writeString(rawDir.resolve("member-5.html"), "<html/>", StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EePepGovernmentParser.ID_PREFIX, EePepGovernmentParser.TOPIC);

        String message = new EePepGovernmentParser().convert(rawDir, dataset);

        assertTrue(message.contains("4 membres"), message);
        assertTrue(dataset.entities().stream().anyMatch(e -> "Person".equals(e.getSchema())
                && e.getValues("sourceUrl").contains("https://valitsus.ee/en/prime-minister-ministers/prime-minister-kristen-michal")));
        assertTrue(message.contains("1 fiche(s) illisible(s)"), message);
        Set<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
        assertEquals(Set.of("Kristen Michal", "Jürgen Ligi", "Heidy Purga", "Erkki Keldo"), names);
        FtmEntity michal = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Kristen Michal")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), michal.getValues("topics"));
        assertEquals(Set.of("1975-07-12"), michal.getValues("birthDate"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("topics").contains("gov.head")));
        assertEquals(4, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) throws Exception {
        assertThrows(IllegalStateException.class, () -> new EePepGovernmentParser().convert(rawDir,
                new FtmDatasetBuilder("x", "role.pep")));
        Files.writeString(rawDir.resolve("home.html"), "<html/>", StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("member-1.html"), "<html/>", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new EePepGovernmentParser().convert(rawDir,
                new FtmDatasetBuilder("x", "role.pep")));
        assertNull(EePepGovernmentParser.member("<html/>", "x"));
    }

    private static String resource(String name) {
        try (InputStream in = EePepGovernmentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
