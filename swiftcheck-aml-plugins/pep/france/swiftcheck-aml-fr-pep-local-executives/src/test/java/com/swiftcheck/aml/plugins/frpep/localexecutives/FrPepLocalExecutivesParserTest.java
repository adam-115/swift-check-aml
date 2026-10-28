package com.swiftcheck.aml.plugins.frpep.localexecutives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Extraits réels du Répertoire national des élus (11/08/2026) et des populations de l'API Découpage administratif :
 * Lyon et L'Abergement-Clémenciat (maires), Métropole européenne de Lille et une communauté de communes de l'Ain
 * (présidents et un vice-président), conseil départemental de l'Ain, conseil régional Auvergne-Rhône-Alpes, Corse,
 * Métropole de Lyon et Saint-Pierre-et-Miquelon. Le fichier des populations ne contient que six communes : les
 * sommes par département ou région sont donc celles de ces communes.
 */
class FrPepLocalExecutivesParserTest {

    @Test
    void keepsOnlyTheHeadsOfCollectivitiesAboveTheThreshold(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();

        FrPepLocalExecutivesParser.Report report = new FrPepLocalExecutivesParser().convert(rawDir, 50_000, dataset);

        assertEquals(1, report.mayors(), "Lyon (519 127 habitants), pas L'Abergement-Clémenciat (860)");
        assertEquals(1, report.epciPresidents(), "Métropole européenne de Lille ; ni le vice-président ni la CC de l'Ain");
        assertEquals(0, report.departmentPresidents(), "Ain : une seule commune dans l'extrait (860 habitants)");
        assertEquals(1, report.regionPresidents(), "Auvergne-Rhône-Alpes : Lyon + 2 communes");
        assertEquals(3, report.specialPresidents(), "Corse (assemblée et exécutif) et Métropole de Lyon ; pas Saint-Pierre-et-Miquelon");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
    }

    @Test
    void writesThePersonWithBirthDateAndTheCollectivity(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();
        new FrPepLocalExecutivesParser().convert(rawDir, 50_000, dataset);

        FtmEntity mayor = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("position").contains("Maire – Lyon"))
                .findFirst().orElseThrow();
        assertEquals(1, mayor.getValues("birthDate").size());
        assertEquals(1, mayor.getValues("gender").size());
        assertEquals(Set.of("role.pep"), mayor.getValues("topics"));
        FtmEntity position = dataset.entities().stream()
                .filter(e -> "Position".equals(e.getSchema()) && e.getValues("name").contains("Maire – Lyon"))
                .findFirst().orElseThrow();
        assertEquals(Set.of("Lyon"), position.getValues("subnationalArea"));
        assertTrue(position.getValues("topics").contains("gov.muni"));
    }

    @Test
    void theThresholdIsAParameter(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);

        FrPepLocalExecutivesParser.Report report = new FrPepLocalExecutivesParser().convert(rawDir, 500, dataset());

        assertEquals(2, report.mayors());
        assertEquals(1, report.departmentPresidents());
        assertEquals(4, report.specialPresidents(), "Saint-Pierre-et-Miquelon (5 194 habitants) au-dessus de 500");
        assertEquals(50_000, FrPepLocalExecutivesParser.minPopulation(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> FrPepLocalExecutivesParser.minPopulation(Map.of("minPopulation", "50k")));
    }

    @Test
    void downloadsTheSixFilesAndListsSourceRecords(@TempDir Path rawDir) throws Exception {
        Map<String, String> params = new HashMap<>();
        FrPepLocalExecutivesParser.FILES.forEach((key, file) -> params.put(key, "https://example.org/" + file));

        FrPepLocalExecutivesParser.download(url -> bytes(url.substring(url.lastIndexOf('/') + 1)), params, rawDir);

        List<SourceRecord> records = new FrPepLocalExecutivesParser()
                .readSourceRecords(new ParserContext("fr_pep_local_executives", null, rawDir, null, Map.of()));
        assertEquals(6, records.size());
        params.remove("epciUrl");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FrPepLocalExecutivesParser.download(url -> new byte[0], params, rawDir));
        assertTrue(e.getMessage().contains("epciUrl"));
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(FrPepLocalExecutivesParser.ID_PREFIX, FrPepLocalExecutivesParser.TOPIC);
    }

    private static void copyFixture(Path rawDir) throws IOException {
        for (String file : FrPepLocalExecutivesParser.FILES.values()) {
            Files.write(rawDir.resolve(file), bytes(file));
        }
    }

    private static byte[] bytes(String file) throws IOException {
        try (InputStream in = FrPepLocalExecutivesParserTest.class.getResourceAsStream("/rne/" + file)) {
            return in.readAllBytes();
        }
    }
}
