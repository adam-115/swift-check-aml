package com.swiftcheck.aml.plugins.depep.bundestag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

class DePepBundestagParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    @Test
    void testConvertXml(@TempDir Path tempDir) throws Exception {
        Path xmlFile = tempDir.resolve("test_mdb.xml");
        try (InputStream in = getClass().getResourceAsStream("/test_mdb.xml")) {
            assertNotNull(in);
            Files.copy(in, xmlFile);
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepBundestagParser.ID_PREFIX, DePepBundestagParser.TOPIC);
        DePepBundestagParser.Report report = new DePepBundestagParser().convert(xmlFile, TODAY, 12, dataset);

        assertEquals(3, report.totalMdb());
        assertEquals(1, report.current(), "Friedrich Merz est en fonction");
        assertEquals(1, report.formerKept(), "Erika Mustermann sortie en juin 2026 : moins de 12 mois");
        assertEquals(1, report.formerSkipped(), "Hans Altmann sorti en 1969 : écarté");

        FtmEntity merz = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getId().endsWith("11002735"))
                .findFirst().orElseThrow();
        assertEquals(Set.of("Friedrich Merz"), merz.getValues("name"));
        assertEquals(Set.of("1955-11-11"), merz.getValues("birthDate"));
        assertEquals(Set.of("Brilon"), merz.getValues("birthPlace"));
        assertEquals(Set.of("male"), merz.getValues("gender"));
        assertEquals(Set.of("CDU"), merz.getValues("political"));
        assertEquals(Set.of("de"), merz.getValues("country"));

        List<FtmEntity> occupancies = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertEquals(2, occupancies.size());

        ParserContext ctx = new ParserContext("de_pep_bundestag", xmlFile, tempDir, null, Map.of("monthsAfterEnd", "12"));
        List<SourceRecord> records = new DePepBundestagParser().readSourceRecords(ctx);
        assertEquals(2, records.size());
    }

    @Test
    void aLongHistoryKeepsLivingFormerMembersButNotTheDeceased(@TempDir Path tempDir) throws Exception {
        String xml;
        try (InputStream in = getClass().getResourceAsStream("/test_mdb.xml")) {
            assertNotNull(in);
            xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Path alive = tempDir.resolve("alive.xml");
        Files.writeString(alive, xml);
        int altmann = xml.indexOf("Altmann");
        int bio = xml.indexOf("<BIOGRAFISCHE_ANGABEN>", altmann) + "<BIOGRAFISCHE_ANGABEN>".length();
        Path deceased = tempDir.resolve("deceased.xml");
        Files.writeString(deceased, xml.substring(0, bio) + "<STERBEDATUM>12.03.1990</STERBEDATUM>" + xml.substring(bio));

        DePepBundestagParser.Report living = new DePepBundestagParser().convert(alive, TODAY, 1200,
                new FtmDatasetBuilder(DePepBundestagParser.ID_PREFIX, DePepBundestagParser.TOPIC));
        DePepBundestagParser.Report dead = new DePepBundestagParser().convert(deceased, TODAY, 1200,
                new FtmDatasetBuilder(DePepBundestagParser.ID_PREFIX, DePepBundestagParser.TOPIC));

        assertEquals(2, living.formerKept(), "sorti en 1969 mais vivant : repris dans un historique de 100 ans");
        assertEquals(1, dead.formerKept(), "décédé : jamais repris");
        assertEquals(1, dead.formerSkipped());
    }
}
