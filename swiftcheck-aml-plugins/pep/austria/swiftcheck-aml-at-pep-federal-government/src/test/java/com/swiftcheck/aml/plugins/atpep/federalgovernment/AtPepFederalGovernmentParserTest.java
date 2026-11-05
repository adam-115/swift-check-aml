package com.swiftcheck.aml.plugins.atpep.federalgovernment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page réelle du Bundeskanzleramt (bundeskanzleramt.gv.at, 5 novembre 2026). */
class AtPepFederalGovernmentParserTest {

    private static final Path PAGE = Path.of("src/test/resources/bundesregierung.html");

    @Test
    void readsTheChancellorTheMinistersAndTheStateSecretaries() throws Exception {
        List<AtPepFederalGovernmentParser.Member> members =
                AtPepFederalGovernmentParser.read(PAGE, AtPepFederalGovernmentParser.DEFAULT_PAGE_URL);

        assertEquals(21, members.size(), "1 chancelier, 1 vice-chancelier, 12 ministres, 7 secrétaires d'État");
        AtPepFederalGovernmentParser.Member chancellor = members.get(0);
        assertEquals("Christian Stocker", chancellor.name(), "le titre « Dr. » n'est pas dans le nom");
        assertEquals("Bundeskanzler", chancellor.role());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Andreas Babler")
                && m.role().startsWith("Vizekanzler") && m.role().contains("Bundesminister für Wohnen")));
        assertEquals(7, members.stream().filter(m -> m.role().startsWith("Staatssekret")).count());
    }

    @Test
    void dropsAcademicTitlesAndKeepsAFormerNameAsAnAlias() throws Exception {
        List<AtPepFederalGovernmentParser.Member> members =
                AtPepFederalGovernmentParser.read(PAGE, AtPepFederalGovernmentParser.DEFAULT_PAGE_URL);

        AtPepFederalGovernmentParser.Member bauer = members.stream().filter(m -> m.name().equals("Claudia Bauer"))
                .findFirst().orElseThrow();
        assertEquals("Claudia Plakolm", bauer.formerName());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Beate Meinl-Reisinger")),
                "« Mag.a » et « MES » sont des titres");
        assertTrue(members.stream().noneMatch(m -> m.name().contains("Mag") || m.name().contains("MSc")));
    }

    @Test
    void writesPersonsPositionsAndCurrentMandates() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(AtPepFederalGovernmentParser.ID_PREFIX,
                AtPepFederalGovernmentParser.TOPIC);

        new AtPepFederalGovernmentParser().convert(
                AtPepFederalGovernmentParser.read(PAGE, AtPepFederalGovernmentParser.DEFAULT_PAGE_URL), dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        FtmEntity stocker = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Christian Stocker")).findFirst().orElseThrow();
        assertEquals(Set.of("at"), stocker.getValues("nationality"));
        assertEquals(Set.of("role.pep"), stocker.getValues("topics"));
        assertTrue(dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema()))
                .allMatch(e -> e.getValues("status").contains("current")));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("topics").contains("gov.head")));
    }

    @Test
    void sourceRecordsGiveTheIdYenteMustKnow(@TempDir Path dir) throws Exception {
        List<SourceRecord> records = new AtPepFederalGovernmentParser().readSourceRecords(
                new ParserContext("at_pep_federal_government", PAGE, dir, null, Map.of()));

        assertEquals(21, records.size());
        assertEquals("Christian Stocker", records.get(0).name());
    }

    @Test
    void refusesAPageThatIsNotTheGovernmentList() {
        assertThrows(IllegalStateException.class,
                () -> AtPepFederalGovernmentParser.read("<html><body>Fehler 404</body></html>", "x"));
    }

    @Test
    void explainsWhatToConfigureWithoutASourceFile(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new AtPepFederalGovernmentParser()
                .parse(new ParserContext("at_pep_federal_government", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("HTTP_FILE"));
    }
}
