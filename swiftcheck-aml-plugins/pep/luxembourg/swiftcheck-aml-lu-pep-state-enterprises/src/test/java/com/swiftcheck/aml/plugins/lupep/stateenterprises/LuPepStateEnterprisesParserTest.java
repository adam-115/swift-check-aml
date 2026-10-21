package com.swiftcheck.aml.plugins.lupep.stateenterprises;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.swiftcheck.aml.plugins.lupep.stateenterprises.LuPepStateEnterprisesParser.EnterpriseLeaderRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class LuPepStateEnterprisesParserTest {

    @TempDir
    Path tempDir;

    private LuPepStateEnterprisesParser parser;
    private Path sampleJson;
    private Path fullJson;

    @BeforeEach
    void setUp() {
        parser = new LuPepStateEnterprisesParser();
        sampleJson = Path.of("src/test/resources/sample_participations.json");
        fullJson = Path.of("src/test/resources/participations.json");
    }

    @Test
    @DisplayName("Devrait extraire dynamiquement les dirigeants depuis le fichier sample_participations.json")
    void testExtractLeadersFromSampleJson() throws Exception {
        assertThat(Files.exists(sampleJson)).isTrue();

        List<EnterpriseLeaderRecord> leaders = parser.extractLeaders(sampleJson, LuPepStateEnterprisesParser.DEFAULT_SOURCE_URL);

        assertThat(leaders).isNotEmpty();
        // Le sample contient au moins 3 entités avec conseil d'administration et direction
        assertThat(leaders.size()).isGreaterThanOrEqualTo(20);

        for (EnterpriseLeaderRecord leader : leaders) {
            assertThat(leader.companyName).isNotBlank();
            assertThat(leader.name).isNotBlank();
            assertThat(leader.role).isNotBlank();
        }
    }

    @Test
    @DisplayName("Devrait extraire l'intégralité des dirigeants (> 1000) depuis le jeu officiel participations.json")
    void testExtractLeadersFromFullJson() throws Exception {
        if (!Files.exists(fullJson)) {
            return; // Skip if large file not present in test dir
        }

        List<EnterpriseLeaderRecord> leaders = parser.extractLeaders(fullJson, LuPepStateEnterprisesParser.DEFAULT_SOURCE_URL);

        assertThat(leaders).isNotEmpty();
        // Le jeu officiel contient 1289 mandats de dirigeants d'entreprises publiques
        assertThat(leaders.size()).isGreaterThan(1000);

        long uniqueCompanies = leaders.stream().map(l -> l.companyName).distinct().count();
        assertThat(uniqueCompanies).isGreaterThan(100);
    }

    @Test
    @DisplayName("Devrait convertir dynamiquement les données en entités FollowTheMoney valides")
    void testConvertGeneratesValidFtmEntities() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-state-ent", "role.pep");
        parser.convert(sampleJson, LuPepStateEnterprisesParser.DEFAULT_SOURCE_URL, LocalDate.now().minusYears(5), dataset);

        List<FtmEntity> entities = new ArrayList<>(dataset.entities());
        assertThat(entities).isNotEmpty();

        // 1 Position officielle
        List<FtmEntity> positions = entities.stream().filter(e -> "Position".equals(e.getSchema())).toList();
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getValues("name"))
                .contains("Administrateur, directeur ou commissaire du gouvernement d'une entreprise publique");
        assertThat(positions.get(0).getValues("country")).contains("lu");

        // Entreprises publiques (Company)
        List<FtmEntity> companies = entities.stream().filter(e -> "Company".equals(e.getSchema())).toList();
        assertThat(companies).isNotEmpty();
        for (FtmEntity company : companies) {
            assertThat(company.getValues("jurisdiction")).contains("lu");
            assertThat(company.getValues("name")).isNotEmpty();
        }

        // Personnes physiques (Person)
        List<FtmEntity> persons = entities.stream().filter(e -> "Person".equals(e.getSchema())).toList();
        assertThat(persons).isNotEmpty();
        for (FtmEntity person : persons) {
            assertThat(person.getValues("name")).isNotEmpty();
            assertThat(person.getValues("country")).contains("lu");
            assertThat(person.getValues("topics")).contains("role.pep");
        }

        // Occupancies liant les personnes à la Position
        List<FtmEntity> occs = entities.stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertThat(occs).isNotEmpty();
        for (FtmEntity occ : occs) {
            assertThat(occ.getValues("post")).contains(positions.get(0).getId());
            assertThat(occ.getValues("holder")).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Devrait exécuter le cycle de parsing complet et générer un fichier NDJSON valide")
    void testParseFullLifecycle() throws Exception {
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext(
                "lu_pep_state_enterprises",
                sampleJson,
                rawDir,
                outputFile,
                Map.of("yearsAfterEnd", "5")
        );

        ParserResult result = parser.parse(ctx);

        assertThat(result).isNotNull();
        assertThat(result.entityCount()).isGreaterThan(0);
        assertThat(Files.exists(outputFile)).isTrue();
        assertThat(Files.size(outputFile)).isGreaterThan(0);

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Position\""));
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Company\""));
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Person\""));
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Occupancy\""));
    }

    @Test
    @DisplayName("Devrait lire dynamiquement les SourceRecords via readSourceRecords")
    void testReadSourceRecords() throws Exception {
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext(
                "lu_pep_state_enterprises",
                sampleJson,
                rawDir,
                outputFile,
                Map.of()
        );

        List<SourceRecord> records = parser.readSourceRecords(ctx);

        assertThat(records).isNotEmpty();
        for (SourceRecord rec : records) {
            assertThat(rec.entityId()).isNotBlank();
            assertThat(rec.schema()).isEqualTo("Person");
            assertThat(rec.name()).isNotBlank();
        }
    }
}
