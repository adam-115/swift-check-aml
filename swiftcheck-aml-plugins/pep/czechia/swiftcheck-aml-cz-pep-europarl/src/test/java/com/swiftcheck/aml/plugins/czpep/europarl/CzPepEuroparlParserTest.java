package com.swiftcheck.aml.plugins.czpep.europarl;

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
 * Réponses réelles de l'API du Parlement européen (6 novembre 2026) : les 21 députés élus en Tchéquie en fonction
 * (24 dans la législature), dont trois fiches complètes (Kateřina KONEČNÁ, Jaroslav KNOT, Ondřej KOVAŘÍK).
 */
class CzPepEuroparlParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final List<String> FILES = List.of("current.json", "term.json", "mep-23699.json", "mep-270261.json", "mep-118949.json");

    @Test
    void keepsEveryCurrentMepAndTheBirthWhenTheRecordIsKnown(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        CzPepEuroparlParser.Report report = new CzPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        assertEquals(21, report.current());
        assertEquals(21, report.withoutDetail().size(), "les autres fiches ne sont pas dans les fixtures");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());

        FtmEntity first = entity(dataset, dataset.buildEntityId("23699"));
        assertEquals(Set.of("Kateřina KONEČNÁ"), first.getValues("name"));
        assertEquals(Set.of("1981-01-20"), first.getValues("birthDate"));
        assertEquals(Set.of("cz"), first.getValues("country"));
        assertEquals(Set.of("role.pep"), first.getValues("topics"));
        assertEquals(1, occupancies(dataset, first, "current").size(), "un seul mandat en cours");
    }

    @Test
    void aLongerRetentionKeepsThePreviousTermsToo(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder standard = dataset();
        new CzPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), standard);
        FtmDatasetBuilder wide = dataset();
        new CzPepEuroparlParser().convert(rawDir, TODAY, 600, List.of(), wide);

        FtmEntity first = entity(wide, wide.buildEntityId("23699"));
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
            if (url.equals("detail/270261")) {
                throw new IOException("HTTP 500");
            }
            return bytes(url.startsWith("current") ? "current.json" : url.startsWith("term") ? "term.json" : "mep-23699.json");
        }, "current", "term", "detail/{id}", 0, rawDir);

        assertEquals("current", calls.get(0));
        assertEquals("term", calls.get(1));
        assertEquals(2 + 24, calls.size(), "les deux listes, puis une fiche par député");
        assertEquals(List.of("detail/270261"), failed);
    }

    @Test
    void requiresTheUrls(@TempDir Path rawDir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new CzPepEuroparlParser()
                .parse(new ParserContext("cz_pep_europarl", null, rawDir, rawDir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("currentUrl"));
    }

    private static List<FtmEntity> occupancies(FtmDatasetBuilder dataset, FtmEntity person, String status) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(person.getId()) && e.getValues("status").contains(status)).toList();
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(CzPepEuroparlParser.ID_PREFIX, CzPepEuroparlParser.TOPIC);
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
        try (InputStream in = CzPepEuroparlParserTest.class.getResourceAsStream("/europarl/" + file)) {
            if (in == null) {
                throw new IOException("404 " + file);
            }
            return in.readAllBytes();
        }
    }
}
