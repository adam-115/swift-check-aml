package com.swiftcheck.aml.plugins.espep.europarl;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class EsPepEuroparlParserTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 1);
    private static final List<String> FILES = List.of("current.json", "term.json", "mep-122978.json",
            "mep-125005.json", "mep-124972.json");

    @Test
    void keepsCurrentMepsWithTheirBirthAndMandate(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        EsPepEuroparlParser.Report report = new EsPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        assertEquals(1, report.current());
        assertEquals(1, report.former(), "Javier Nart, sorti le 15/07/2024 : moins de 12 mois avant le 1er juin 2025");
        assertEquals(1, report.tooOld(), "Ernest Urtasun, sorti le 20/11/2023 : plus de 12 mois");
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity homs = entity(dataset, dataset.buildEntityId("122978"));
        assertEquals(Set.of("Alicia HOMS GINEL"), homs.getValues("name"));
        assertEquals(Set.of("Homs Ginel"), homs.getValues("lastName"));
        assertEquals(Set.of("1993-10-15"), homs.getValues("birthDate"));
        assertEquals(Set.of("Palma de Mallorca"), homs.getValues("birthPlace"));
        assertEquals(Set.of("female"), homs.getValues("gender"));
        assertEquals(Set.of("role.pep"), homs.getValues("topics"));

        List<FtmEntity> positions = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).toList();
        assertEquals(1, positions.size());
        assertEquals(Set.of("es"), positions.get(0).getValues("country"));

        List<FtmEntity> occupancies = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertEquals(3, occupancies.size(), "Homs : 2 mandats ; Nart : 1 mandat récent");
    }

    @Test
    void testReadSourceRecords(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        ParserContext ctx = new ParserContext("es_pep_europarl", null, rawDir, null, Map.of("monthsAfterEnd", "12"));
        List<SourceRecord> records = new EsPepEuroparlParser().readSourceRecords(ctx);
        assertEquals(1, records.size(), "à la date du jour, seule Alicia Homs Ginel est en fonction");
    }

    private static void copy(Path dir, List<String> files) throws IOException {
        for (String file : files) {
            try (InputStream in = EsPepEuroparlParserTest.class.getResourceAsStream("/europarl/" + file)) {
                if (in == null) {
                    throw new IllegalArgumentException("/europarl/" + file + " introuvable dans le classpath.");
                }
                Files.copy(in, dir.resolve(file));
            }
        }
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(EsPepEuroparlParser.ID_PREFIX, EsPepEuroparlParser.TOPIC);
    }

    private static FtmEntity entity(FtmDatasetBuilder dataset, String id) {
        return dataset.entities().stream().filter(e -> id.equals(e.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }
}
