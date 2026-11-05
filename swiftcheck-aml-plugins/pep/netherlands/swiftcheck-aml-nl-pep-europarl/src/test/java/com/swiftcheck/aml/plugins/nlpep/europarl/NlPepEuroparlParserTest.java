package com.swiftcheck.aml.plugins.nlpep.europarl;

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
 * Réponses réelles de l’API du Parlement européen (5 novembre 2026) : les 31 députés élus aux Pays-Bas en fonction,
 * dont trois fiches complètes : Anja Hazekamp (2014-2019, 2019-2024 puis depuis 2024), Tineke Strik (2019-2024 puis depuis 2024)
 * et Tom Berendsen (2019 à février 2026, sorti de la liste courante).
 */
class NlPepEuroparlParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final List<String> FILES = List.of("current.json", "term.json", "mep-125023.json", "mep-197772.json", "mep-197778.json");

    @Test
    void keepsEveryCurrentMepWithBirthAndMandateWhenTheRecordIsKnown(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        NlPepEuroparlParser.Report report = new NlPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        assertEquals(31, report.current());
        assertEquals(31, report.withoutDetail().size(), "les 31 autres fiches ne sont pas dans les fixtures");
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity doherty = entity(dataset, dataset.buildEntityId("197772"));
        assertEquals(Set.of("Tineke STRIK"), doherty.getValues("name"));
        assertEquals(Set.of("1961-09-28"), doherty.getValues("birthDate"));
        assertEquals(Set.of("female"), doherty.getValues("gender"));
        assertEquals(Set.of("nl"), doherty.getValues("country"));
        assertEquals(Set.of("role.pep"), doherty.getValues("topics"));

        FtmEntity mandate = occupancyOf(dataset, doherty);
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(Set.of("2024-07-16"), mandate.getValues("startDate"));
    }

    @Test
    void onlyTheCurrentMandateWithTheDefaultRetention(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();
        new NlPepEuroparlParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        FtmEntity boylan = entity(dataset, dataset.buildEntityId("125023"));
        long mandates = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(boylan.getId())).count();
        assertEquals(1, mandates, "la législature 2014-2019 est trop ancienne");
    }

    @Test
    void aLongerRetentionKeepsThePreviousTermToo(@TempDir Path rawDir) throws Exception {
        copy(rawDir, FILES);
        FtmDatasetBuilder dataset = dataset();

        new NlPepEuroparlParser().convert(rawDir, TODAY, 40, List.of(), dataset);

        FtmEntity andrews = entity(dataset, dataset.buildEntityId("197772"));
        List<FtmEntity> mandates = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(andrews.getId())).toList();
        assertEquals(2, mandates.size());
        assertTrue(mandates.stream().anyMatch(m -> m.getValues("endDate").contains("2024-07-15")
                && m.getValues("status").contains("ended")));
    }

    @Test
    void downloadsBothListsThenEachDetail(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        List<String> failed = EuroparlApi.download(url -> {
            calls.add(url);
            if (url.equals("detail/197772")) {
                throw new IOException("HTTP 500");
            }
            return bytes(url.startsWith("current") ? "current.json" : url.startsWith("term") ? "term.json" : "mep-125023.json");
        }, "current", "term", "detail/{id}", 0, rawDir);

        assertEquals("current", calls.get(0));
        assertEquals("term", calls.get(1));
        assertEquals(2 + 34, calls.size(), "les deux listes, puis une fiche par député");
        assertEquals(List.of("detail/197772"), failed);

        List<SourceRecord> records = new NlPepEuroparlParser()
                .readSourceRecords(new ParserContext("nl_pep_europarl", null, rawDir, null, Map.of()));
        assertTrue(records.stream().anyMatch(r -> r.name().equals("Anja HAZEKAMP")
                && r.sourceRef().equals("https://www.europarl.europa.eu/meps/fr/125023")));
    }

    @Test
    void requiresTheUrls(@TempDir Path rawDir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new NlPepEuroparlParser()
                .parse(new ParserContext("nl_pep_europarl", null, rawDir, rawDir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("currentUrl"));
    }

    private static FtmEntity occupancyOf(FtmDatasetBuilder dataset, FtmEntity person) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(person.getId())).findFirst().orElseThrow();
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(NlPepEuroparlParser.ID_PREFIX, NlPepEuroparlParser.TOPIC);
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
        try (InputStream in = NlPepEuroparlParserTest.class.getResourceAsStream("/europarl/" + file)) {
            if (in == null) {
                throw new IOException("404 " + file);
            }
            return in.readAllBytes();
        }
    }
}
