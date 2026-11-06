package com.swiftcheck.aml.plugins.plpep.europarl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Réponses réelles de l'API du Parlement européen (6 novembre 2026) : les 53 députés élus en Pologne en fonction
 * (54 dans la législature), dont trois fiches complètes (Adam BIELAN, Daniel OBAJTEK, Marcin KIERWIŃSKI).
 */
class PlPepEuroparlParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final List<String> FILES = List.of("current.json", "term.json", "mep-23788.json", "mep-257058.json", "mep-257042.json");

    @Test
    void keepsEveryCurrentMepAndTheBirthWhenTheRecordIsKnown(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        PlPepEuroparlParser.Report report = new PlPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        assertEquals(53, report.current());
        assertEquals(51, report.withoutDetail().size(), "les autres fiches ne sont pas dans les fixtures");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());

        FtmEntity first = entity(dataset, dataset.buildEntityId("23788"));
        assertEquals(Set.of("Adam BIELAN"), first.getValues("name"));
        assertEquals(Set.of("1974-09-12"), first.getValues("birthDate"));
        assertEquals(Set.of("pl"), first.getValues("country"));
        assertEquals(Set.of("role.pep"), first.getValues("topics"));
        assertEquals(1, occupancies(dataset, first, "current").size(), "un seul mandat en cours");
    }

    @Test
    void aLongerRetentionKeepsThePreviousTermsToo(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder standard = dataset();
        new PlPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), standard);
        FtmDatasetBuilder wide = dataset();
        new PlPepEuroparlParser().convert(rawDir, TODAY, 600, List.of(), wide);

        FtmEntity first = entity(wide, wide.buildEntityId("23788"));
        long recent = standard.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(first.getId())).count();
        long all = wide.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(first.getId())).count();
        assertEquals(1, recent, "seul le mandat en cours est dans la rétention de 12 mois");
        assertTrue(all >= recent, "une rétention longue garde au moins les mêmes mandats");
    }

    @Test
    void downloadsBothListsThenEachDetail(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        List<String> failed = EuroparlApi.download(url -> {
            calls.add(url);
            if (url.equals("detail/257058")) {
                throw new IOException("HTTP 500");
            }
            return bytes(url.startsWith("current") ? "current.json" : url.startsWith("term") ? "term.json" : "mep-23788.json");
        }, "current", "term", "detail/{id}", 0, rawDir);

        assertEquals("current", calls.get(0));
        assertEquals("term", calls.get(1));
        assertEquals(2 + 54, calls.size(), "les deux listes, puis une fiche par député");
        assertEquals(List.of("detail/257058"), failed);
    }

    @Test
    void requiresTheUrls(@TempDir Path rawDir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new PlPepEuroparlParser()
                .parse(new ParserContext("pl_pep_europarl", null, rawDir, rawDir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("currentUrl"));
    }

    private static List<FtmEntity> occupancies(FtmDatasetBuilder dataset, FtmEntity person, String status) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(person.getId()) && e.getValues("status").contains(status)).toList();
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(PlPepEuroparlParser.ID_PREFIX, PlPepEuroparlParser.TOPIC);
    }

    private static FtmEntity entity(FtmDatasetBuilder dataset, String id) {
        return dataset.entities().stream().filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }

    private static void copy(Path rawDir, List<String> files) throws IOException {
        for (String f : files) {
            Files.write(rawDir.resolve(f), bytes(f));
        }
    }

    private static byte[] bytes(String file) throws IOException {
        try (InputStream in = PlPepEuroparlParserTest.class.getResourceAsStream("/europarl/" + file)) {
            if (in == null) {
                throw new IOException("404 " + file);
            }
            return in.readAllBytes();
        }
    }
}
