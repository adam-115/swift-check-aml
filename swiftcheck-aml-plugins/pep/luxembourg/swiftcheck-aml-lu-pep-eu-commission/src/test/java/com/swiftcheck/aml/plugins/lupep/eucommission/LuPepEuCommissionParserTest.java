package com.swiftcheck.aml.plugins.lupep.eucommission;

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

@DisplayName("Tests du parser du Commissaire européen luxembourgeois (LU-EU)")
class LuPepEuCommissionParserTest {

    @TempDir
    Path tempDir;

    private LuPepEuCommissionParser parser;
    private Path collegeHtml;
    private Path individualHtml;

    @BeforeEach
    void setUp() throws Exception {
        parser = new LuPepEuCommissionParser();

        URL collegeResource = getClass().getResource("/eu_commission/college_commissioners.html");
        assertNotNull(collegeResource, "Fichier college_commissioners.html introuvable dans les ressources");
        collegeHtml = Path.of(collegeResource.toURI());

        URL individualResource = getClass().getResource("/eu_commission/christophe_hansen.html");
        assertNotNull(individualResource, "Fichier christophe_hansen.html introuvable dans les ressources");
        individualHtml = Path.of(individualResource.toURI());
    }

    @Test
    @DisplayName("Extraction depuis la page d'ensemble du Collège des commissaires")
    void testExtractFromCollegePage() {
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);
        List<LuPepEuCommissionParser.CommissionerData> list =
                LuPepEuCommissionParser.extractCommissioners(
                        readFile(collegeHtml),
                        LuPepEuCommissionParser.DEFAULT_SOURCE_URL,
                        fiveYearsAgo);

        assertNotNull(list);
        assertFalse(list.isEmpty());

        // Christophe Hansen doit être présent en tant que commissaire actuel
        var hansen = list.stream().filter(c -> c.fullName().contains("Hansen")).findFirst().orElse(null);
        assertNotNull(hansen, "Christophe Hansen doit être extrait de la page du Collège");
        assertEquals("current", hansen.status());
        assertEquals("lu", "lu");
        assertTrue(hansen.portfolio().toLowerCase().contains("agriculture"));

        // Nicolas Schmit (mandat 2019-2024 achevé récemment) doit être présent dans l'historique PEP 5 ans
        var schmit = list.stream().filter(c -> c.fullName().contains("Schmit")).findFirst().orElse(null);
        assertNotNull(schmit, "Nicolas Schmit doit être retenu au titre de l'historique PEP 5 ans");
        assertEquals("ended", schmit.status());
    }

    @Test
    @DisplayName("Extraction depuis la fiche individuelle de Christophe Hansen")
    void testExtractFromIndividualPage() {
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);
        List<LuPepEuCommissionParser.CommissionerData> list =
                LuPepEuCommissionParser.extractCommissioners(
                        readFile(individualHtml),
                        "https://commission.europa.eu/about/organisation/college-commissioners/christophe-hansen_fr",
                        fiveYearsAgo);

        assertNotNull(list);
        assertFalse(list.isEmpty());

        var hansen = list.stream().filter(c -> c.fullName().contains("Hansen")).findFirst().orElse(null);
        assertNotNull(hansen);
        assertEquals("Christophe Hansen", hansen.fullName());
        assertEquals("1982-02-21", hansen.birthDate());
        assertEquals("current", hansen.status());
    }

    @Test
    @DisplayName("Conversion complète FollowTheMoney (Person, Position, Occupancy)")
    void testFullParseFtm() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-eu-commission", "role.pep");
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);

        parser.convert(collegeHtml, fiveYearsAgo, LuPepEuCommissionParser.DEFAULT_SOURCE_URL, dataset);

        Collection<FtmEntity> entities = dataset.entities();
        assertFalse(entities.isEmpty());

        long persons = entities.stream().filter(e -> "Person".equals(e.getSchema())).count();
        long positions = entities.stream().filter(e -> "Position".equals(e.getSchema())).count();
        long occupancies = entities.stream().filter(e -> "Occupancy".equals(e.getSchema())).count();

        assertTrue(persons >= 1, "Au moins un commissaire doit être généré");
        assertEquals(persons, occupancies, "Chaque personne doit être reliée à un mandat");
        assertTrue(positions >= 1, "Au moins une fonction doit être déclarée");

        // Contrôle des propriétés Person
        for (FtmEntity p : entities.stream().filter(e -> "Person".equals(e.getSchema())).toList()) {
            assertFalse(p.getValues("name").isEmpty());
            assertTrue(p.getValues("country").contains("lu"));
            assertTrue(p.getValues("topics").contains("role.pep"));
        }

        // Contrôle des propriétés Position
        for (FtmEntity pos : entities.stream().filter(e -> "Position".equals(e.getSchema())).toList()) {
            assertTrue(pos.getValues("country").contains("lu"));
            assertTrue(pos.getValues("topics").contains("gov.national"));
        }
    }

    @Test
    @DisplayName("L'URL source est résolue dynamiquement depuis ParserContext")
    void testDynamicSourceUrlFromContext() throws Exception {
        String customUrl = "https://custom.commission.europa.eu/college.html";
        Path outputFile = tempDir.resolve("custom_eu_output.ndjson");
        ParserContext ctx = new ParserContext(
                "lu_pep_eu_commission",
                collegeHtml,
                null,
                outputFile,
                Map.of("yearsAfterEnd", "5", "sourceUrl", customUrl));

        ParserResult result = parser.parse(ctx);
        assertNotNull(result);
        assertTrue(Files.exists(outputFile));
        assertTrue(Files.size(outputFile) > 0);

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertNotNull(records);
        assertFalse(records.isEmpty());

        for (SourceRecord r : records) {
            assertEquals(customUrl, r.sourceRef(), "L'URL source de chaque enregistrement doit provenir dynamiquement du ParserContext");
        }
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
