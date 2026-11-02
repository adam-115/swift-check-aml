package com.swiftcheck.aml.plugins.bepep.europarl;

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
class BePepEuroparlParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final List<String> FILES = List.of("current.json", "term.json", "mep-125106.json",
            "mep-124936.json", "mep-197420.json");

    @Test
    void keepsCurrentMepsWithTheirBirthAndMandate(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        BePepEuroparlParser.Report report = new BePepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        assertEquals(1, report.current());
        assertEquals(0, report.former());
        assertEquals(2, report.tooOld(), "sorties en 2021 et le 15/07/2024 : plus de 12 mois");
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity bay = entity(dataset, dataset.buildEntityId("125106"));
        assertEquals(Set.of("Johan VAN OVERTVELDT"), bay.getValues("name"));
        assertEquals(Set.of("Van Overtveldt"), bay.getValues("lastName"));
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
    void photosOfKeptMepsComeFromWikimediaCommons(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();
        new BePepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);
        List<String> queries = new ArrayList<>();
        String tsv = "?id\t?image\n\"125106\"\t<http://commons.wikimedia.org/wiki/Special:FilePath/Johan%20Van%20Overtveldt.jpg>\n";

        String note = BePepEuroparlParser.addPhotos(dataset, EuroparlApi.meps(rawDir).keySet(), q -> {
            queries.add(q);
            return tsv;
        }, rawDir);

        assertEquals("Photos Wikimedia Commons : 1 sur 1.", note);
        assertTrue(queries.get(0).contains("\"125106\"") && !queries.get(0).contains("\"197420\""),
                "seulement les députés gardés");
        assertEquals(List.of(new FtmDatasetBuilder.FtmMedia(dataset.buildEntityId("125106"),
                "https://commons.wikimedia.org/wiki/Special:FilePath/Johan%20Van%20Overtveldt.jpg",
                "https://commons.wikimedia.org/wiki/File:Johan%20Van%20Overtveldt.jpg", null)), dataset.media());
        assertEquals(tsv, Files.readString(rawDir.resolve(BePepEuroparlParser.PHOTOS_FILE)), "réponse archivée");

        // Wikidata injoignable : la publication continue sans photo
        assertTrue(BePepEuroparlParser.addPhotos(dataset, List.of("125106"), q -> {
            throw new IOException("HTTP 503");
        }, rawDir).startsWith("Photos non cherchées"));
    }

    @Test
    void keepsAMepWhoLeftWithinTheRetentionPeriod(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        BePepEuroparlParser.Report report = new BePepEuroparlParser().convert(rawDir, TODAY, 28, List.of(), dataset);

        assertEquals(1, report.former(), "Maria Arena, sortie le 15/07/2024 : gardée avec une rétention de 28 mois");
        FtmEntity arena = entity(dataset, dataset.buildEntityId("124936"));
        assertEquals(Set.of("1966-12-17"), arena.getValues("birthDate"));
        assertEquals(Set.of("Mons"), arena.getValues("birthPlace"));
        assertEquals(Set.of("female"), arena.getValues("gender"));
        FtmEntity mandate = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(arena.getId())).findFirst().orElseThrow();
        assertEquals(Set.of("2024-07-15"), mandate.getValues("endDate"));
        assertEquals(Set.of("ended"), mandate.getValues("status"));
    }

    @Test
    void aFormerMepWithoutDetailIsKeptWithoutDates(@TempDir Path rawDir) throws Exception {
        copy(rawDir, List.of("current.json", "term.json", "mep-125106.json"));

        BePepEuroparlParser.Report report = new BePepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset());

        assertEquals(2, report.former());
        assertEquals(2, report.withoutDetail().size());
        assertTrue(report.message().contains("2 sans fiche"));
    }

    @Test
    void downloadsBothListsThenEachDetail(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        List<String> failed = EuroparlApi.download(url -> {
            calls.add(url);
            if (url.contains("197420")) {
                throw new IOException("HTTP 500");
            }
            return bytes(url.startsWith("current") ? "current.json" : url.startsWith("term") ? "term.json"
                    : "mep-" + url.replaceAll("\\D", "") + ".json");
        }, "current", "term", "detail/{id}", 0, rawDir);

        assertEquals(List.of("current", "term", "detail/125106", "detail/124936", "detail/197420"), calls);
        assertEquals(List.of("detail/197420"), failed);

        List<SourceRecord> records = new BePepEuroparlParser()
                .readSourceRecords(new ParserContext("be_pep_europarl", null, rawDir, null, Map.of()));
        assertTrue(records.stream().anyMatch(r -> r.name().equals("Johan VAN OVERTVELDT")
                && r.sourceRef().equals("https://www.europarl.europa.eu/meps/fr/125106")));
    }

    @Test
    void requiresTheUrls(@TempDir Path rawDir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new BePepEuroparlParser()
                .parse(new ParserContext("be_pep_europarl", null, rawDir, rawDir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("currentUrl"));
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(BePepEuroparlParser.ID_PREFIX, BePepEuroparlParser.TOPIC);
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
        try (InputStream in = BePepEuroparlParserTest.class.getResourceAsStream("/europarl/" + file)) {
            if (in == null) {
                throw new IOException("404 " + file);
            }
            return in.readAllBytes();
        }
    }
}
