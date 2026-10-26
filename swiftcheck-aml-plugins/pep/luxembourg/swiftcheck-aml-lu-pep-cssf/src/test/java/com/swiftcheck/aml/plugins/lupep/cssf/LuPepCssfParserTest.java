package com.swiftcheck.aml.plugins.lupep.cssf;

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

class LuPepCssfParserTest {

    private final LuPepCssfParser parser = new LuPepCssfParser();

    /** PDF officiel « Liste des membres de la Direction » (cssf.lu, version du 1er septembre 2026). */
    private static final Path PDF = Path.of("src/test/resources/LM_DIRECTION.pdf");

    @Test
    void testExtractOfficialsFromPdf() throws Exception {
        assertThat(LuPepCssfParser.isPdfFile(PDF)).isTrue();
        assertThat(LuPepCssfParser.isPdfFile(Path.of("src/test/resources/gouvernance.html"))).isFalse();

        List<LuPepCssfParser.CssfOfficialRecord> list = LuPepCssfParser.extractFromPdf(PDF);

        assertThat(list).extracting(m -> m.fullName + " | " + m.roleDescription).containsExactlyInAnyOrder(
                "Claude Marx | Directeur général",
                "Jean-Pierre Faber | Directeur",
                "Marco Zwick | Directeur",
                "Claude Wampach | Directeur",
                "Pascale Toussing | Directeur");

        LuPepCssfParser.CssfOfficialRecord faber = list.stream()
                .filter(m -> m.fullName.contains("Faber")).findFirst().orElseThrow();
        assertThat(faber.firstName).isEqualTo("Jean-Pierre");
        assertThat(faber.lastName).isEqualTo("Faber");
    }

    @Test
    void testParseFromPdf(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("result.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext("lu_pep_cssf", PDF, rawDir, outputFile,
                Map.of("sourceLocation", LuPepCssfParser.DEFAULT_SOURCE_URL, "yearsAfterEnd", "5"));

        ParserResult res = parser.parse(ctx);
        assertThat(res.entityCount()).isEqualTo(1 + 5 + 5); // 1 Position + 5 Persons + 5 Occupancies
        assertThat(Files.readString(outputFile))
                .contains("Claude Marx")
                .contains("Directeur général")
                .contains(LuPepCssfParser.DEFAULT_SOURCE_URL);
    }

    @Test
    void testCapitalizeName() {
        assertThat(LuPepCssfParser.capitalizeName("MARX")).isEqualTo("Marx");
        assertThat(LuPepCssfParser.capitalizeName("WEBER-MÜLLER")).isEqualTo("Weber-Müller");
        assertThat(LuPepCssfParser.capitalizeName("DE LA FONTAINE")).isEqualTo("De La Fontaine");
    }

    @Test
    void testParseDirectionTextLineLayouts() {
        // Nom et fonction sur la même ligne, ou sur deux lignes
        String text = "Direction/Executive Board/Vorstand\nClaude MARX\nDirecteur général\nJean-Pierre FABER Directeur\n";
        assertThat(LuPepCssfParser.parseDirectionText(text))
                .extracting(m -> m.fullName + " | " + m.roleDescription)
                .containsExactly("Claude Marx | Directeur général", "Jean-Pierre Faber | Directeur");
    }

    @Test
    void testExtractOfficialsFromHtml() throws Exception {
        Path htmlPath = Path.of("src/test/resources/gouvernance.html");
        assertThat(Files.exists(htmlPath)).isTrue();

        String html = Files.readString(htmlPath);
        List<LuPepCssfParser.CssfOfficialRecord> list =
                LuPepCssfParser.extractOfficials(html, LuPepCssfParser.DEFAULT_SOURCE_URL);

        assertThat(list).hasSize(5);

        List<String> names = list.stream().map(m -> m.fullName).toList();
        assertThat(names).contains(
                "Claude Marx",
                "Jean-Pierre Faber",
                "Marco Zwick",
                "Claude Wampach",
                "Pascale Toussing"
        );

        LuPepCssfParser.CssfOfficialRecord marx = list.stream()
                .filter(m -> m.fullName.contains("Marx")).findFirst().orElseThrow();
        assertThat(marx.roleDescription).isEqualTo("Directeur général");
        assertThat(marx.firstName).isEqualTo("Claude");
        assertThat(marx.lastName).isEqualTo("Marx");

        LuPepCssfParser.CssfOfficialRecord toussing = list.stream()
                .filter(m -> m.fullName.contains("Toussing")).findFirst().orElseThrow();
        assertThat(toussing.roleDescription).isEqualTo("Directeur");
    }

    @Test
    void testConvertToFtm(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/gouvernance.html");
        Path outputFile = tempDir.resolve("output.ndjson");

        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-cssf", "role.pep");
        parser.convert(htmlPath, LuPepCssfParser.DEFAULT_SOURCE_URL, null, dataset);
        dataset.writeNdjsonFile(outputFile);

        assertThat(Files.exists(outputFile)).isTrue();
        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        long positionCount = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).count();
        long personCount = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        long occupancyCount = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count();

        // 1 Position officielle unique
        assertThat(positionCount).isEqualTo(1);
        // 5 membres de la direction de la CSSF
        assertThat(personCount).isEqualTo(5);
        assertThat(occupancyCount).isEqualTo(5);

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
            assertThat(person.getValues("sourceUrl")).contains(LuPepCssfParser.DEFAULT_SOURCE_URL);
        }
    }

    @Test
    void testParseContext(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/gouvernance.html");
        Path outputFile = tempDir.resolve("result.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_cssf",
                htmlPath,
                rawDir,
                outputFile,
                Map.of(
                        "sourceUrl", LuPepCssfParser.DEFAULT_SOURCE_URL,
                        "yearsAfterEnd", "5"
                )
        );

        ParserResult res = parser.parse(ctx);
        assertThat(res).isNotNull();
        assertThat(res.entityCount()).isEqualTo(1 + 5 + 5); // 1 Position + 5 Persons + 5 Occupancies
        assertThat(Files.exists(outputFile)).isTrue();
    }

    @Test
    void testReadSourceRecords(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/gouvernance.html");
        Path outputFile = tempDir.resolve("dummy.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_cssf",
                htmlPath,
                rawDir,
                outputFile,
                Map.of("sourceUrl", LuPepCssfParser.DEFAULT_SOURCE_URL)
        );

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(5);
        for (SourceRecord r : records) {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.name()).isNotEmpty();
        }
    }
}
