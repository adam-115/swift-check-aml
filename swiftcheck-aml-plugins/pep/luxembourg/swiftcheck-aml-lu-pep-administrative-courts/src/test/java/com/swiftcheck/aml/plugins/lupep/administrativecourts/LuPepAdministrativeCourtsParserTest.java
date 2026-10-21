package com.swiftcheck.aml.plugins.lupep.administrativecourts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class LuPepAdministrativeCourtsParserTest {

    private final LuPepAdministrativeCourtsParser parser = new LuPepAdministrativeCourtsParser();

    @Test
    void testExtractFromPdf() throws Exception {
        Path pdfPath = Path.of("src/test/resources/Cour.pdf");
        assertThat(Files.exists(pdfPath)).isTrue();

        List<LuPepAdministrativeCourtsParser.MagistrateRecord> list =
                LuPepAdministrativeCourtsParser.extractFromPdf(pdfPath);

        assertThat(list).isNotEmpty();
        // Vérifier la présence des membres de la Cour administrative
        List<String> names = list.stream().map(m -> m.fullName).toList();
        assertThat(names).anyMatch(n -> n.contains("DELAPORTE"));
        assertThat(names).anyMatch(n -> n.contains("SCHROEDER"));
        assertThat(names).anyMatch(n -> n.contains("BRAUN"));
        assertThat(names).anyMatch(n -> n.contains("CASTEGNARO"));
    }

    @Test
    void testExtractFromHtml() throws Exception {
        Path htmlPath = Path.of("src/test/resources/cour_administrative.html");
        assertThat(Files.exists(htmlPath)).isTrue();

        String html = Files.readString(htmlPath);
        List<LuPepAdministrativeCourtsParser.MagistrateRecord> list =
                LuPepAdministrativeCourtsParser.extractFromHtml(html);

        assertThat(list).hasSize(6);
        List<String> names = list.stream().map(m -> m.fullName).toList();
        assertThat(names).anyMatch(n -> n.contains("DELAPORTE"));
        assertThat(names).anyMatch(n -> n.contains("SCHROEDER"));
        assertThat(names).anyMatch(n -> n.contains("SPIELMANN"));
    }

    @Test
    void testConvertPdfToFtm(@TempDir Path tempDir) throws Exception {
        Path pdfPath = Path.of("src/test/resources/Cour.pdf");
        Path outputFile = tempDir.resolve("output.ndjson");

        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-administrative-courts", "role.pep");
        parser.convert(pdfPath, "https://justice.public.lu/downloads.jurad/Cour.pdf", null, dataset);
        dataset.writeNdjsonFile(outputFile);

        assertThat(Files.exists(outputFile)).isTrue();
        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        long positionCount = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).count();
        long personCount = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        long occupancyCount = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count();

        // Une seule Position officielle pour éviter tout doublon fusionné
        assertThat(positionCount).isEqualTo(1);
        assertThat(personCount).isGreaterThanOrEqualTo(5);
        assertThat(occupancyCount).isEqualTo(personCount);

        // Vérifier qu'aucun Occupancy n'a la propriété non-valide role
        for (FtmEntity occ : dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList()) {
            assertThat(occ.getValues("role")).isEmpty();
            assertThat(occ.getValues("status")).isNotEmpty();
            assertThat(occ.getValues("holder")).isNotEmpty();
            assertThat(occ.getValues("post")).isNotEmpty();
        }
    }

    @Test
    void testParseContext(@TempDir Path tempDir) throws Exception {
        Path pdfPath = Path.of("src/test/resources/Cour.pdf");
        Path outputFile = tempDir.resolve("out.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_administrative_courts",
                pdfPath,
                rawDir,
                outputFile,
                Map.of("yearsAfterEnd", "5")
        );

        ParserResult res = parser.parse(ctx);
        assertThat(res).isNotNull();
        assertThat(res.entityCount()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void testReadSourceRecords(@TempDir Path tempDir) throws Exception {
        Path pdfPath = Path.of("src/test/resources/Cour.pdf");
        Path outputFile = tempDir.resolve("records.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_administrative_courts",
                pdfPath,
                rawDir,
                outputFile,
                Map.of()
        );

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).isNotEmpty();
        assertThat(records).allMatch(r -> "Person".equals(r.schema()));
        assertThat(records).anyMatch(r -> r.name().contains("DELAPORTE"));
    }
}
