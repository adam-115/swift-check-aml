package com.swiftcheck.aml.plugins.lupep.judiciary;

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

@DisplayName("Tests du parser des Hautes Juridictions Judiciaires du Luxembourg (LU-d)")
class LuPepJudiciaryParserTest {

    @TempDir
    Path tempDir;

    private LuPepJudiciaryParser parser;
    private Path constHtml;

    @BeforeEach
    void setUp() throws Exception {
        parser = new LuPepJudiciaryParser();

        URL res = getClass().getResource("/justice/cour_constitutionnelle.html");
        assertNotNull(res, "Fichier cour_constitutionnelle.html introuvable");
        constHtml = Path.of(res.toURI());
    }

    @Test
    @DisplayName("Extraction des magistrats depuis la page officielle de la Cour constitutionnelle")
    void testExtractFromConstitutionalCourtPage() {
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);
        List<LuPepJudiciaryParser.MagistrateData> list =
                LuPepJudiciaryParser.extractMagistrates(
                        readFile(constHtml),
                        LuPepJudiciaryParser.DEFAULT_SOURCE_URL,
                        fiveYearsAgo);

        assertNotNull(list);
        assertFalse(list.isEmpty());

        // 1. Thierry Hoscheit (Président Cour constitutionnelle, Cour supérieure de justice, Cour de cassation)
        var hoscheit = list.stream().filter(m -> "thierry-hoscheit".equals(m.slug())).findFirst().orElse(null);
        assertNotNull(hoscheit, "Thierry Hoscheit doit être présent");
        assertEquals("Thierry Hoscheit", hoscheit.fullName());
        assertTrue(hoscheit.functions().contains(LuPepJudiciaryParser.POS_COUR_CONST));
        assertTrue(hoscheit.functions().contains(LuPepJudiciaryParser.POS_PRES_CSJ));
        assertTrue(hoscheit.functions().contains(LuPepJudiciaryParser.POS_CASSATION));
        assertEquals("current", hoscheit.status());

        // 2. Francis Delaporte (Vice-président Cour constitutionnelle)
        var delaporte = list.stream().filter(m -> "francis-delaporte".equals(m.slug())).findFirst().orElse(null);
        assertNotNull(delaporte, "Francis Delaporte doit être présent");
        assertEquals("Francis Delaporte", delaporte.fullName());

        // 3. Conseillers à la Cour de cassation / Cour constitutionnelle
        var schweitzer = list.stream().filter(m -> "danielle-schweitzer".equals(m.slug())).findFirst().orElse(null);
        assertNotNull(schweitzer);
        assertTrue(schweitzer.functions().contains(LuPepJudiciaryParser.POS_CASSATION));

        // 4. Le greffier (Viviane Probst) NE DOIT PAS être extrait comme magistrat PEP
        var greffier = list.stream().filter(m -> m.fullName().toLowerCase().contains("probst")).findFirst().orElse(null);
        assertNull(greffier, "Le greffier ne doit pas être extrait comme PEP");

        // 5. Tous les magistrats extraits proviennent strictement du fichier HTML (9 membres en titre)
        assertEquals(9, list.size(), "Exactement 9 magistrats doivent être extraits du HTML de la Cour constitutionnelle");
    }

    @Test
    @DisplayName("Conversion complète FollowTheMoney (Positions, Persons, Occupancies) avec 0 rejet")
    void testFullParseFtm() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-judiciary", "role.pep");
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);

        parser.convert(constHtml, LuPepJudiciaryParser.DEFAULT_SOURCE_URL, fiveYearsAgo, dataset);

        Collection<FtmEntity> entities = dataset.entities();
        assertFalse(entities.isEmpty());

        // Vérifier les 4 Positions
        List<FtmEntity> positions = entities.stream()
                .filter(e -> "Position".equals(e.getSchema()))
                .toList();
        assertEquals(4, positions.size(), "Les 4 positions officielles LU-d doivent exister");

        // Vérifier Thierry Hoscheit
        FtmEntity hoscheit = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getId().endsWith("thierry-hoscheit"))
                .findFirst()
                .orElse(null);
        assertNotNull(hoscheit);
        assertTrue(hoscheit.getValues("name").contains("Thierry Hoscheit"));
        assertTrue(hoscheit.getValues("country").contains("lu"));
        assertTrue(hoscheit.getValues("topics").contains("role.pep"));

        // Vérifier les Occupancies
        List<FtmEntity> occs = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()))
                .toList();
        assertFalse(occs.isEmpty());

        for (FtmEntity occ : occs) {
            assertNotNull(occ.getValues("holder"));
            assertNotNull(occ.getValues("post"));
            assertNotNull(occ.getValues("description"));
            assertNotNull(occ.getValues("status"));
        }
    }

    @Test
    @DisplayName("Exécution complète via parse(ctx) avec génération NDJSON et 0 % rejet")
    void testParseContextExecution() throws Exception {
        Path outputNdjson = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext(
                "lu_pep_judiciary",
                constHtml,
                rawDir,
                outputNdjson,
                Map.of("sourceUrl", "https://justice.public.lu/fr/organisation-justice/cour-constitutionnelle.html")
        );

        ParserResult result = parser.parse(ctx);
        assertNotNull(result);
        assertTrue(result.entityCount() >= 15);
        assertEquals(0, result.rejectedCount(), "Aucune valeur ne doit être rejetée (conformité FtM stricte)");
        assertTrue(Files.exists(outputNdjson));
        assertTrue(Files.size(outputNdjson) > 0);

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertNotNull(records);
        assertEquals(9, records.size());
        assertTrue(records.stream().anyMatch(r -> "Thierry Hoscheit".equals(r.name())));
    }

    @Test
    @DisplayName("Résolution dynamique de sourceUrl")
    void testResolveSourceUrl() {
        Path rawDir = tempDir.resolve("raw");
        ParserContext ctxDefault = new ParserContext("lu_pep_judiciary", constHtml, rawDir, tempDir.resolve("out.ndjson"), Map.of());
        assertEquals(LuPepJudiciaryParser.DEFAULT_SOURCE_URL, LuPepJudiciaryParser.resolveSourceUrl(ctxDefault));

        String customUrl = "https://custom.justice.public.lu/cour-constitutionnelle.html";
        ParserContext ctxCustom = new ParserContext("lu_pep_judiciary", constHtml, rawDir, tempDir.resolve("out.ndjson"), Map.of("sourceUrl", customUrl));
        assertEquals(customUrl, LuPepJudiciaryParser.resolveSourceUrl(ctxCustom));
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
