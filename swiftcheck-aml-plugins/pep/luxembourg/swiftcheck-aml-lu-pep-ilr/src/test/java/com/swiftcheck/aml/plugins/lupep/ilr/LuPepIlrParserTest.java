package com.swiftcheck.aml.plugins.lupep.ilr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class LuPepIlrParserTest {

    private final LuPepIlrParser parser = new LuPepIlrParser();

    @Test
    void testExtractOfficialsFromHtml() throws Exception {
        Path htmlPath = Path.of("src/test/resources/equipe.html");
        assertThat(Files.exists(htmlPath)).isTrue();

        String html = Files.readString(htmlPath);
        List<LuPepIlrParser.IlrOfficialRecord> list =
                LuPepIlrParser.extractOfficials(html, LuPepIlrParser.DEFAULT_SOURCE_URL);

        assertThat(list).hasSize(3);

        List<String> names = list.stream().map(m -> m.fullName).toList();
        assertThat(names).contains("Luc Tapella", "Claude Rischette", "Sandra Wietor");

        LuPepIlrParser.IlrOfficialRecord tapella = list.stream()
                .filter(m -> m.fullName.contains("Tapella")).findFirst().orElseThrow();
        assertThat(tapella.roleDescription).isEqualTo("Directeur");
        assertThat(tapella.firstName).isEqualTo("Luc");
        assertThat(tapella.lastName).isEqualTo("Tapella");

        LuPepIlrParser.IlrOfficialRecord rischette = list.stream()
                .filter(m -> m.fullName.contains("Rischette")).findFirst().orElseThrow();
        assertThat(rischette.roleDescription).isEqualTo("Directeur adjoint");

        LuPepIlrParser.IlrOfficialRecord wietor = list.stream()
                .filter(m -> m.fullName.contains("Wietor")).findFirst().orElseThrow();
        assertThat(wietor.roleDescription).isEqualTo("Directrice adjointe");
    }

    @Test
    void testConvertToFtm(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/equipe.html");
        Path outputFile = tempDir.resolve("output.ndjson");

        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-ilr", "role.pep");
        parser.convert(htmlPath, LuPepIlrParser.DEFAULT_SOURCE_URL, null, dataset);
        dataset.writeNdjsonFile(outputFile);

        assertThat(Files.exists(outputFile)).isTrue();
        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        long positionCount = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).count();
        long personCount = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        long occupancyCount = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count();

        // 1 Position officielle unique
        assertThat(positionCount).isEqualTo(1);
        // 3 directeurs de l'ILR
        assertThat(personCount).isEqualTo(3);
        assertThat(occupancyCount).isEqualTo(3);

        // Vérification de conformité stricte FtM sur Occupancy
        for (FtmEntity occ : dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList()) {
            assertThat(occ.getValues("role")).as("Occupancy ne doit pas avoir la propriété 'role'").isEmpty();
            assertThat(occ.getValues("status")).isNotEmpty();
            assertThat(occ.getValues("holder")).isNotEmpty();
            assertThat(occ.getValues("post")).isNotEmpty();
        }

        // Vérification des entités Person
        for (FtmEntity person : dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).toList()) {
            assertThat(person.getValues("country")).contains("lu");
            assertThat(person.getValues("name")).isNotEmpty();
            assertThat(person.getValues("sourceUrl")).contains(LuPepIlrParser.DEFAULT_SOURCE_URL);
        }
    }

    @Test
    void testParseContext(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/equipe.html");
        Path outputFile = tempDir.resolve("result.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_ilr",
                htmlPath,
                rawDir,
                outputFile,
                Map.of(
                        "sourceUrl", LuPepIlrParser.DEFAULT_SOURCE_URL,
                        "yearsAfterEnd", "5"
                )
        );

        ParserResult res = parser.parse(ctx);
        assertThat(res).isNotNull();
        assertThat(res.entityCount()).isEqualTo(1 + 3 + 3); // 1 Position + 3 Persons + 3 Occupancies
        assertThat(Files.exists(outputFile)).isTrue();
    }

    @Test
    void testReadSourceRecords(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/equipe.html");
        Path outputFile = tempDir.resolve("dummy.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_ilr",
                htmlPath,
                rawDir,
                outputFile,
                Map.of("sourceUrl", LuPepIlrParser.DEFAULT_SOURCE_URL)
        );

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(3);
        for (SourceRecord r : records) {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.name()).isNotEmpty();
        }
    }
}
