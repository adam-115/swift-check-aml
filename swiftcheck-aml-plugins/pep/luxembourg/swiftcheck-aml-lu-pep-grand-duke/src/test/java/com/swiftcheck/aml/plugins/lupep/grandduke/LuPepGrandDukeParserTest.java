package com.swiftcheck.aml.plugins.lupep.grandduke;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

@DisplayName("Tests du parser du Grand-Duc de Luxembourg (LU-a)")
class LuPepGrandDukeParserTest {

    @TempDir
    Path tempDir;

    private LuPepGrandDukeParser parser;
    private Path guillaumeHtml;
    private Path henriHtml;

    @BeforeEach
    void setUp() throws Exception {
        parser = new LuPepGrandDukeParser();

        URL guillaumeRes = getClass().getResource("/monarchie/grand_duc_guillaume.html");
        assertNotNull(guillaumeRes, "Fichier grand_duc_guillaume.html introuvable");
        guillaumeHtml = Path.of(guillaumeRes.toURI());

        URL henriRes = getClass().getResource("/monarchie/grand_duc_henri.html");
        assertNotNull(henriRes, "Fichier grand_duc_henri.html introuvable");
        henriHtml = Path.of(henriRes.toURI());
    }

    @Test
    @DisplayName("Extraction du Grand-Duc régnant par défaut (1 personne en fonction)")
    void testExtractCurrentGrandDuke() {
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);
        List<LuPepGrandDukeParser.MonarchData> list =
                LuPepGrandDukeParser.extractMonarchs(
                        readFile(guillaumeHtml),
                        LuPepGrandDukeParser.DEFAULT_SOURCE_URL,
                        false, // includeFormer = false
                        fiveYearsAgo);

        assertNotNull(list);
        assertEquals(1, list.size(), "Par défaut, exactement 1 personne (Grand-Duc en titre) doit être extraite");

        var g = list.get(0);
        assertEquals("guillaume", g.slug());
        assertEquals("Guillaume de Luxembourg", g.fullName());
        assertEquals("Guillaume", g.firstName());
        assertEquals("de Luxembourg", g.lastName());
        assertEquals("1981-11-11", g.birthDate());
        assertEquals("Luxembourg", g.birthPlace());
        assertEquals(LocalDate.of(2025, 10, 3), g.startDate());
        assertNull(g.endDate());
        assertEquals("current", g.status());
        assertFalse(g.aliases().isEmpty());
        assertTrue(g.aliases().contains("S.A.R. le Grand-Duc Guillaume"));
    }

    @Test
    @DisplayName("Extraction avec ancien souverain sous rétention PEP 5 ans (includeFormer=true)")
    void testExtractWithFormerMonarch() {
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);
        List<LuPepGrandDukeParser.MonarchData> list =
                LuPepGrandDukeParser.extractMonarchs(
                        readFile(guillaumeHtml),
                        LuPepGrandDukeParser.DEFAULT_SOURCE_URL,
                        true, // includeFormer = true
                        fiveYearsAgo);

        assertNotNull(list);
        assertEquals(2, list.size(), "Doit inclure Guillaume (en fonction) et Henri (abdiqué le 03/10/2025)");

        var henri = list.stream().filter(m -> "henri".equals(m.slug())).findFirst().orElse(null);
        assertNotNull(henri, "Le Grand-Duc Henri doit être présent");
        assertEquals("Henri de Luxembourg", henri.fullName());
        assertEquals("1955-04-16", henri.birthDate());
        assertEquals("Betzdorf", henri.birthPlace());
        assertEquals(LocalDate.of(2000, 10, 7), henri.startDate());
        assertEquals(LocalDate.of(2025, 10, 3), henri.endDate());
        assertEquals("ended", henri.status());
    }

    @Test
    @DisplayName("Extraction depuis la page dédiée de l'ancien Grand-Duc Henri")
    void testExtractFromHenriPage() {
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);
        List<LuPepGrandDukeParser.MonarchData> list =
                LuPepGrandDukeParser.extractMonarchs(
                        readFile(henriHtml),
                        "https://monarchie.lu/fr/la-monarchie/les-anciens-souverains/le-grand-duc-henri",
                        false,
                        fiveYearsAgo);

        assertNotNull(list);
        assertEquals(1, list.size());
        var henri = list.get(0);
        assertEquals("henri", henri.slug());
        assertEquals("Henri de Luxembourg", henri.fullName());
        assertEquals("1955-04-16", henri.birthDate());
        assertEquals("ended", henri.status());
    }

    @Test
    @DisplayName("Conversion FollowTheMoney complète (Position, Person, Occupancy)")
    void testFullParseFtm() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-grand-duke", "role.pep");
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);

        parser.convert(guillaumeHtml, LuPepGrandDukeParser.DEFAULT_SOURCE_URL, false, fiveYearsAgo, dataset);

        Collection<FtmEntity> entities = dataset.entities();
        assertFalse(entities.isEmpty());

        // 1 Position
        FtmEntity pos = entities.stream()
                .filter(e -> "Position".equals(e.getSchema()))
                .findFirst()
                .orElse(null);
        assertNotNull(pos, "L'entité Position doit exister");
        assertTrue(pos.getValues("name").contains("Grand-Duc de Luxembourg"));
        assertTrue(pos.getValues("country").contains("lu"));
        assertTrue(pos.getValues("organization").contains("Maison grand-ducale"));

        // 1 Person (Guillaume)
        FtmEntity person = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .findFirst()
                .orElse(null);
        assertNotNull(person, "L'entité Person doit exister");
        assertEquals("lu-pep-grand-duke-guillaume", person.getId());
        assertTrue(person.getValues("name").contains("Guillaume de Luxembourg"));
        assertTrue(person.getValues("firstName").contains("Guillaume"));
        assertTrue(person.getValues("birthDate").contains("1981-11-11"));
        assertTrue(person.getValues("birthPlace").contains("Luxembourg"));
        assertTrue(person.getValues("topics").contains("role.pep"));

        // 1 Occupancy
        FtmEntity occ = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()))
                .findFirst()
                .orElse(null);
        assertNotNull(occ, "L'entité Occupancy doit exister");
        assertTrue(occ.getValues("holder").contains(person.getId()));
        assertTrue(occ.getValues("post").contains(pos.getId()));
        assertTrue(occ.getValues("status").contains("current"));
        assertTrue(occ.getValues("startDate").contains("2025-10-03"));
        assertTrue(occ.getValues("description").contains("Grand-Duc de Luxembourg"));
    }

    @Test
    @DisplayName("Exécution complète via parse(ctx) avec génération NDJSON")
    void testParseContextExecution() throws Exception {
        Path outputNdjson = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_grand_duke",
                guillaumeHtml,
                rawDir,
                outputNdjson,
                Map.of("sourceUrl", "https://monarchie.lu/fr/la-famille-grand-ducale/le-grand-duc")
        );

        ParserResult result = parser.parse(ctx);
        assertNotNull(result);
        assertTrue(result.entityCount() >= 3);
        assertEquals(0, result.rejectedCount(), "Aucune valeur ne doit être rejetée (conformité FtM stricte)");
        assertTrue(Files.exists(outputNdjson));
        assertTrue(Files.size(outputNdjson) > 0);

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertNotNull(records);
        assertEquals(1, records.size());
        assertEquals("lu-pep-grand-duke-guillaume", records.get(0).entityId());
        assertEquals("Guillaume de Luxembourg", records.get(0).name());
        assertEquals("https://monarchie.lu/fr/la-famille-grand-ducale/le-grand-duc", records.get(0).sourceRef());
    }

    @Test
    @DisplayName("Résolution dynamique de sourceUrl")
    void testResolveSourceUrl() {
        Path rawDir = tempDir.resolve("raw");
        ParserContext ctxDefault = new ParserContext("lu_pep_grand_duke", guillaumeHtml, rawDir, tempDir.resolve("out.ndjson"), Map.of());
        assertEquals(LuPepGrandDukeParser.DEFAULT_SOURCE_URL, LuPepGrandDukeParser.resolveSourceUrl(ctxDefault));

        String customUrl = "https://custom.monarchie.lu/grand-duc";
        ParserContext ctxCustom = new ParserContext("lu_pep_grand_duke", guillaumeHtml, rawDir, tempDir.resolve("out.ndjson"), Map.of("sourceUrl", customUrl));
        assertEquals(customUrl, LuPepGrandDukeParser.resolveSourceUrl(ctxCustom));
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
