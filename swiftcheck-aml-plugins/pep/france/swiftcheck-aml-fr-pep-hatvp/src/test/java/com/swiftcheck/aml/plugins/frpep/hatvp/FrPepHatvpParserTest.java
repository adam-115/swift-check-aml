package com.swiftcheck.aml.plugins.frpep.hatvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Extraits réels de la liste des déclarations de la HATVP (liste.csv, novembre 2026). */
class FrPepHatvpParserTest {

    @Test
    void groupsDeclarationLinesByPersonAndFunction(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = dataset();
        FrPepHatvpParser.Report report = new FrPepHatvpParser().convert(csv(dir), Set.of(), dataset);

        assertEquals(17, report.lines());
        assertEquals(13, report.persons(), "les lignes répétées d'une même personne sont regroupées");
        assertEquals(1, report.former(), "Michel Fournier, ancien ministre délégué");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
    }

    @Test
    void writesPersonWithStableHatvpKeyAndFunctionTopics(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = dataset();
        new FrPepHatvpParser().convert(csv(dir), Set.of(), dataset);

        FtmEntity mayor = person(dataset, "Abdelaziz HAMIDA");
        assertEquals(Set.of("Maire de Goussainville"), mayor.getValues("position"));
        assertEquals(Set.of("male"), mayor.getValues("gender"));
        assertEquals(Set.of("role.pep"), mayor.getValues("topics"));
        assertTrue(mayor.getValues("sourceUrl").iterator().next().startsWith("https://www.hatvp.fr/pages_nominatives/"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("topics").contains("gov.muni")));
    }

    @Test
    void formerMembersAreKeptWithEndedStatusAndNoPrefix(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = dataset();
        new FrPepHatvpParser().convert(csv(dir), Set.of(), dataset);

        FtmEntity person = person(dataset, "Michel FOURNIER");
        assertFalse(person.getValues("position").iterator().next().startsWith("Ancien"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("status").contains("ended")));
    }

    @Test
    void typesParameterFiltersTheMandates(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = dataset();
        FrPepHatvpParser.Report report = new FrPepHatvpParser().convert(csv(dir), Set.of("autre"), dataset);

        assertEquals(1, report.persons());
        assertEquals(Set.of("autre"), report.byType().keySet());
        assertEquals(Set.of("commune", "epci"), FrPepHatvpParser.types(java.util.Map.of("types", "commune, epci")));
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name)).findFirst().orElseThrow();
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(FrPepHatvpParser.ID_PREFIX, FrPepHatvpParser.TOPIC);
    }

    private static Path csv(Path dir) throws Exception {
        Path target = dir.resolve("liste.csv");
        try (InputStream in = FrPepHatvpParserTest.class.getResourceAsStream("/hatvp/liste.csv")) {
            Files.write(target, in.readAllBytes());
        }
        return target;
    }
}
