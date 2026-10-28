package com.swiftcheck.aml.plugins.frpep.nationalassembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Extrait réel de l'archive AMO30 (27 octobre 2026) : Alain David et Yaël Braun-Pivet (présidente) en fonction,
 * Laurent Panifous sorti le 12/11/2025 (entré au Gouvernement), Nicolas Dupont-Aignan sorti le 09/06/2024.
 */
class FrPepNationalAssemblyParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @TempDir
    static Path dir;
    private static Path zip;

    @BeforeAll
    static void copyArchive() throws Exception {
        zip = dir.resolve("amo30.json.zip");
        try (InputStream in = FrPepNationalAssemblyParserTest.class.getResourceAsStream("/amo30-extrait.json.zip")) {
            Files.copy(in, zip);
        }
    }

    @Test
    void keepsCurrentDeputiesAndThoseWhoLeftLessThanAYearAgo() throws Exception {
        FtmDatasetBuilder dataset = dataset();

        FrPepNationalAssemblyParser.Report report = new FrPepNationalAssemblyParser().convert(zip, TODAY, 12, dataset);

        assertEquals(2, report.current());
        assertEquals(1, report.former(), "Laurent Panifous ; pas Nicolas Dupont-Aignan, sorti en 2024");
        assertEquals(List.of("Yaël Braun-Pivet"), report.presidents());
        assertEquals("2 députés en fonction, 1 sortis depuis moins de 12 mois ; présidence : Yaël Braun-Pivet.",
                report.message());
        assertEquals(3, dataset.qualityReport().getEntities("Person"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().stream().anyMatch(e -> e.getId().equals(dataset.buildEntityId("PA1206"))));
    }

    @Test
    void writesTheDeputyWithBirthGroupAndMandate() throws Exception {
        FtmDatasetBuilder dataset = dataset();
        new FrPepNationalAssemblyParser().convert(zip, TODAY, 12, dataset);

        FtmEntity david = entity(dataset, dataset.buildEntityId("PA1008"));
        assertEquals(Set.of("Alain David"), david.getValues("name"));
        assertEquals(Set.of("1949-06-02"), david.getValues("birthDate"));
        assertEquals(Set.of("Libourne"), david.getValues("birthPlace"));
        assertEquals(Set.of("male"), david.getValues("gender"));
        assertEquals(Set.of("SOC"), david.getValues("political"));
        assertEquals(Set.of("role.pep"), david.getValues("topics"));

        FtmEntity seat = entity(dataset, dataset.buildEntityId("PA1008", "PM842621"));
        assertEquals(Set.of(david.getId()), seat.getValues("holder"));
        assertEquals(Set.of(dataset.buildEntityId("deputy")), seat.getValues("post"));
        assertEquals(Set.of("2024-07-07"), seat.getValues("startDate"));
        assertEquals(Set.of("current"), seat.getValues("status"));
        assertEquals(Set.of("Gironde, 4e circonscription"), seat.getValues("constituency"));
        assertEquals(Set.of("SOC"), seat.getValues("politicalGroup"));
    }

    @Test
    void writesThePresidencyAndTheFormerDeputy() throws Exception {
        FtmDatasetBuilder dataset = dataset();
        new FrPepNationalAssemblyParser().convert(zip, TODAY, 12, dataset);

        FtmEntity braunPivet = entity(dataset, dataset.buildEntityId("PA721908"));
        assertEquals(Set.of("female"), braunPivet.getValues("gender"));
        assertEquals(Set.of("EPR"), braunPivet.getValues("political"));
        assertTrue(braunPivet.getValues("position").contains("Président de l'Assemblée nationale"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("post").contains(dataset.buildEntityId("president"))
                && e.getValues("holder").contains(braunPivet.getId())
                && e.getValues("status").contains("current")));

        FtmEntity panifous = entity(dataset, dataset.buildEntityId("PA793210"));
        assertEquals(Set.of("LIOT"), panifous.getValues("political"), "groupe à la fin du mandat");
        FtmEntity seat = entity(dataset, dataset.buildEntityId("PA793210", "PM842417"));
        assertEquals(Set.of("ended"), seat.getValues("status"));
        assertEquals(Set.of("2025-11-12"), seat.getValues("endDate"));
        assertEquals(Set.of("Ariège, 2e circonscription"), seat.getValues("constituency"));
    }

    @Test
    void theRetentionPeriodIsAParameter() throws Exception {
        // Trois ans : Nicolas Dupont-Aignan, sorti le 09/06/2024, est gardé
        FrPepNationalAssemblyParser.Report report = new FrPepNationalAssemblyParser().convert(zip, TODAY, 36, dataset());
        assertEquals(2, report.former());
        assertEquals(36, FrPepNationalAssemblyParser.monthsAfterEnd(Map.of("monthsAfterEnd", "36")));
        assertEquals(12, FrPepNationalAssemblyParser.monthsAfterEnd(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> FrPepNationalAssemblyParser.monthsAfterEnd(Map.of("monthsAfterEnd", "un an")));
    }

    @Test
    void parseAndSourceRecordsUseTheFileGivenByTheCore(@TempDir Path out) throws Exception {
        var result = new FrPepNationalAssemblyParser()
                .parse(new ParserContext("fr_pep_national_assembly", zip, out, out.resolve("out.ndjson"), Map.of()));
        List<SourceRecord> records = new FrPepNationalAssemblyParser()
                .readSourceRecords(new ParserContext("fr_pep_national_assembly", zip, null, null, Map.of()));

        assertEquals(result.entityCount(), Files.readAllLines(out.resolve("out.ndjson")).size());
        assertEquals(result.entityCount(), result.entities().size());
        assertEquals(records.size(), result.entities().stream().filter(e -> e.getSchema().equals("Person")).count());
        assertTrue(records.stream().anyMatch(r -> r.name().equals("Yaël Braun-Pivet")
                && r.sourceRef().equals("https://www.assemblee-nationale.fr/dyn/deputes/PA721908")));
    }

    @Test
    void refusesAnArchiveWithoutActors(@TempDir Path out) throws Exception {
        Path empty = out.resolve("vide.zip");
        try (var zipOut = new java.util.zip.ZipOutputStream(Files.newOutputStream(empty))) {
            zipOut.putNextEntry(new java.util.zip.ZipEntry("json/organe/PO1.json"));
            zipOut.write("{\"organe\": {\"uid\": \"PO1\", \"codeType\": \"GP\"}}".getBytes());
        }
        assertThrows(java.io.IOException.class, () -> new FrPepNationalAssemblyParser().convert(empty, TODAY, 12, dataset()));
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(FrPepNationalAssemblyParser.ID_PREFIX, FrPepNationalAssemblyParser.TOPIC);
    }

    private static FtmEntity entity(FtmDatasetBuilder dataset, String id) {
        return dataset.entities().stream().filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }
}
