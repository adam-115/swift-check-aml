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
        assertTrue(hansen.portfolio().toLowerCase().contains("agriculture"));
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
        assertEquals("Christophe", hansen.firstName());
        assertEquals("Hansen", hansen.lastName());
        assertEquals("current", hansen.status());
        assertTrue(hansen.portfolio().toLowerCase().contains("agriculture"));
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

    private static final String SCHMIT_URL = "https://commissioners.ec.europa.eu/nicolas-schmit_fr";

    /** Parser dont les fiches d'archive sont lues dans les ressources de test (sans réseau). */
    private LuPepEuCommissionParser offlineParser() {
        return new LuPepEuCommissionParser(url -> {
            if (!SCHMIT_URL.equals(url)) {
                throw new java.io.IOException("HTTP 404 pour " + url);
            }
            return readFile(resource("/eu_commission/nicolas_schmit_archive.html"));
        });
    }

    private Path resource(String name) {
        try {
            return Path.of(getClass().getResource(name).toURI());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Fiche d'archive : nom, portefeuille, mandat et dates de la biographie")
    void testParseArchivePage() {
        var schmit = LuPepEuCommissionParser.parseArchivePage(
                readFile(resource("/eu_commission/nicolas_schmit_archive.html")), SCHMIT_URL);

        assertNotNull(schmit);
        assertEquals("Nicolas Schmit", schmit.fullName());
        assertEquals("nicolas-schmit", schmit.slug());
        assertEquals("Emploi et droits sociaux", schmit.portfolio());
        assertEquals("2019-12", schmit.startDate());
        assertEquals("2024", schmit.endDate());
        assertEquals("ended", schmit.status());
        assertFalse(schmit.isPresident());
        assertEquals(SCHMIT_URL, schmit.pageUrl());
    }

    @Test
    @DisplayName("formerCommissionerPages : commissaire en fonction + ancien commissaire (2 Person, 1 Position, 2 Occupancy)")
    void testFormerCommissionerPages() throws Exception {
        Path outputFile = tempDir.resolve("eu.ndjson");
        Path rawDir = tempDir.resolve("raw");
        ParserContext ctx = new ParserContext("lu_pep_eu_commission", collegeHtml, rawDir, outputFile,
                Map.of("yearsAfterEnd", "5", LuPepEuCommissionParser.PARAM_FORMER_PAGES, " " + SCHMIT_URL + "\n"));

        ParserResult result = offlineParser().parse(ctx);

        assertEquals(5, result.entityCount());
        String ndjson = Files.readString(outputFile);
        assertTrue(ndjson.contains("Nicolas Schmit"));
        assertTrue(ndjson.contains("\"2019-12\""));
        assertTrue(ndjson.contains("\"ended\""));
        assertTrue(Files.exists(rawDir.resolve("former-nicolas-schmit.html")), "La fiche brute doit être gardée");
    }

    @Test
    @DisplayName("Rétention : un ancien commissaire sorti depuis plus de N ans n'est pas publié")
    void testFormerCommissionerOutOfRetention() throws Exception {
        // Mandat terminé en 2024 : hors rétention si l'on ne garde que les fins postérieures au 1er janvier 2025
        List<LuPepEuCommissionParser.CommissionerData> former =
                offlineParser().formerCommissioners(List.of(SCHMIT_URL), LocalDate.of(2025, 1, 1), null);
        assertTrue(former.isEmpty());

        former = offlineParser().formerCommissioners(List.of(SCHMIT_URL), LocalDate.of(2024, 12, 31), null);
        assertEquals(1, former.size());
    }

    @Test
    @DisplayName("Une fiche d'archive inaccessible fait échouer l'exécution au lieu de retirer un PEP")
    void testUnreachableFormerPageFails() {
        assertThrows(java.io.IOException.class, () -> offlineParser().formerCommissioners(
                List.of("https://commissioners.ec.europa.eu/inconnu_fr"), LocalDate.now().minusYears(5), null));
    }

    @Test
    @DisplayName("formerCommissionerPages : lignes, virgules ou tableau JSON")
    void testFormerPagesFormats() {
        String a = "https://commissioners.ec.europa.eu/a_fr";
        String b = "https://commissioners.ec.europa.eu/b_fr";
        String key = LuPepEuCommissionParser.PARAM_FORMER_PAGES;
        assertEquals(List.of(a, b), LuPepEuCommissionParser.formerPages(Map.of(key, a + "\n" + b)));
        assertEquals(List.of(a, b), LuPepEuCommissionParser.formerPages(Map.of(key, a + ", " + b)));
        assertEquals(List.of(a, b), LuPepEuCommissionParser.formerPages(Map.of(key, "[\"" + a + "\",\"" + b + "\"]")));
        assertEquals(List.of(), LuPepEuCommissionParser.formerPages(Map.of()));
    }

    @Test
    @DisplayName("Dates partielles : dernier jour couvert")
    void testLastDayOf() {
        assertEquals(LocalDate.of(2024, 12, 31), LuPepEuCommissionParser.lastDayOf("2024"));
        assertEquals(LocalDate.of(2024, 11, 30), LuPepEuCommissionParser.lastDayOf("2024-11"));
        assertEquals(LocalDate.of(2024, 11, 15), LuPepEuCommissionParser.lastDayOf("2024-11-15"));
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
