package com.swiftcheck.aml.plugins.nopep.parliament;

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
 * Extraits réels de l'API data.stortinget.no (6 novembre 2026) : trois représentants en fonction dont un suppléant en
 * exercice, un représentant sorti en cours de législature, deux anciens de 2021-2025 et deux de 2017-2021 dont un décédé.
 */
class NoPepParliamentParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final String API = "https://data.stortinget.no/eksport";

    @Test
    void keepsCurrentRepresentantsAndThoseWhoLeftTheCurrentPeriod(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 12);

        NoPepParliamentParser.Report report = report(rawDir, 12);
        assertEquals(3, report.current());
        assertEquals(1, report.former(), "un représentant de la législature en cours n'est plus en fonction");
        assertEquals(1, report.periods(), "2021-2025 s'est terminée avant la fenêtre de 12 mois");
        FtmEntity nilsen = person(dataset, "Nilsen");
        assertEquals(Set.of("role.pep"), nilsen.getValues("topics"));
        assertEquals(Set.of("male"), nilsen.getValues("gender"));
        assertEquals(1, nilsen.getValues("birthDate").size());
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("description").stream().anyMatch(d -> d.contains("Suppléant en exercice"))));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("description").stream().anyMatch(d -> d.contains("date de départ inconnue"))));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void aLongerRetentionAddsTheFormerPeriodsAndSkipsTheDeceased(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 100);

        NoPepParliamentParser.Report report = report(rawDir, 100);
        assertEquals(3, report.current());
        assertTrue(report.former() >= 4, "BJRSKJ + 2 de 2021-2025 + 1 de 2017-2021 : " + report.former());
        assertTrue(report.dead() >= 1, "BRA décédé écarté");
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema()) && e.getValues("endDate").contains("2025-09-30")
                && e.getValues("status").contains("ended")), "fin de mandat = fin de la législature 2021-2025");
        assertTrue(dataset.entities().stream().noneMatch(e -> "Person".equals(e.getSchema()) && e.getValues("name").stream()
                .anyMatch(n -> n.contains("Bratteli"))), "décédé non publié");
    }

    @Test
    void aRepresentantOfTwoPeriodsHasTwoMandatesButOnePerson(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 24);

        FtmEntity nilsen = person(dataset, "Nilsen");
        long mandates = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(nilsen.getId())).count();
        assertEquals(2, mandates, "en fonction + législature 2021-2025");
        assertEquals(1, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").stream().anyMatch(n -> n.contains("Nilsen"))).count());
    }

    @Test
    void downloadsOnlyThePeriodsInsideTheRetentionWindow(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        NoPepParliamentParser.download(fetcher(calls), API, TODAY, 12, rawDir);

        assertEquals(List.of(API + "/dagensrepresentanter", API + "/stortingsperioder",
                API + "/representanter?stortingsperiodeid=2025-2029"), calls);

        List<String> more = new ArrayList<>();
        NoPepParliamentParser.download(fetcher(more), API, TODAY, 24, rawDir);
        assertTrue(more.contains(API + "/representanter?stortingsperiodeid=2021-2025"));
        assertTrue(more.stream().noneMatch(c -> c.contains("2017-2021")));
    }

    @Test
    void failsClearlyWithoutCurrentRepresentants(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve(NoPepParliamentParser.CURRENT_FILE), "<dagensrepresentanter_oversikt/>");
        Files.writeString(rawDir.resolve(NoPepParliamentParser.PERIODS_FILE), resource("perioder.xml"));
        assertThrows(IllegalStateException.class, () -> new NoPepParliamentParser().convert(rawDir, TODAY, 12,
                new FtmDatasetBuilder(NoPepParliamentParser.ID_PREFIX)));
    }

    private static FtmDatasetBuilder run(Path rawDir, int months) throws Exception {
        NoPepParliamentParser.download(fetcher(new ArrayList<>()), API, TODAY, 1200, rawDir);   // tous les fichiers de la fixture
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(NoPepParliamentParser.ID_PREFIX, NoPepParliamentParser.TOPIC);
        new NoPepParliamentParser().convert(rawDir, TODAY, months, dataset);
        return dataset;
    }

    private static NoPepParliamentParser.Report report(Path rawDir, int months) throws Exception {
        return new NoPepParliamentParser().convert(rawDir, TODAY, months,
                new FtmDatasetBuilder(NoPepParliamentParser.ID_PREFIX, NoPepParliamentParser.TOPIC));
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String lastNamePart) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").stream().anyMatch(n -> n.contains(lastNamePart))).findFirst()
                .orElseThrow(() -> new AssertionError("Personne absente : " + lastNamePart));
    }

    private static NoPepParliamentParser.Fetcher fetcher(List<String> calls) {
        return url -> {
            calls.add(url);
            if (url.endsWith("/dagensrepresentanter")) {
                return resource("dagens.xml");
            }
            if (url.endsWith("/stortingsperioder")) {
                return resource("perioder.xml");
            }
            return resource("representanter-" + url.substring(url.indexOf("id=") + 3) + ".xml");
        };
    }

    private static String resource(String name) {
        try (InputStream in = NoPepParliamentParserTest.class.getResourceAsStream("/parliament/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
