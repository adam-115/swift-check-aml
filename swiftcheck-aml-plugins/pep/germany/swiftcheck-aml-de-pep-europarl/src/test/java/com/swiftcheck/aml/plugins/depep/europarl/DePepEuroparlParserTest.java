package com.swiftcheck.aml.plugins.depep.europarl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class DePepEuroparlParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final List<String> FILES = List.of("current.json", "term.json", "mep-197420.json",
            "mep-124936.json", "mep-125106.json");

    @Test
    void keepsCurrentMepsWithTheirBirthAndMandate(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        DePepEuroparlParser.Report report = new DePepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        assertEquals(1, report.current(), "1 député actif (Katarina Barley)");
        assertEquals(1, report.former(), "sortie le 15/05/2026 : moins de 12 mois");
        assertEquals(1, report.tooOld(), "sortie en 2021 : plus de 12 mois");
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity barley = entity(dataset, dataset.buildEntityId("197420"));
        assertEquals(Set.of("Katarina BARLEY"), barley.getValues("name"));
        assertEquals(Set.of("Barley"), barley.getValues("lastName"));
        assertEquals(Set.of("1968-11-19"), barley.getValues("birthDate"));
        assertEquals(Set.of("Köln"), barley.getValues("birthPlace"));
        assertEquals(Set.of("female"), barley.getValues("gender"));
        assertEquals(Set.of("role.pep"), barley.getValues("topics"));

        // Vérifications de conformité Story 17
        assertEquals(Set.of(DePepEuroparlParser.POSITION), barley.getValues("position"),
                "Titre officiel strict allemand exigé par la Story 17");
        assertTrue(barley.getValues("sourceUrl").iterator().next().contains("/meps/de/197420"),
                "URL source officielle en langue allemande (/meps/de/)");
        assertTrue(barley.getValues("description").iterator().next().contains("EU_MEP_197420"),
                "Identifiant externe EU_MEP_197420 requis par la Story 17");

        // Vérification de la photo officielle enregistrée
        boolean hasOfficialPhoto = dataset.media().stream().anyMatch(
                m -> m.imageUrl().equals("https://www.europarl.europa.eu/mepphoto/197420.jpg"));
        assertTrue(hasOfficialPhoto, "Photo officielle du Parlement européen enregistrée dans les médias");

        // Positions et Occupancies
        List<FtmEntity> positions = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).toList();
        assertTrue(positions.size() >= 1);
        assertEquals(Set.of("de"), positions.get(0).getValues("country"));

        List<FtmEntity> occupancies = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertTrue(occupancies.size() >= 2);

        // AC2.2 : Vérification du statut pour les anciens mandats
        FtmEntity formerOccupancy = occupancies.stream()
                .filter(o -> "ended".equals(o.getValues("status").iterator().next()))
                .findFirst()
                .orElse(null);
        assertNotNull(formerOccupancy, "Un mandat terminé doit avoir le statut ended (AC2.2)");
        assertNotNull(formerOccupancy.getValues("endDate"), "Un mandat terminé doit avoir une endDate renseignée");
    }

    @Test
    void testReadSourceRecords(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        ParserContext ctx = new ParserContext("de_pep_europarl", null, rawDir, null, Map.of("monthsAfterEnd", "12"));
        List<SourceRecord> records = new DePepEuroparlParser().readSourceRecords(ctx);
        assertEquals(2, records.size());
    }

    private static void copy(Path dir, List<String> files) throws IOException {
        for (String file : files) {
            try (InputStream in = DePepEuroparlParserTest.class.getResourceAsStream("/europarl/" + file)) {
                if (in == null) {
                    throw new IllegalArgumentException("/europarl/" + file + " introuvable dans le classpath.");
                }
                Files.copy(in, dir.resolve(file));
            }
        }
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(DePepEuroparlParser.ID_PREFIX, DePepEuroparlParser.TOPIC);
    }

    private static FtmEntity entity(FtmDatasetBuilder dataset, String id) {
        return dataset.entities().stream().filter(e -> id.equals(e.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }
}
