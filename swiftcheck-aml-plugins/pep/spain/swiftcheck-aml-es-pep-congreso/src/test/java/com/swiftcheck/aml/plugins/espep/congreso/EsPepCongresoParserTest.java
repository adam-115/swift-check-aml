package com.swiftcheck.aml.plugins.espep.congreso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Fichiers réels de l'open data du Congreso (3 novembre 2026), sans la biographie. */
class EsPepCongresoParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    private static Path rawDir(Path dir) throws IOException {
        for (String file : new String[] { EsPepCongresoParser.ACTIVE_FILE, EsPepCongresoParser.FORMER_FILE }) {
            try (InputStream in = EsPepCongresoParserTest.class.getResourceAsStream("/congreso/" + file)) {
                Files.copy(in, dir.resolve(file));
            }
        }
        return dir;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow(() -> new AssertionError("Personne absente : " + name));
    }

    @Test
    void keepsActiveDeputiesAndThoseWhoLeftWithinTwoYears(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepCongresoParser.ID_PREFIX, EsPepCongresoParser.TOPIC);
        EsPepCongresoParser.Report report = new EsPepCongresoParser().convert(rawDir(dir), TODAY, 24, dataset);

        assertEquals(3, report.current());
        assertEquals(1, report.former(), "Cristina Abades (06/10/2026) ; les deux autres sont partis en 2023 et 2024");
        assertEquals(2, report.tooOld());
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity abascal = person(dataset, "Santiago Abascal Conde");
        assertEquals(Set.of("Santiago"), abascal.getValues("firstName"));
        assertEquals(Set.of("Abascal Conde"), abascal.getValues("lastName"));
        assertEquals(Set.of("VOX"), abascal.getValues("political"));
        assertEquals(Set.of("es"), abascal.getValues("nationality"));
        assertEquals(Set.of("role.pep"), abascal.getValues("topics"));
    }

    @Test
    void closesTheMandateOfAFormerDeputy(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepCongresoParser.ID_PREFIX, EsPepCongresoParser.TOPIC);
        new EsPepCongresoParser().convert(rawDir(dir), TODAY, 24, dataset);

        FtmEntity abades = person(dataset, "Cristina Abades Martínez");
        assertTrue(abades.getValues("position").contains("Ancien : Diputado al Congreso de los Diputados"));
        FtmEntity occupancy = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(abades.getId())).findFirst().orElseThrow();
        assertEquals(Set.of("2023-08-08"), occupancy.getValues("startDate"));
        assertEquals(Set.of("2026-10-06"), occupancy.getValues("endDate"));
        assertEquals(Set.of("ended"), occupancy.getValues("status"));
        assertEquals(Set.of("Lugo"), occupancy.getValues("constituency"));
    }

    @Test
    void usesTheRetentionOfTheCountry(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepCongresoParser.ID_PREFIX, EsPepCongresoParser.TOPIC);
        // 12 mois : Abades (octobre 2026) reste, un départ de 2024 n'est plus gardé
        EsPepCongresoParser.Report report = new EsPepCongresoParser().convert(rawDir(dir), TODAY, 12, dataset);
        assertEquals(1, report.former());

        FtmDatasetBuilder wide = new FtmDatasetBuilder(EsPepCongresoParser.ID_PREFIX, EsPepCongresoParser.TOPIC);
        EsPepCongresoParser.Report all = new EsPepCongresoParser().convert(dir, TODAY, 48, wide);
        assertEquals(3, all.former());
        assertFalse(wide.entities().isEmpty());
    }

    @Test
    void doesNotKeepBiographies(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepCongresoParser.ID_PREFIX, EsPepCongresoParser.TOPIC);
        new EsPepCongresoParser().convert(rawDir(dir), TODAY, 24, dataset);
        assertTrue(dataset.entities().stream().noneMatch(e -> e.getValues("notes").size() > 0));
    }

    @Test
    void findsTheTimestampedLinksOnThePage() {
        String page = "<a href=\"/webpublica/opendata/diputados/DiputadosActivos__20261103050009.json\">JSON</a>"
                + "<a href=\"/webpublica/opendata/diputados/DiputadosDeBaja__20261103050013.json\">JSON</a>";
        assertEquals("https://www.congreso.es/webpublica/opendata/diputados/DiputadosActivos__20261103050009.json",
                EsPepCongresoParser.link(page, "https://www.congreso.es/es/opendata/diputados", "DiputadosActivos"));
        assertThrows(IllegalStateException.class,
                () -> EsPepCongresoParser.link("<html></html>", "https://www.congreso.es/es/opendata/diputados", "DiputadosActivos"));
    }

    @Test
    void failsWithoutFiles(@TempDir Path dir) {
        assertThrows(IllegalStateException.class, () -> new EsPepCongresoParser().convert(dir, TODAY, 24,
                new FtmDatasetBuilder(EsPepCongresoParser.ID_PREFIX, EsPepCongresoParser.TOPIC)));
    }
}
