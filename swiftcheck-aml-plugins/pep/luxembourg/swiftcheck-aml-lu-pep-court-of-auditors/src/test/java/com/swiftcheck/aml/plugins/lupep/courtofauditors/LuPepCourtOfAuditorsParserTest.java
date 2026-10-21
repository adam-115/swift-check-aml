package com.swiftcheck.aml.plugins.lupep.courtofauditors;

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

class LuPepCourtOfAuditorsParserTest {

    private final LuPepCourtOfAuditorsParser parser = new LuPepCourtOfAuditorsParser();

    @Test
    void testExtractFromCollegeHtml() throws Exception {
        Path htmlPath = Path.of("src/test/resources/college.html");
        assertThat(Files.exists(htmlPath)).isTrue();

        String html = Files.readString(htmlPath);
        List<LuPepCourtOfAuditorsParser.AuditorRecord> list =
                LuPepCourtOfAuditorsParser.extractAuditors(html, "https://cour-des-comptes.public.lu/fr/cour/college.html");

        assertThat(list).hasSize(5);

        List<String> names = list.stream().map(m -> m.fullName).toList();
        assertThat(names).contains(
                "Marc Gengler",
                "Patrick Graffé",
                "Georges Ramos",
                "Marie-Jeanne Conter",
                "Patrick Weymerskirch"
        );

        // Vérifier les fonctions
        LuPepCourtOfAuditorsParser.AuditorRecord gengler = list.stream()
                .filter(m -> m.fullName.contains("Gengler")).findFirst().orElseThrow();
        assertThat(gengler.roleDescription).isEqualTo("Président");
        assertThat(gengler.profileUrl).contains("gengler.html");

        LuPepCourtOfAuditorsParser.AuditorRecord graffe = list.stream()
                .filter(m -> m.fullName.contains("Graffé")).findFirst().orElseThrow();
        assertThat(graffe.roleDescription).isEqualTo("Vice-Président");
    }

    @Test
    void testExtractFromGenglerBioHtml() throws Exception {
        Path htmlPath = Path.of("src/test/resources/gengler.html");
        assertThat(Files.exists(htmlPath)).isTrue();

        String html = Files.readString(htmlPath);
        List<LuPepCourtOfAuditorsParser.AuditorRecord> list =
                LuPepCourtOfAuditorsParser.extractAuditors(html, "https://cour-des-comptes.public.lu/fr/cour/college/gengler.html");

        assertThat(list).hasSize(1);
        LuPepCourtOfAuditorsParser.AuditorRecord rec = list.get(0);
        assertThat(rec.fullName).contains("Marc Gengler");
        assertThat(rec.birthDate).isEqualTo("1966-12-12");
    }

    @Test
    void testConvertToFtm(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/college.html");
        Path outputFile = tempDir.resolve("output.ndjson");

        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-court-of-auditors", "role.pep");
        parser.convert(htmlPath, "https://cour-des-comptes.public.lu/fr/cour/college.html", null, dataset);
        dataset.writeNdjsonFile(outputFile);

        assertThat(Files.exists(outputFile)).isTrue();
        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        long positionCount = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).count();
        long personCount = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        long occupancyCount = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count();

        // 1 Position officielle unique
        assertThat(positionCount).isEqualTo(1);
        // 5 membres du collège
        assertThat(personCount).isEqualTo(5);
        assertThat(occupancyCount).isEqualTo(5);

        // Vérifier l'absence de propriété "role" invalide sur Occupancy
        for (FtmEntity occ : dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList()) {
            assertThat(occ.getValues("role")).isEmpty();
            assertThat(occ.getValues("status")).isNotEmpty();
            assertThat(occ.getValues("holder")).isNotEmpty();
            assertThat(occ.getValues("post")).isNotEmpty();
        }
    }

    @Test
    void testParseContext(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/college.html");
        Path outputFile = tempDir.resolve("out.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_court_of_auditors",
                htmlPath,
                rawDir,
                outputFile,
                Map.of("yearsAfterEnd", "5")
        );

        ParserResult res = parser.parse(ctx);
        assertThat(res).isNotNull();
        assertThat(res.entityCount()).isEqualTo(11); // 1 Position + 5 Person + 5 Occupancy
    }

    @Test
    void testReadSourceRecords(@TempDir Path tempDir) throws Exception {
        Path htmlPath = Path.of("src/test/resources/college.html");
        Path outputFile = tempDir.resolve("records.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_court_of_auditors",
                htmlPath,
                rawDir,
                outputFile,
                Map.of()
        );

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(5);
        assertThat(records).allMatch(r -> "Person".equals(r.schema()));
        assertThat(records).anyMatch(r -> r.name().contains("Gengler"));
    }
}
