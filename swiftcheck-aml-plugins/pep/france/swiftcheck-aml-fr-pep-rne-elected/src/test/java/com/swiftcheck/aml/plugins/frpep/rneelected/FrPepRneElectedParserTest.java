package com.swiftcheck.aml.plugins.frpep.rneelected;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Extraits réels du Répertoire national des élus (data.gouv.fr, 11/08/2026). */
class FrPepRneElectedParserTest {

    @Test
    void headsOfExecutiveAreLeftToTheOtherParsers() {
        assertTrue(FrPepRneElectedParser.covered("Président du conseil départemental"));
        assertTrue(FrPepRneElectedParser.covered("Président de l'assemblée"));
        assertTrue(!FrPepRneElectedParser.covered("Vice-président du conseil communautaire"));
    }

    @Test
    void readsMayorsDeputyMayorsAndCouncils(@TempDir Path rawDir) throws Exception {
        copyFixtures(rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepRneElectedParser.ID_PREFIX, FrPepRneElectedParser.TOPIC);

        FrPepRneElectedParser.Report report = new FrPepRneElectedParser().convert(rawDir, false, dataset);

        assertEquals(2, report.mayors(), "2 maires délégués de Valserhône ; les maires sont dans fr_pep_local_executives_all");
        assertEquals(2, report.deputyMayors());
        assertTrue(report.epci() >= 1, "au moins un membre du bureau d'intercommunalité");
        assertEquals(2, report.departments(), "deux conseillers départementaux de l Ain");
        assertTrue(report.regions() >= 1, "conseillers régionaux");
        assertTrue(report.special() >= 1, "élus de collectivités à statut particulier");
        assertEquals(0, report.councillors(), "conseiller municipal sans fonction exclu par défaut");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
    }

    @Test
    void includesPlainCouncillorsWhenAsked(@TempDir Path rawDir) throws Exception {
        copyFixtures(rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepRneElectedParser.ID_PREFIX, FrPepRneElectedParser.TOPIC);

        FrPepRneElectedParser.Report report = new FrPepRneElectedParser().convert(rawDir, true, dataset);

        assertEquals(1, report.councillors());
    }

    @Test
    void rankIsMovedToTheOccupancyDescription(@TempDir Path rawDir) throws Exception {
        copyFixtures(rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepRneElectedParser.ID_PREFIX, FrPepRneElectedParser.TOPIC);
        new FrPepRneElectedParser().convert(rawDir, false, dataset);

        assertTrue(dataset.entities().stream().anyMatch(e -> "Person".equals(e.getSchema())
                && e.getValues("position").contains("Adjoint au Maire – L'Abergement-Clémenciat")));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("description").contains("Rang 2")));
    }

    private static void copyFixtures(Path rawDir) throws Exception {
        for (var f : FrPepRneElectedParser.FILES.entrySet()) {
            String name = f.getValue().equals("municipaux.csv") ? "municipaux.csv" : f.getValue();
            try (InputStream in = FrPepRneElectedParserTest.class.getResourceAsStream("/rne/" + name)) {
                Files.write(rawDir.resolve(f.getValue()), in.readAllBytes());
            }
        }
    }
}
