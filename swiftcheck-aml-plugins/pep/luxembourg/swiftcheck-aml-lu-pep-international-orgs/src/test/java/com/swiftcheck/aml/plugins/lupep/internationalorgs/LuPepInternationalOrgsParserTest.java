package com.swiftcheck.aml.plugins.lupep.internationalorgs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.lupep.internationalorgs.LuPepInternationalOrgsParser.InternationalLeaderRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class LuPepInternationalOrgsParserTest {

    @TempDir
    Path tempDir;

    private LuPepInternationalOrgsParser parser;
    private String testHtml;

    @BeforeEach
    void setUp() throws Exception {
        parser = new LuPepInternationalOrgsParser();
        try (InputStream is = getClass().getResourceAsStream("/efta_court.html")) {
            if (is != null) {
                testHtml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } else {
                testHtml = "<html><body>"
                        + "<h2>Páll Hreinsson, President</h2>"
                        + "<p>Born: 20 February 1963, in Reykjavík, Iceland.</p>"
                        + "<h2>Bernd Hammermann, Judge</h2>"
                        + "<p>Born: 5 January 1964, citizen of Triesen, Principality of Liechtenstein</p>"
                        + "<h2>Michael Reiertsen, Judge</h2>"
                        + "<p>Born: 20 February 1976 in Tromsø, Norway</p>"
                        + "<h2>Ólafur Jóhannes Einarsson, Registrar</h2>"
                        + "<p>Born: 27 March 1974, in Reykjavik, Iceland</p>"
                        + "</body></html>";
            }
        }
    }

    @Test
    @DisplayName("Devrait extraire au moins 15 dirigeants internationaux (Cour AELE, Eurocontrol, ESO)")
    void testExtractLeaders() {
        List<InternationalLeaderRecord> leaders = parser.extractLeaders(testHtml, LocalDate.now(), LocalDate.now().minusYears(5));

        leaders.forEach(l -> System.out.println("DEBUG LEADER: name=[" + l.name + "] role=[" + l.role + "] org=[" + l.organization + "]"));
        assertThat(leaders).isNotEmpty();
        assertThat(leaders.size()).isGreaterThanOrEqualTo(15);

        // Membres de la Cour AELE
        assertThat(leaders).anyMatch(l -> l.name.contains("Hreinsson") && l.role.contains("President"));
        assertThat(leaders).anyMatch(l -> l.name.contains("Hammermann") && l.role.contains("Judge"));
        assertThat(leaders).anyMatch(l -> l.name.contains("Reiertsen") && l.role.contains("Judge"));
        assertThat(leaders).anyMatch(l -> l.name.contains("Einarsson") && l.role.contains("Registrar"));

        // Juges ad hoc AELE
        assertThat(leaders).anyMatch(l -> l.name.contains("Ása Ólafsdóttir") && l.role.contains("ad hoc"));
        assertThat(leaders).anyMatch(l -> l.name.contains("Ola Mestad") && l.role.contains("ad hoc"));

        // Office statistique AELE (ESO)
        assertThat(leaders).anyMatch(l -> l.name.contains("Michel Di Pietro"));

        // Eurocontrol Luxembourg
        assertThat(leaders).anyMatch(l -> l.name.contains("Hendrik Dermont") && l.organization.contains("EUROCONTROL"));
    }

    @Test
    @DisplayName("Devrait parser les détails biographiques (date de naissance, nationalité)")
    void testExtractBioDetails() {
        List<InternationalLeaderRecord> leaders = parser.extractLeaders(testHtml, LocalDate.now(), LocalDate.now().minusYears(5));

        InternationalLeaderRecord president = leaders.stream()
                .filter(l -> l.name.contains("Hreinsson"))
                .findFirst()
                .orElseThrow();
        assertThat(president.birthDate).isEqualTo("1963-02-20");
        assertThat(president.nationality).isEqualTo("is");

        InternationalLeaderRecord judgeLi = leaders.stream()
                .filter(l -> l.name.contains("Bernd Hammermann"))
                .findFirst()
                .orElseThrow();
        assertThat(judgeLi.birthDate).isEqualTo("1964-01-05");
        assertThat(judgeLi.nationality).isEqualTo("li");
    }

    @Test
    @DisplayName("Devrait convertir en entités FollowTheMoney valides")
    void testConvertGeneratesValidFtmDataset() throws Exception {
        Path htmlFile = tempDir.resolve("efta.html");
        Files.writeString(htmlFile, testHtml, StandardCharsets.UTF_8);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-orgint", "role.pep");
        parser.convert(htmlFile, LuPepInternationalOrgsParser.DEFAULT_SOURCE_URL, LocalDate.now().minusYears(5), dataset);

        List<FtmEntity> entities = new ArrayList<>(dataset.entities());
        assertThat(entities).isNotEmpty();

        // 1 Position
        List<FtmEntity> positions = entities.stream().filter(e -> "Position".equals(e.getSchema())).toList();
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getValues("name")).contains("Dirigeant d'une organisation internationale au Luxembourg");

        // Personnes
        List<FtmEntity> persons = entities.stream().filter(e -> "Person".equals(e.getSchema())).toList();
        assertThat(persons.size()).isGreaterThanOrEqualTo(15);

        for (FtmEntity p : persons) {
            assertThat(p.getValues("name")).isNotEmpty();
            assertThat(p.getValues("country")).contains("lu");
        }

        // Occupancies
        List<FtmEntity> occs = entities.stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertThat(occs.size()).isEqualTo(persons.size());
        for (FtmEntity occ : occs) {
            // « role » n'existe pas dans le schéma FtM Occupancy : le contrôle qualité du core le rejette
            assertThat(occ.getValues("role")).as("Occupancy ne doit pas avoir la propriété 'role'").isEmpty();
            assertThat(occ.getValues("description")).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Devrait lire les SourceRecord via readSourceRecords")
    void testReadSourceRecords() throws Exception {
        Path htmlFile = tempDir.resolve("efta.html");
        Files.writeString(htmlFile, testHtml, StandardCharsets.UTF_8);

        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_international_orgs",
                htmlFile,
                rawDir,
                tempDir.resolve("out.ndjson"),
                Map.of("sourceUrl", LuPepInternationalOrgsParser.DEFAULT_SOURCE_URL)
        );

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).isNotEmpty();
        assertThat(records.size()).isGreaterThanOrEqualTo(15);
        assertThat(records.get(0).schema()).isEqualTo("Person");
        assertThat(records.get(0).name()).isNotBlank();
    }

    @Test
    @DisplayName("Devrait exécuter parse() complet et générer le fichier NDJSON")
    void testFullParse() throws Exception {
        Path htmlFile = tempDir.resolve("efta.html");
        Path outFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        Files.writeString(htmlFile, testHtml, StandardCharsets.UTF_8);

        ParserContext ctx = new ParserContext(
                "lu_pep_international_orgs",
                htmlFile,
                rawDir,
                outFile,
                Map.of("sourceUrl", LuPepInternationalOrgsParser.DEFAULT_SOURCE_URL)
        );

        ParserResult result = parser.parse(ctx);
        assertThat(result).isNotNull();
        assertThat(result.entityCount()).isGreaterThan(20);
        assertThat(Files.exists(outFile)).isTrue();
        assertThat(Files.size(outFile)).isGreaterThan(100);
    }

    @Test
    @DisplayName("Devrait slugifier proprement les noms islandais et nordiques")
    void testSlugify() {
        assertThat(LuPepInternationalOrgsParser.slugify("Páll Hreinsson")).isEqualTo("pall-hreinsson");
        assertThat(LuPepInternationalOrgsParser.slugify("Ólafur Jóhannes Einarsson")).isEqualTo("olafur-johannes-einarsson");
        assertThat(LuPepInternationalOrgsParser.slugify("Ása Ólafsdóttir")).isEqualTo("asa-olafsdottir");
    }
}
