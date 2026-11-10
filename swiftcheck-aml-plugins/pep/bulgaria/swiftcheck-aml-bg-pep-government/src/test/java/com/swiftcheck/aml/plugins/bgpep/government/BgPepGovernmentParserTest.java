package com.swiftcheck.aml.plugins.bgpep.government;

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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Pages réelles de www.government.bg (8 novembre 2026), scripts retirés : la liste « Състав на правителството / Биографии » (20 membres)
 * et la fiche du Premier ministre Румен Радев, avec sa naissance.
 */
class BgPepGovernmentParserTest {

    @Test
    void readsEveryMemberOfTheBiographiesPage() {
        var members = BgPepGovernmentParser.members(resource("members.html"));

        assertEquals(20, members.size(), "un lien-image sans texte par membre est ignoré");
        assertEquals("Гълъб Донев", members.get(0).name());
        assertEquals("заместник министър-председател и министър на финансите", members.get(0).role());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Велислава Петрова-Чамова") && m.role().equals("министър на външните работи")),
                "tiret demi-cadratin d'un nom composé ramené à un trait d'union");
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Георги Вълчев") && m.role().equals("министър на образованието и науката")),
                "titre « Проф. » retiré");
        assertTrue(members.stream().noneMatch(m -> m.role().isBlank()));
    }

    @Test
    void readsThePrimeMinisterWithHisBirth() {
        var pm = BgPepGovernmentParser.primeMinister(resource("pm.html"));

        assertEquals("Румен Радев", pm.name());
        assertEquals("министър-председател", pm.role());
        assertEquals(LocalDate.of(1963, 6, 18), pm.birthDate());
        assertEquals("Димитровград", pm.birthPlace());
    }

    @Test
    void addsReverseOrderAndOfficialLatinTransliteration() {
        assertEquals(Set.of("Радев Румен", "Rumen Radev", "Radev Rumen"), BgPepGovernmentParser.aliases("Румен Радев"));
        assertTrue(BgPepGovernmentParser.aliases("Велислава Петрова-Чамова").contains("Velislava Petrova-Chamova"));
        assertEquals("Galab Donev", BgPepGovernmentParser.transliterate("Гълъб Донев"));
    }

    @Test
    void downloadsBothPages(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        BgPepGovernmentParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, BgPepGovernmentParser.DEFAULT_MEMBERS_URL, BgPepGovernmentParser.DEFAULT_PM_URL, rawDir);

        assertEquals(List.of(BgPepGovernmentParser.DEFAULT_MEMBERS_URL, BgPepGovernmentParser.DEFAULT_PM_URL), calls);
        assertTrue(Files.exists(rawDir.resolve("members.html")) && Files.exists(rawDir.resolve("pm.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("members.html"), resource("members.html"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("pm.html"), resource("pm.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BgPepGovernmentParser.ID_PREFIX, BgPepGovernmentParser.TOPIC);

        String message = new BgPepGovernmentParser().convert(rawDir, BgPepGovernmentParser.DEFAULT_MEMBERS_URL, dataset);

        assertTrue(message.startsWith("21 membres"), message);
        assertEquals(21, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        FtmEntity radev = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Румен Радев")).findFirst().orElseThrow();
        assertEquals(Set.of("1963-06-18"), radev.getValues("birthDate"));
        assertEquals(Set.of("role.pep"), radev.getValues("topics"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.head")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePagesChangedOrAreMissing(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new BgPepGovernmentParser().convert(rawDir, "x", new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> BgPepGovernmentParser.members("<html/>"));
        assertThrows(IllegalStateException.class, () -> BgPepGovernmentParser.primeMinister("<html><h1>Autre</h1></html>"));
    }

    private static String resource(String name) {
        try (InputStream in = BgPepGovernmentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
