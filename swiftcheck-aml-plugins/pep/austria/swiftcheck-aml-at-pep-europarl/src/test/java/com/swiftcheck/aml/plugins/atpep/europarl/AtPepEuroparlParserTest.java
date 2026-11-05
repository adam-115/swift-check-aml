package com.swiftcheck.aml.plugins.atpep.europarl;

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
 * Réponses réelles de l'API du Parlement européen (5 novembre 2026) : les 21 députés élus en Autriche en fonction,
 * dont deux fiches complètes : harald vilimsky (premier mandat) et Lídia Pereira (législatures 9 et 10).
 */
class AtPepEuroparlParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final List<String> FILES = List.of("current.json", "term.json", "mep-125001.json", "mep-190713.json");

    @Test
    void keepsEveryCurrentMepWithBirthAndMandateWhenTheRecordIsKnown(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        AtPepEuroparlParser.Report report = new AtPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        assertEquals(20, report.current());
        assertEquals(0, report.former());
        assertEquals(18, report.withoutDetail().size(), "les 18 autres fiches ne sont pas dans les fixtures");
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity pedro = entity(dataset, dataset.buildEntityId("125001"));
        assertEquals(Set.of("Harald VILIMSKY"), pedro.getValues("name"));
        assertEquals(Set.of("Vilimsky"), pedro.getValues("lastName"));
        assertEquals(Set.of("1966-07-22"), pedro.getValues("birthDate"));
        assertEquals(Set.of("male"), pedro.getValues("gender"));
        assertEquals(Set.of("role.pep"), pedro.getValues("topics"));

        FtmEntity mandate = occupancyOf(dataset, pedro);
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(Set.of("2024-07-16"), mandate.getValues("startDate"));
        assertEquals(Set.of("PfE"), mandate.getValues("politicalGroup"));

        FtmEntity pereira = entity(dataset, dataset.buildEntityId("190713"));
        long pereiraMandates = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(pereira.getId())).count();
        assertEquals(1, pereiraMandates, "seul le mandat en cours : la législature précédente est trop ancienne");
    }

    @Test
    void aLongerRetentionKeepsThePreviousTermToo(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        new AtPepEuroparlParser().convert(rawDir, TODAY, 40, List.of(), dataset);

        FtmEntity pereira = entity(dataset, dataset.buildEntityId("190713"));
        List<FtmEntity> mandates = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(pereira.getId())).toList();
        assertEquals(2, mandates.size());
        assertTrue(mandates.stream().anyMatch(m -> m.getValues("endDate").contains("2024-07-15")
                && m.getValues("status").contains("ended")));
    }

    @Test
    void photosOfKeptMepsComeFromWikimediaCommons(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();
        new AtPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);
        String tsv = "?id\t?image\n\"125001\"\t<http://commons.wikimedia.org/wiki/Special:FilePath/Harald%20Vilimsky.jpg>\n";

        String note = AtPepEuroparlParser.addPhotos(dataset, EuroparlApi.meps(rawDir).keySet(), q -> tsv, rawDir);

        assertTrue(note.startsWith("Photos Wikimedia Commons : 1 sur "), note);
        assertEquals(1, dataset.media().size());
        assertEquals(tsv, Files.readString(rawDir.resolve(AtPepEuroparlParser.PHOTOS_FILE)), "réponse archivée");

        // Wikidata injoignable : la publication continue sans photo
        assertTrue(AtPepEuroparlParser.addPhotos(dataset, List.of("125001"), q -> {
            throw new IOException("HTTP 503");
        }, rawDir).startsWith("Photos non cherchées"));
    }

    @Test
    void downloadsBothListsThenEachDetail(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        List<String> failed = EuroparlApi.download(url -> {
            calls.add(url);
            if (url.equals("detail/190713")) {
                throw new IOException("HTTP 500");
            }
            return bytes(url.startsWith("current") ? "current.json" : url.startsWith("term") ? "term.json"
                    : "mep-125001.json");
        }, "current", "term", "detail/{id}", 0, rawDir);

        assertEquals("current", calls.get(0));
        assertEquals("term", calls.get(1));
        assertEquals(2 + 20, calls.size(), "les deux listes, puis une fiche par député");
        assertEquals(List.of("detail/190713"), failed);

        List<SourceRecord> records = new AtPepEuroparlParser()
                .readSourceRecords(new ParserContext("at_pep_europarl", null, rawDir, null, Map.of()));
        assertTrue(records.stream().anyMatch(r -> r.name().equals("Harald VILIMSKY")
                && r.sourceRef().equals("https://www.europarl.europa.eu/meps/fr/125001")));
    }

    @Test
    void requiresTheUrls(@TempDir Path rawDir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new AtPepEuroparlParser()
                .parse(new ParserContext("at_pep_europarl", null, rawDir, rawDir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("currentUrl"));
    }

    private static FtmEntity occupancyOf(FtmDatasetBuilder dataset, FtmEntity person) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(person.getId())).findFirst().orElseThrow();
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(AtPepEuroparlParser.ID_PREFIX, AtPepEuroparlParser.TOPIC);
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
        try (InputStream in = AtPepEuroparlParserTest.class.getResourceAsStream("/europarl/" + file)) {
            if (in == null) {
                throw new IOException("404 " + file);
            }
            return in.readAllBytes();
        }
    }
}
