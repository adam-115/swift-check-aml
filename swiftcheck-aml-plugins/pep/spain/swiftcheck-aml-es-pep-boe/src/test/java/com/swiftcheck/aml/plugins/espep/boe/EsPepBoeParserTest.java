package com.swiftcheck.aml.plugins.espep.boe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Sommaires réels du BOE : octobre 2025 (section II.A) et novembre 2023 (nomination du Gouvernement). */
class EsPepBoeParserTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 11, 3);

    private static Path rawDir(Path dir) throws IOException {
        for (String file : List.of("sumario-2025-10.json", "sumario-2023-11.json", "doc-BOE-A-2023-23543.xml")) {
            try (InputStream in = EsPepBoeParserTest.class.getResourceAsStream("/boe/" + file)) {
                Files.copy(in, dir.resolve(file));
            }
        }
        return dir;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains(name)).findFirst()
                .orElseThrow(() -> new AssertionError("Personne absente : " + name));
    }

    private static List<FtmEntity> occupancies(FtmDatasetBuilder dataset, FtmEntity person) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(person.getId())).toList();
    }

    @Test
    void closesAPostWithTheDateOfTheCessation(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepBoeParser.ID_PREFIX, EsPepBoeParser.TOPIC);
        new EsPepBoeParser().convert(rawDir(dir), TODAY, 24, dataset);

        FtmEntity ana = person(dataset, "Ana Esmeralda Martínez Sáez");
        FtmEntity cessation = occupancies(dataset, ana).get(0);
        assertEquals(Set.of("2025-09-30"), cessation.getValues("endDate"));
        assertEquals(Set.of("ended"), cessation.getValues("status"));
        assertTrue(ana.getValues("position").contains("Ancien : Directora General de Diplomacia Económica"));

        FtmEntity jordi = person(dataset, "Jordi Colgan Perera");
        FtmEntity appointment = occupancies(dataset, jordi).get(0);
        assertEquals(Set.of("2025-09-30"), appointment.getValues("startDate"));
        assertEquals(Set.of("current"), appointment.getValues("status"));
        assertEquals(Set.of("https://www.boe.es/diario_boe/txt.php?id=" + appointment.getValues("sourceUrl")
                .iterator().next().substring(appointment.getValues("sourceUrl").iterator().next().indexOf("id=") + 3)),
                appointment.getValues("sourceUrl"));
    }

    @Test
    void keepsTheCessationAndTheNewPostOfTheSamePerson(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepBoeParser.ID_PREFIX, EsPepBoeParser.TOPIC);
        new EsPepBoeParser().convert(rawDir(dir), TODAY, 24, dataset);

        FtmEntity consuelo = person(dataset, "Consuelo Sánchez Naranjo");
        List<FtmEntity> posts = occupancies(dataset, consuelo);
        assertEquals(2, posts.size(), "ancienne sous-secrétaire et nouvelle secrétaire d'État");
        assertEquals(1, posts.stream().filter(o -> o.getValues("status").contains("current")).count());
        assertEquals(1, posts.stream().filter(o -> o.getValues("status").contains("ended")).count());
    }

    @Test
    void readsAmbassadorsAndTagsThemAsDiplomats(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepBoeParser.ID_PREFIX, EsPepBoeParser.TOPIC);
        new EsPepBoeParser().convert(rawDir(dir), TODAY, 24, dataset);

        FtmEntity paloma = person(dataset, "Paloma Serra Robles");
        assertTrue(paloma.getValues("topics").contains("role.diplo"));
        assertTrue(paloma.getValues("topics").contains("role.pep"));
        assertEquals(Set.of("es"), paloma.getValues("country"));
    }

    @Test
    void aMilitaryPromotionReplacesTheRankBefore(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepBoeParser.ID_PREFIX, EsPepBoeParser.TOPIC);
        new EsPepBoeParser().convert(rawDir(dir), TODAY, 24, dataset);

        FtmEntity ramon = person(dataset, "Ramón Armada Vázquez");
        assertTrue(ramon.getValues("position").contains("Teniente General"));
        // les généraux de brigade ne font pas partie des fonctions retenues
        assertFalse(dataset.entities().stream().anyMatch(e -> e.getValues("name").contains("Antonio Julián Ferrera Arriba")));
    }

    @Test
    void doesNotKeepLowerLevelOrOrdinaryAppointments(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepBoeParser.ID_PREFIX, EsPepBoeParser.TOPIC);
        new EsPepBoeParser().convert(rawDir(dir), TODAY, 24, dataset);

        // Doyen de tribunaux, procureur provincial : pas dans la liste (art. 14.4)
        assertFalse(dataset.entities().stream().anyMatch(e -> e.getValues("name").contains("Abel Fernández Domínguez")));
        assertFalse(dataset.entities().stream().anyMatch(e -> e.getValues("name").contains("Eva Regueiro Rodríguez")));
        // le président d'un tribunal supérieur de justice, si
        assertTrue(dataset.entities().stream().anyMatch(e -> e.getValues("name").contains("Juan José Carbonero Redondo")));
    }

    @Test
    void readsTheMinistersOfTheDecreeThatNamesThemAll(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepBoeParser.ID_PREFIX, EsPepBoeParser.TOPIC);
        EsPepBoeParser.Report report = new EsPepBoeParser().convert(rawDir(dir), TODAY, 24, dataset);

        assertEquals(1, report.multiDocuments());
        FtmEntity albares = person(dataset, "José Manuel Albares Bueno");
        FtmEntity post = occupancies(dataset, albares).get(0);
        assertEquals(Set.of("2023-11-20"), post.getValues("startDate"));
        assertTrue(albares.getValues("position").stream().anyMatch(p -> p.startsWith("Ministro de Asuntos Exteriores")));
        assertTrue(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("position").stream().anyMatch(p -> p.startsWith("Ministr"))).count() >= 22);
    }

    @Test
    void dropsThosWhoLeftMoreThanTheRetentionAgo(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepBoeParser.ID_PREFIX, EsPepBoeParser.TOPIC);
        // à fin 2027, la cessation du 30/09/2025 date de plus de 24 mois
        EsPepBoeParser.Report report = new EsPepBoeParser().convert(rawDir(dir), LocalDate.of(2027, 12, 1), 24, dataset);

        assertTrue(report.dropped() > 0);
        assertFalse(dataset.entities().stream().anyMatch(e -> e.getValues("name").contains("Ana Esmeralda Martínez Sáez")));
    }

    @Test
    void failsWithoutAnySummary(@TempDir Path dir) {
        assertThrows(IllegalStateException.class, () -> new EsPepBoeParser().convert(dir, TODAY, 24,
                new FtmDatasetBuilder(EsPepBoeParser.ID_PREFIX, EsPepBoeParser.TOPIC)));
    }

    @Test
    void downloadsEachDayOnceAndRemembersDaysWithoutBoe(@TempDir Path dir) throws Exception {
        List<String> asked = new ArrayList<>();
        BoeApi.Fetcher fetcher = (url, accept) -> {
            asked.add(url.substring(url.lastIndexOf('/') + 1));
            if (url.endsWith("20251004")) {
                return new BoeApi.Response(404, "{}".getBytes(StandardCharsets.UTF_8));
            }
            String json = "{\"data\":{\"sumario\":{\"diario\":[{\"seccion\":[{\"codigo\":\"2A\",\"departamento\":["
                    + "{\"nombre\":\"MINISTERIO X\",\"epigrafe\":[{\"nombre\":\"Ceses\",\"item\":{\"identificador\":\"BOE-A-2025-" + asked.size()
                    + "\",\"titulo\":\"Real Decreto 1/2025, de 1 de octubre, por el que se nombra Director General de Y a don Z.\"}}]}]},"
                    + "{\"codigo\":\"3\",\"departamento\":[]}]}]}}}";
            return new BoeApi.Response(200, json.getBytes(StandardCharsets.UTF_8));
        };

        BoeApi.Download first = BoeApi.download(fetcher, "http://boe/", LocalDate.of(2025, 10, 2), LocalDate.of(2025, 10, 6),
                Set.of("2A"), 0, dir);
        assertEquals(4, first.fetched());
        assertEquals(1, first.noBoe());
        assertTrue(Files.isRegularFile(dir.resolve("sumario-2025-10.json")));

        asked.clear();
        BoeApi.Download second = BoeApi.download(fetcher, "http://boe/", LocalDate.of(2025, 10, 2), LocalDate.of(2025, 10, 6),
                Set.of("2A"), 0, dir);
        // les deux derniers jours sont toujours relus ; le reste vient des fichiers déjà enregistrés
        assertEquals(List.of("20251005", "20251006"), asked);
        assertEquals(3, second.cached());
        assertEquals(1, BoeApi.read(dir).get(LocalDate.of(2025, 10, 2)).size());
        assertEquals("2A", BoeApi.read(dir).get(LocalDate.of(2025, 10, 2)).get(0).section());
    }

    @Test
    void stopsAfterThreeFailuresInARow(@TempDir Path dir) throws Exception {
        List<String> asked = new ArrayList<>();
        BoeApi.Fetcher failing = (url, accept) -> {
            asked.add(url);
            return new BoeApi.Response(503, new byte[0]);
        };
        BoeApi.Download result = BoeApi.download(failing, "http://boe/", LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 20),
                Set.of("2A"), 0, dir);
        assertEquals(3, asked.size());
        assertEquals(3, result.failedDays().size());
    }
}
