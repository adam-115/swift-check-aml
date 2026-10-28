package com.swiftcheck.aml.plugins.frpep.senate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Extrait réel des fichiers data.senat.fr (27 octobre 2026, après le renouvellement de septembre 2026) :
 * Gérard Larcher (président) et Gabriel Amard (élu le 01/10/2026) en fonction, Pascal Allizard sorti le 30/09/2026,
 * Patrick Abate et Serge Larcher sortis il y a plus d'un an, François Abadie décédé.
 */
class FrPepSenateParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @Test
    void keepsCurrentSenatorsAndThoseWhoLeftLessThanAYearAgo(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();

        FrPepSenateParser.Report report = new FrPepSenateParser().convert(rawDir, TODAY, 12, dataset);

        assertEquals(2, report.current());
        assertEquals(1, report.former(), "Pascal Allizard");
        assertEquals(List.of("Gérard Larcher"), report.presidents());
        assertEquals(3, dataset.qualityReport().getEntities("Person"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        for (String gone : List.of("14263U", "04094Q", "83008P")) {
            assertFalse(dataset.entities().stream().anyMatch(e -> e.getId().equals(dataset.buildEntityId(gone))), gone);
        }
    }

    @Test
    void writesTheSenatorTheMandateAndThePresidency(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();
        new FrPepSenateParser().convert(rawDir, TODAY, 12, dataset);

        FtmEntity larcher = entity(dataset, dataset.buildEntityId("86034E"));
        assertEquals(Set.of("Gérard Larcher"), larcher.getValues("name"));
        assertEquals(Set.of("1949-09-14"), larcher.getValues("birthDate"));
        assertEquals(Set.of("male"), larcher.getValues("gender"));
        assertEquals(Set.of("Les Républicains"), larcher.getValues("political"));
        assertEquals(Set.of("Sénateur", "Président du Sénat"), larcher.getValues("position"));
        assertEquals(Set.of("https://www.senat.fr/senateur/86034e.html"), larcher.getValues("sourceUrl"));
        assertEquals(Set.of("role.pep"), larcher.getValues("topics"));

        FtmEntity seat = entity(dataset, dataset.buildEntityId("86034E", "53323"));
        assertEquals(Set.of("2023-10-02"), seat.getValues("startDate"));
        assertEquals(Set.of("current"), seat.getValues("status"));
        assertEquals(Set.of("Yvelines"), seat.getValues("constituency"));
        FtmEntity presidency = entity(dataset, dataset.buildEntityId("86034E", "president"));
        assertEquals(Set.of(dataset.buildEntityId("president")), presidency.getValues("post"));

        FtmEntity allizard = entity(dataset, dataset.buildEntityId("14133K"));
        assertFalse(allizard.getValues("position").contains("Président du Sénat"), "fonction au Bureau non datée pour un ancien");
        FtmEntity ended = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(allizard.getId())).findFirst().orElseThrow();
        assertEquals(Set.of("2026-09-30"), ended.getValues("endDate"));
        assertEquals(Set.of("ended"), ended.getValues("status"));
    }

    @Test
    void theRetentionPeriodIsAParameter(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        // 13 ans : Patrick Abate (sorti en 2014) et Serge Larcher (2017) sont gardés, pas François Abadie (décédé)
        FrPepSenateParser.Report report = new FrPepSenateParser().convert(rawDir, TODAY, 13 * 12, dataset());
        assertEquals(3, report.former());
        assertThrows(IllegalArgumentException.class, () -> FrPepSenateParser.monthsAfterEnd(Map.of("monthsAfterEnd", "un an")));
    }

    @Test
    void downloadsBothFilesAndListsSourceRecords(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        SenateFiles.download(url -> {
            calls.add(url);
            return bytes(url.substring(url.lastIndexOf('/') + 1));
        }, "https://data.senat.fr/data/senateurs/ODSEN_GENERAL.csv",
                "https://data.senat.fr/data/senateurs/ODSEN_ELUSEN.csv", rawDir);

        assertEquals(2, calls.size());
        List<SourceRecord> records = new FrPepSenateParser()
                .readSourceRecords(new ParserContext("fr_pep_senate", null, rawDir, null, Map.of()));
        assertTrue(records.stream().anyMatch(r -> r.name().equals("Gérard Larcher")));
    }

    @Test
    void refusesMissingParametersAndUnreadableEndDates(@TempDir Path rawDir) throws Exception {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new FrPepSenateParser()
                .parse(new ParserContext("fr_pep_senate", null, rawDir, rawDir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("generalUrl"));

        copyFixture(rawDir);
        Path mandates = rawDir.resolve(SenateFiles.MANDATES_FILE);
        Files.write(mandates, new String(Files.readAllBytes(mandates), "windows-1252")
                .replace("2026-09-30 00:00:00.0", "fin septembre").getBytes("windows-1252"));
        assertThrows(Exception.class, () -> new FrPepSenateParser().convert(rawDir, TODAY, 12, dataset()));
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(FrPepSenateParser.ID_PREFIX, FrPepSenateParser.TOPIC);
    }

    private static FtmEntity entity(FtmDatasetBuilder dataset, String id) {
        return dataset.entities().stream().filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }

    private static void copyFixture(Path rawDir) throws IOException {
        for (String f : List.of(SenateFiles.GENERAL_FILE, SenateFiles.MANDATES_FILE)) {
            Files.write(rawDir.resolve(f), bytes(f));
        }
    }

    private static byte[] bytes(String file) throws IOException {
        try (InputStream in = FrPepSenateParserTest.class.getResourceAsStream("/senat/" + file)) {
            return in.readAllBytes();
        }
    }
}
