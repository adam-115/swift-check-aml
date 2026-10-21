package com.swiftcheck.aml.plugins.lupep.diplomats;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class LuPepDiplomatsParserTest {

    @TempDir
    Path tempDir;

    private LuPepDiplomatsParser parser;
    private Path sampleMainHtml;

    @BeforeEach
    void setUp() {
        parser = new LuPepDiplomatsParser();
        sampleMainHtml = Path.of("src/test/resources/missions-diplomatiques.html");
    }

    @Test
    void testParseEmbassyTeamHtmlIndividualSites() throws Exception {
        Path embassiesDir = Path.of("src/test/resources/embassies");

        // 1. Paris
        String parisHtml = Files.readString(embassiesDir.resolve("paris.html"));
        LuPepDiplomatsParser.DiplomatRecord paris = LuPepDiplomatsParser.parseEmbassyTeamHtml("paris", parisHtml, "https://paris.mae.lu");
        assertThat(paris).isNotNull();
        assertThat(paris.fullName).containsIgnoringCase("Jean Olinger");
        assertThat(paris.roleDescription).containsIgnoringCase("Ambassadeur");

        // 2. Londres
        String londonHtml = Files.readString(embassiesDir.resolve("londres.html"));
        LuPepDiplomatsParser.DiplomatRecord london = LuPepDiplomatsParser.parseEmbassyTeamHtml("londres", londonHtml, "https://londres.mae.lu");
        assertThat(london).isNotNull();
        assertThat(london.fullName).containsIgnoringCase("Georges Friden");
        assertThat(london.roleDescription).containsIgnoringCase("Ambassador");

        // 3. Berlin
        String berlinHtml = Files.readString(embassiesDir.resolve("berlin.html"));
        LuPepDiplomatsParser.DiplomatRecord berlin = LuPepDiplomatsParser.parseEmbassyTeamHtml("berlin", berlinHtml, "https://berlin.mae.lu");
        assertThat(berlin).isNotNull();
        assertThat(berlin.fullName).containsIgnoringCase("Sylvie Lucas");
        assertThat(berlin.roleDescription).containsIgnoringCase("Botschafterin");

        // 4. Abu Dhabi (Chargée d'affaires)
        String adHtml = Files.readString(embassiesDir.resolve("abudhabi.html"));
        LuPepDiplomatsParser.DiplomatRecord ad = LuPepDiplomatsParser.parseEmbassyTeamHtml("abudhabi", adHtml, "https://abudhabi.mae.lu");
        assertThat(ad).isNotNull();
        assertThat(ad.fullName).containsIgnoringCase("Marie-Anne Marx");
        assertThat(ad.roleDescription).containsIgnoringCase("affaires");

        // 5. Tokyo
        String tokyoHtml = Files.readString(embassiesDir.resolve("tokyo.html"));
        LuPepDiplomatsParser.DiplomatRecord tokyo = LuPepDiplomatsParser.parseEmbassyTeamHtml("tokyo", tokyoHtml, "https://tokyo.mae.lu");
        assertThat(tokyo).isNotNull();
        assertThat(tokyo.fullName).containsIgnoringCase("Michel Leesch");
        assertThat(tokyo.roleDescription).containsIgnoringCase("Ambassadeur");
    }

    @Test
    void testConvertOfflineWithLocalEmbassies() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-diplomats", "role.pep");
        parser.convert(sampleMainHtml, LuPepDiplomatsParser.DEFAULT_SOURCE_URL, null, dataset);

        List<FtmEntity> entities = new ArrayList<>(dataset.entities());
        assertThat(entities).isNotEmpty();

        // 1. Position unique
        List<FtmEntity> positions = entities.stream().filter(e -> "Position".equals(e.getSchema())).toList();
        assertThat(positions).hasSize(1);
        FtmEntity pos = positions.get(0);
        assertThat(pos.getValues("name")).contains("Ambassadeur ou chargé d'affaires du Grand-Duché de Luxembourg");
        assertThat(pos.getValues("country")).contains("lu");
        assertThat(pos.getValues("organization")).contains("Ministère des Affaires étrangères et européennes");
        assertThat(pos.getValues("topics")).contains("gov.national", "gov.diplomatic");

        // 2. Personnes
        List<FtmEntity> persons = entities.stream().filter(e -> "Person".equals(e.getSchema())).toList();
        assertThat(persons).isNotEmpty();
        for (FtmEntity p : persons) {
            assertThat(p.getValues("name")).isNotEmpty();
            assertThat(p.getValues("country")).contains("lu");
            assertThat(p.getValues("sourceUrl")).isNotEmpty();
        }

        // 3. Occupancies
        List<FtmEntity> occupancies = entities.stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertThat(occupancies).isNotEmpty();
        for (FtmEntity occ : occupancies) {
            assertThat(occ.getValues("holder")).isNotEmpty();
            assertThat(occ.getValues("post")).contains(pos.getId());
            assertThat(occ.getValues("status")).contains("current");
            // Vérification stricte : aucun champ 'role' invalide dans Occupancy
            assertThat(occ.getValues("role")).isEmpty();
        }
    }

    @Test
    void testParseFullPipeline() throws Exception {
        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_diplomats",
                sampleMainHtml,
                rawDir,
                outputFile,
                Map.of("sourceUrl", LuPepDiplomatsParser.DEFAULT_SOURCE_URL)
        );

        ParserResult result = parser.parse(ctx);
        assertThat(result).isNotNull();
        assertThat(result.entityCount()).isGreaterThan(0);
        assertThat(Files.exists(outputFile)).isTrue();
        assertThat(Files.size(outputFile)).isGreaterThan(0);
    }

    @Test
    void testReadSourceRecords() throws Exception {
        Path outputFile = tempDir.resolve("dummy.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_diplomats",
                sampleMainHtml,
                rawDir,
                outputFile,
                Map.of("sourceUrl", LuPepDiplomatsParser.DEFAULT_SOURCE_URL)
        );

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).isNotEmpty();
        assertThat(records).allMatch(r -> "Person".equals(r.schema()));
    }

    @Test
    void testResolveSourceUrl() {
        ParserContext ctx = new ParserContext(
                "lu_pep_diplomats",
                null,
                null,
                null,
                Map.of("url", "https://mae.gouvernement.lu/custom.html")
        );
        String resolved = LuPepDiplomatsParser.resolveSourceUrl(ctx);
        assertThat(resolved).isEqualTo("https://mae.gouvernement.lu/custom.html");
    }
}
