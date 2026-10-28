package com.swiftcheck.aml.plugins.frpep.europarl;

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
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Réponses réelles de l'API du Parlement européen (octobre 2026), réduites à trois députés : Nicolas Bay en
 * fonction, Marie-Pierre Vedrenne sortie le 11/10/2025 (entrée au Gouvernement), Sylvie Josserand sortie le
 * 26/09/2024.
 */
class FrPepEuroparlParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final List<String> FILES = List.of("current.json", "term.json", "mep-124760.json",
            "mep-197502.json", "mep-256894.json");

    @Test
    void keepsCurrentMepsWithTheirBirthAndMandate(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        FrPepEuroparlParser.Report report = new FrPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        assertEquals(1, report.current());
        assertEquals(0, report.former());
        assertEquals(2, report.tooOld(), "sorties en 2024 et le 11/10/2025 : plus de 12 mois");
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity bay = entity(dataset, dataset.buildEntityId("124760"));
        assertEquals(Set.of("Nicolas BAY"), bay.getValues("name"));
        assertEquals(Set.of("Bay"), bay.getValues("lastName"));
        assertEquals(1, bay.getValues("birthDate").size());
        assertEquals(Set.of("male"), bay.getValues("gender"));
        assertEquals(Set.of("role.pep"), bay.getValues("topics"));

        List<FtmEntity> mandates = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertEquals(1, mandates.size(), "seul le mandat en cours : les législatures précédentes sont trop anciennes");
        assertEquals(Set.of("current"), mandates.get(0).getValues("status"));
        assertEquals(Set.of("2024-07-16"), mandates.get(0).getValues("startDate"));
        assertEquals(Set.of("ECR"), mandates.get(0).getValues("politicalGroup"));
    }

    @Test
    void keepsAMepWhoLeftWithinTheRetentionPeriod(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        FrPepEuroparlParser.Report report = new FrPepEuroparlParser().convert(rawDir, TODAY, 13, List.of(), dataset);

        assertEquals(1, report.former(), "Marie-Pierre Vedrenne, sortie il y a un peu plus de 12 mois");
        FtmEntity vedrenne = entity(dataset, dataset.buildEntityId("197502"));
        assertEquals(Set.of("1982-12-13"), vedrenne.getValues("birthDate"));
        assertEquals(Set.of("Limoges"), vedrenne.getValues("birthPlace"));
        assertEquals(Set.of("female"), vedrenne.getValues("gender"));
        FtmEntity mandate = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(vedrenne.getId())).findFirst().orElseThrow();
        assertEquals(Set.of("2025-10-11"), mandate.getValues("endDate"));
        assertEquals(Set.of("ended"), mandate.getValues("status"));
    }

    @Test
    void aFormerMepWithoutDetailIsKeptWithoutDates(@TempDir Path rawDir) throws Exception {
        copy(rawDir, List.of("current.json", "term.json", "mep-124760.json"));

        FrPepEuroparlParser.Report report = new FrPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset());

        assertEquals(2, report.former());
        assertEquals(2, report.withoutDetail().size());
        assertTrue(report.message().contains("2 sans fiche"));
    }

    @Test
    void downloadsBothListsThenEachDetail(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        List<String> failed = EuroparlApi.download(url -> {
            calls.add(url);
            if (url.contains("256894")) {
                throw new IOException("HTTP 500");
            }
            return bytes(url.startsWith("current") ? "current.json" : url.startsWith("term") ? "term.json"
                    : "mep-" + url.replaceAll("\\D", "") + ".json");
        }, "current", "term", "detail/{id}", 0, rawDir);

        assertEquals(List.of("current", "term", "detail/124760", "detail/197502", "detail/256894"), calls);
        assertEquals(List.of("detail/256894"), failed);

        List<SourceRecord> records = new FrPepEuroparlParser()
                .readSourceRecords(new ParserContext("fr_pep_europarl", null, rawDir, null, Map.of()));
        assertTrue(records.stream().anyMatch(r -> r.name().equals("Nicolas BAY")
                && r.sourceRef().equals("https://www.europarl.europa.eu/meps/fr/124760")));
    }

    @Test
    void requiresTheUrls(@TempDir Path rawDir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new FrPepEuroparlParser()
                .parse(new ParserContext("fr_pep_europarl", null, rawDir, rawDir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("currentUrl"));
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(FrPepEuroparlParser.ID_PREFIX, FrPepEuroparlParser.TOPIC);
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
        try (InputStream in = FrPepEuroparlParserTest.class.getResourceAsStream("/europarl/" + file)) {
            if (in == null) {
                throw new IOException("404 " + file);
            }
            return in.readAllBytes();
        }
    }
}
