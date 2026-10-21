package com.swiftcheck.aml.plugins.lupep.centralbank;

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

class LuPepCentralBankParserTest {

    private final LuPepCentralBankParser parser = new LuPepCentralBankParser();

    @Test
    void testExtractOfficialsFromHtml() throws Exception {
        Path htmlPath = Path.of("src/test/resources/organes_statutaires.html");
        assertThat(Files.exists(htmlPath)).isTrue();

        String html = Files.readString(htmlPath);
        List<LuPepCentralBankParser.BankOfficialRecord> list =
                LuPepCentralBankParser.extractOfficials(html, LuPepCentralBankParser.DEFAULT_SOURCE_URL);

        assertThat(list).isNotEmpty();

        // Direction members
        List<LuPepCentralBankParser.BankOfficialRecord> direction = list.stream()
                .filter(m -> "DIRECTION".equals(m.organType))
                .toList();
        assertThat(direction).hasSize(3);

        List<String> dirNames = direction.stream().map(m -> m.fullName).toList();
        assertThat(dirNames).anyMatch(n -> n.contains("Reinesch"));
        assertThat(dirNames).anyMatch(n -> n.contains("Weyland"));
        assertThat(dirNames).anyMatch(n -> n.contains("Weber"));

        // Conseil members
        List<LuPepCentralBankParser.BankOfficialRecord> council = list.stream()
                .filter(m -> "COUNCIL".equals(m.organType))
                .toList();
        assertThat(council).hasSize(9);

        List<String> councilNames = council.stream().map(m -> m.fullName).toList();
        assertThat(councilNames).anyMatch(n -> n.contains("Reinesch"));
        assertThat(councilNames).anyMatch(n -> n.contains("Detaille"));
        assertThat(councilNames).anyMatch(n -> n.contains("Muller"));
        assertThat(councilNames).anyMatch(n -> n.contains("Reicherts"));
        assertThat(councilNames).anyMatch(n -> n.contains("Weber"));
        assertThat(councilNames).anyMatch(n -> n.contains("Welter"));
        assertThat(councilNames).anyMatch(n -> n.contains("Weyland"));
        assertThat(councilNames).anyMatch(n -> n.contains("Wirion"));
        assertThat(councilNames).anyMatch(n -> n.contains("Wurth"));
    }

    @Test
    void testConvertToFtm(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/organes_statutaires.html");
        Path outputFile = tempDir.resolve("output.ndjson");

        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-central-bank", "role.pep");
        parser.convert(htmlPath, LuPepCentralBankParser.DEFAULT_SOURCE_URL, null, dataset);
        dataset.writeNdjsonFile(outputFile);

        assertThat(Files.exists(outputFile)).isTrue();
        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        long positionCount = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).count();
        long personCount = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        long occupancyCount = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count();

        // 2 positions officielles (Direction et Conseil)
        assertThat(positionCount).isEqualTo(2);

        // 9 personnes physiques distinctes (les 3 dirigeants siègent aussi au Conseil sans duplication d'entité Person)
        assertThat(personCount).isEqualTo(9);

        // 12 mandats au total (3 Direction + 9 Conseil)
        assertThat(occupancyCount).isEqualTo(12);

        // Vérification de conformité stricte FtM sur Occupancy
        for (FtmEntity occ : dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList()) {
            assertThat(occ.getValues("role")).as("Occupancy ne doit pas avoir la propriété 'role'").isEmpty();
            assertThat(occ.getValues("status")).isNotEmpty();
            assertThat(occ.getValues("holder")).isNotEmpty();
            assertThat(occ.getValues("post")).isNotEmpty();
        }

        // Vérification que les personnes ont bien country = "lu"
        for (FtmEntity person : dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).toList()) {
            assertThat(person.getValues("country")).contains("lu");
            assertThat(person.getValues("name")).isNotEmpty();
        }
    }

    @Test
    void testParseContext(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/organes_statutaires.html");
        Path outputFile = tempDir.resolve("result.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_central_bank",
                htmlPath,
                rawDir,
                outputFile,
                Map.of(
                        "sourceUrl", LuPepCentralBankParser.DEFAULT_SOURCE_URL,
                        "yearsAfterEnd", "5"
                )
        );

        ParserResult res = parser.parse(ctx);
        assertThat(res).isNotNull();
        assertThat(res.entityCount()).isEqualTo(2 + 9 + 12); // 2 Positions + 9 Persons + 12 Occupancies
        assertThat(Files.exists(outputFile)).isTrue();
    }

    @Test
    void testReadSourceRecords(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/organes_statutaires.html");
        Path outputFile = tempDir.resolve("dummy.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_central_bank",
                htmlPath,
                rawDir,
                outputFile,
                Map.of("sourceUrl", LuPepCentralBankParser.DEFAULT_SOURCE_URL)
        );

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(9);
        for (SourceRecord r : records) {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.name()).isNotEmpty();
        }
    }
}
