package com.swiftcheck.aml.plugins.lupep.politicalparties;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.swiftcheck.aml.plugins.lupep.politicalparties.LuPepPoliticalPartiesParser.PartyLeaderRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class LuPepPoliticalPartiesParserTest {

    @TempDir
    Path tempDir;

    private LuPepPoliticalPartiesParser parser;
    private Path sampleCsv;

    @BeforeEach
    void setUp() throws Exception {
        parser = new LuPepPoliticalPartiesParser();
        sampleCsv = Path.of("src/test/resources/partis_politiques_luxembourg.csv");
        if (!Files.exists(sampleCsv)) {
            sampleCsv = tempDir.resolve("sample_parties.csv");
            Files.writeString(sampleCsv,
                    "parti_code,parti_nom,role,nom_complet,prenom,nom,source_url\n"
                    + "CSV,Chrëschtlech-Sozial Vollekspartei,Président national,Luc Frieden,Luc,Frieden,https://csv.lu\n"
                    + "DP,Demokratesch Partei,Présidente nationale,Carole Hartmann,Carole,Hartmann,https://dp.lu\n",
                    StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Devrait extraire dynamiquement les dirigeants depuis le fichier source CSV sans aucun nom en dur")
    void testExtractLeadersDynamically() throws Exception {
        List<PartyLeaderRecord> leaders = parser.extractLeaders(sampleCsv, LuPepPoliticalPartiesParser.DEFAULT_SOURCE_URL);

        assertThat(leaders).isNotEmpty();
        assertThat(leaders.size()).isGreaterThanOrEqualTo(40);

        // Vérification de la présence des partis représentés
        assertThat(leaders).anyMatch(l -> "CSV".equals(l.partyCode));
        assertThat(leaders).anyMatch(l -> "DP".equals(l.partyCode));
        assertThat(leaders).anyMatch(l -> "LSAP".equals(l.partyCode));
        assertThat(leaders).anyMatch(l -> "ADR".equals(l.partyCode));
        assertThat(leaders).anyMatch(l -> l.partyCode.contains("gréng"));
        assertThat(leaders).anyMatch(l -> "Piraten".equals(l.partyCode));
        assertThat(leaders).anyMatch(l -> l.partyCode.contains("Lénk"));
    }

    @Test
    @DisplayName("Devrait convertir dynamiquement n'importe quel fichier source en entités FollowTheMoney valides")
    void testConvertCustomFileGeneratesValidFtm(@TempDir Path dir) throws Exception {
        Path customCsv = dir.resolve("custom_test.csv");
        Files.writeString(customCsv,
                "parti_code,parti_nom,role,nom_complet,prenom,nom,source_url\n"
                + "TEST,Parti de Test,Président,Jean Dupont,Jean,Dupont,https://test.lu\n"
                + "TEST,Parti de Test,Secrétaire général,Marie Martin,Marie,Martin,https://test.lu\n",
                StandardCharsets.UTF_8);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-parties", "role.pep");
        parser.convert(customCsv, "https://test.lu", LocalDate.now().minusYears(5), dataset);

        List<FtmEntity> entities = new ArrayList<>(dataset.entities());
        assertThat(entities).isNotEmpty();

        // 1 Position
        List<FtmEntity> positions = entities.stream().filter(e -> "Position".equals(e.getSchema())).toList();
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getValues("name")).contains("Membre d'un organe dirigeant de parti politique");

        // 2 Personnes
        List<FtmEntity> persons = entities.stream().filter(e -> "Person".equals(e.getSchema())).toList();
        assertThat(persons).hasSize(2);
        assertThat(persons.get(0).getValues("name")).contains("Jean Dupont");
        assertThat(persons.get(1).getValues("name")).contains("Marie Martin");

        // 2 Occupancies
        List<FtmEntity> occs = entities.stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertThat(occs).hasSize(2);
    }

    @Test
    @DisplayName("Devrait lire dynamiquement les SourceRecords via readSourceRecords")
    void testReadSourceRecords() throws Exception {
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_political_parties",
                sampleCsv,
                rawDir,
                tempDir.resolve("out.ndjson"),
                Map.of("sourceUrl", LuPepPoliticalPartiesParser.DEFAULT_SOURCE_URL)
        );

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).isNotEmpty();
        assertThat(records.size()).isGreaterThanOrEqualTo(40);
        assertThat(records.get(0).schema()).isEqualTo("Person");
        assertThat(records.get(0).name()).isNotBlank();
    }

    @Test
    @DisplayName("Devrait exécuter parse() complet et générer le fichier NDJSON")
    void testFullParse() throws Exception {
        Path outFile = tempDir.resolve("parties.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_political_parties",
                sampleCsv,
                rawDir,
                outFile,
                Map.of("sourceUrl", LuPepPoliticalPartiesParser.DEFAULT_SOURCE_URL)
        );

        ParserResult result = parser.parse(ctx);
        assertThat(result).isNotNull();
        assertThat(result.entityCount()).isGreaterThan(80);
        assertThat(Files.exists(outFile)).isTrue();
        assertThat(Files.size(outFile)).isGreaterThan(500);
    }

    @Test
    @DisplayName("Devrait slugifier proprement les identifiants")
    void testSlugify() {
        assertThat(LuPepPoliticalPartiesParser.slugify("Chrëschtlech-Sozial Vollekspartei")).isEqualTo("chreschtlech-sozial-vollekspartei");
        assertThat(LuPepPoliticalPartiesParser.slugify("déi gréng")).isEqualTo("dei-greng");
        assertThat(LuPepPoliticalPartiesParser.slugify("déi Lénk")).isEqualTo("dei-lenk");
    }
}
