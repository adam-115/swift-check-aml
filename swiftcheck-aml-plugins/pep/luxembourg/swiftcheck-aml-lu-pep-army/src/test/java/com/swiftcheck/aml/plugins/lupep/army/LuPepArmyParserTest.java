package com.swiftcheck.aml.plugins.lupep.army;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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

class LuPepArmyParserTest {

    @TempDir
    Path tempDir;

    private LuPepArmyParser parser;
    private Path sampleHtml;

    @BeforeEach
    void setUp() throws Exception {
        parser = new LuPepArmyParser();
        sampleHtml = Path.of("src/test/resources/personnel-cle.html");
    }

    @Test
    void testConvertPersonnelCleWithRetention() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-army", "role.pep");
        // Rétention 10 ans : de 2016 à 2026
        LocalDate oldestEndDate = LocalDate.of(2016, 1, 1);

        parser.convert(sampleHtml, LuPepArmyParser.DEFAULT_SOURCE_URL, oldestEndDate, dataset);

        List<FtmEntity> entities = new ArrayList<>(dataset.entities());
        assertThat(entities).isNotEmpty();

        // 1. Position unique
        List<FtmEntity> positions = entities.stream().filter(e -> "Position".equals(e.getSchema())).toList();
        assertThat(positions).hasSize(1);
        FtmEntity pos = positions.get(0);
        assertThat(pos.getValues("name")).contains("Officier supérieur de l'Armée luxembourgeoise");
        assertThat(pos.getValues("country")).contains("lu");
        assertThat(pos.getValues("organization")).contains("Armée luxembourgeoise");
        assertThat(pos.getValues("topics")).contains("gov.national", "gov.security");

        // 2. Personnes
        List<FtmEntity> persons = entities.stream().filter(e -> "Person".equals(e.getSchema())).toList();
        assertThat(persons).isNotEmpty();
        List<String> names = persons.stream().map(p -> p.getValues("name").iterator().next()).toList();
        assertThat(names).anyMatch(n -> n.contains("Robinet") || n.contains("ROBINET"));
        assertThat(names).anyMatch(n -> n.contains("Ballinger") || n.contains("BALLINGER"));
        assertThat(names).anyMatch(n -> n.contains("Schoeben") || n.contains("SCHOEBEN"));
        assertThat(names).anyMatch(n -> n.contains("Thull") || n.contains("THULL"));

        // 3. Occupancies
        List<FtmEntity> occupancies = entities.stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertThat(occupancies).isNotEmpty();

        for (FtmEntity occ : occupancies) {
            assertThat(occ.getValues("holder")).isNotEmpty();
            assertThat(occ.getValues("post")).contains(pos.getId());
            assertThat(occ.getValues("status")).isNotEmpty();
            // Aucun champ 'role' invalide ne doit exister dans Occupancy FollowTheMoney
            assertThat(occ.getValues("role")).isEmpty();
        }

        // Vérifier les commandants en exercice actuel
        List<FtmEntity> currentOccs = occupancies.stream()
                .filter(o -> o.getValues("status").contains("current"))
                .toList();
        assertThat(currentOccs).hasSizeGreaterThanOrEqualTo(3);

        List<String> currentDescs = currentOccs.stream()
                .map(o -> o.getValues("description").stream().findFirst().orElse(""))
                .toList();
        assertThat(currentDescs).contains("Chef d'état-major de l'Armée");
        assertThat(currentDescs).contains("Chef d'état-major adjoint de l'Armée");
        assertThat(currentDescs).contains("Commandant des Forces");
    }

    @Test
    void testRetentionFilter() {
        String html = """
            <ul class="cmp-grid">
              <li class="cmp-keyfiguregrid__item">
                <div class="cmp-keyfiguregrid__item-content">
                  <p class="cmp-keyfiguregrid__item-title">01.10.2026</p>
                  <div class="cmp-keyfiguregrid__item-keyfigure">
                    <span class="cmp-keyfiguregrid__item-unit">Général</span>
                  </div>
                </div>
                <div class="cmp-keyfiguregrid__item-desc">
                  <p>ROBINET Claude</p>
                </div>
              </li>
              <li class="cmp-keyfiguregrid__item">
                <div class="cmp-keyfiguregrid__item-content">
                  <p class="cmp-keyfiguregrid__item-title">29.09.2020 - 30.09.2026</p>
                  <div class="cmp-keyfiguregrid__item-keyfigure">
                    <span class="cmp-keyfiguregrid__item-unit">Général</span>
                  </div>
                </div>
                <div class="cmp-keyfiguregrid__item-desc">
                  <p>THULL Steve</p>
                </div>
              </li>
              <li class="cmp-keyfiguregrid__item">
                <div class="cmp-keyfiguregrid__item-content">
                  <p class="cmp-keyfiguregrid__item-title">15.07.1967 - 09.10.1972</p>
                  <div class="cmp-keyfiguregrid__item-keyfigure">
                    <span class="cmp-keyfiguregrid__item-unit">Colonel</span>
                  </div>
                </div>
                <div class="cmp-keyfiguregrid__item-desc">
                  <p>MAYER Michel</p>
                </div>
              </li>
            </ul>
        """;

        LocalDate now = LocalDate.of(2026, 10, 21);
        LocalDate oldest = LocalDate.of(2021, 10, 21); // 5 ans de rétention

        List<LuPepArmyParser.ArmyOfficerRecord> officers = LuPepArmyParser.extractOfficers(html, now, oldest);
        // Robinet (current) et Thull (fin en 2026) doivent être conservés
        // Mayer (fin en 1972) doit être filtré
        assertThat(officers).hasSize(2);
        assertThat(officers.get(0).fullName).containsIgnoringCase("Robinet");
        assertThat(officers.get(0).status).isEqualTo("current");
        assertThat(officers.get(1).fullName).containsIgnoringCase("Thull");
        assertThat(officers.get(1).status).isEqualTo("ended");
    }

    @Test
    void testParseFullPipeline() throws Exception {
        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_army",
                sampleHtml,
                rawDir,
                outputFile,
                Map.of(
                        "sourceUrl", LuPepArmyParser.DEFAULT_SOURCE_URL,
                        "yearsAfterEnd", "10"
                )
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
                "lu_pep_army",
                sampleHtml,
                rawDir,
                outputFile,
                Map.of("sourceUrl", LuPepArmyParser.DEFAULT_SOURCE_URL, "yearsAfterEnd", "10")
        );

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).isNotEmpty();
        assertThat(records).allMatch(r -> "Person".equals(r.schema()));
    }

    @Test
    void testResolveSourceUrl() {
        ParserContext ctx = new ParserContext(
                "lu_pep_army",
                null,
                null,
                null,
                Map.of("url", "https://www.armee.lu")
        );
        String resolved = LuPepArmyParser.resolveSourceUrl(ctx);
        assertThat(resolved).isEqualTo(LuPepArmyParser.DEFAULT_SOURCE_URL);
    }
}
