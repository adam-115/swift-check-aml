package com.swiftcheck.aml.plugins.lupep.councilofstate;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

@DisplayName("Tests du parser du Conseil d'État luxembourgeois")
class LuPepCouncilOfStateParserTest {

    @TempDir
    Path tempDir;

    private LuPepCouncilOfStateParser parser;
    private Path sampleHtml;

    @BeforeEach
    void setUp() throws Exception {
        parser = new LuPepCouncilOfStateParser();
        URL resource = getClass().getResource("/conseil_etat/membresdepuis1857.html");
        assertNotNull(resource, "Extrait HTML de test introuvable dans src/test/resources/conseil_etat/");
        sampleHtml = Path.of(resource.toURI());
    }

    @Test
    @DisplayName("Extraction des membres : les conseillers en fonction et les anciens récents sont extraits")
    void testExtractMembers() throws Exception {
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);
        List<LuPepCouncilOfStateParser.CouncilMember> members =
                LuPepCouncilOfStateParser.extractMembers(sampleHtml, fiveYearsAgo);

        assertNotNull(members);
        assertFalse(members.isEmpty(), "La liste des membres ne doit pas être vide");

        long currentCount = members.stream().filter(m -> "current".equals(m.status())).count();
        // Le Conseil d'État a 21 sièges, dont 20 sont actuellement pourvus suite à une démission récente
        assertEquals(20, currentCount, "Il doit y avoir exactement 20 conseillers d'État actuellement en fonction");

        // Vérification d'un conseiller actif emblématique
        LuPepCouncilOfStateParser.CouncilMember thewes = members.stream()
                .filter(m -> m.fullName().contains("Thewes"))
                .findFirst()
                .orElse(null);
        assertNotNull(thewes, "Marc Thewes doit être présent");
        assertEquals("Thewes", thewes.lastName());
        assertEquals("Marc", thewes.firstName());
        assertEquals("Marc Thewes", thewes.displayName());
        assertEquals(LocalDate.of(1967, 2, 21), thewes.birthDate());
        assertEquals(LocalDate.of(2015, 2, 4), thewes.startDate());
        assertEquals("Président du Conseil d'État", thewes.positionTitle());
        assertEquals("current", thewes.status());
    }

    @Test
    @DisplayName("Vérification du Bureau du Conseil d'État (Président et Vice-Présidents)")
    void testBureauComposition() throws Exception {
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);
        List<LuPepCouncilOfStateParser.CouncilMember> members =
                LuPepCouncilOfStateParser.extractMembers(sampleHtml, fiveYearsAgo);

        LuPepCouncilOfStateParser.CouncilMember president = members.stream()
                .filter(m -> "Président du Conseil d'État".equals(m.positionTitle()))
                .findFirst().orElse(null);
        assertNotNull(president, "Un Président doit être identifié");
        assertEquals("Thewes Marc", president.fullName());

        List<LuPepCouncilOfStateParser.CouncilMember> vps = members.stream()
                .filter(m -> "Vice-Président du Conseil d'État".equals(m.positionTitle()))
                .toList();
        assertEquals(2, vps.size(), "Il doit y avoir 2 Vice-Présidents");
        assertTrue(vps.stream().anyMatch(m -> m.fullName().contains("Kinsch")), "Alain Kinsch doit être Vice-Président");
        assertTrue(vps.stream().anyMatch(m -> m.fullName().contains("Bodry")), "Alex Bodry doit être Vice-Président");
    }

    @Test
    @DisplayName("Vérification des anciens conseillers récents dans la fenêtre de 5 ans")
    void testRecentFormerMembers() throws Exception {
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);
        List<LuPepCouncilOfStateParser.CouncilMember> members =
                LuPepCouncilOfStateParser.extractMembers(sampleHtml, fiveYearsAgo);

        List<LuPepCouncilOfStateParser.CouncilMember> former = members.stream()
                .filter(m -> "ended".equals(m.status()))
                .toList();
        assertFalse(former.isEmpty(), "Il doit y avoir des anciens conseillers dans la fenêtre des 5 ans");

        // Vérification de démissions récentes documentées
        assertTrue(former.stream().anyMatch(m -> m.fullName().contains("Du Bois")), "Deidre Du Bois (démission 2026)");
        assertTrue(former.stream().anyMatch(m -> m.fullName().contains("Kohn")), "Georges Kohn (démission 2026)");
        assertTrue(former.stream().anyMatch(m -> m.fullName().contains("Wagener")), "Yves Wagener (démission 2026)");
        assertTrue(former.stream().anyMatch(m -> m.fullName().contains("Zeimet")), "Laurent Zeimet (démission 2023)");
        assertTrue(former.stream().anyMatch(m -> m.fullName().contains("Lamesch")), "Martine Lamesch (démission 2021)");
    }

    @Test
    @DisplayName("Conversion complète FollowTheMoney (Person, Position, Occupancy)")
    void testFullParseFtm() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-council-of-state", "role.pep");
        LocalDate fiveYearsAgo = LocalDate.now().minusYears(5);

        parser.convert(sampleHtml, fiveYearsAgo, LuPepCouncilOfStateParser.DEFAULT_SOURCE_URL, dataset);

        java.util.Collection<FtmEntity> entities = dataset.entities();
        assertFalse(entities.isEmpty());

        long persons = entities.stream().filter(e -> "Person".equals(e.getSchema())).count();
        long positions = entities.stream().filter(e -> "Position".equals(e.getSchema())).count();
        long occupancies = entities.stream().filter(e -> "Occupancy".equals(e.getSchema())).count();

        assertTrue(persons >= 20, "Au moins 20 personnes doivent être générées");
        assertEquals(persons, occupancies, "Chaque personne doit avoir une occupancy");
        assertTrue(positions >= 1 && positions <= 3, "Il doit y avoir entre 1 et 3 postes (Membre, Président, Vice-Président)");

        // Vérification des propriétés PEP sur les personnes
        for (FtmEntity p : entities.stream().filter(e -> "Person".equals(e.getSchema())).toList()) {
            assertFalse(p.getValues("name").isEmpty(), "Chaque personne doit avoir un nom");
            assertTrue(p.getValues("country").contains("lu"));
            assertTrue(p.getValues("topics").contains("role.pep"), "Chaque personne doit avoir le topic role.pep");
        }

        // Vérification des topics institutionnels sur les positions
        for (FtmEntity pos : entities.stream().filter(e -> "Position".equals(e.getSchema())).toList()) {
            assertTrue(pos.getValues("country").contains("lu"));
            assertTrue(pos.getValues("topics").contains("gov.national"));
            assertTrue(pos.getValues("topics").contains("gov.legislative"));
        }
    }

    @Test
    @DisplayName("readSourceRecords retourne les enregistrements pour le contrôle d'intégrité Yente")
    void testReadSourceRecords() throws Exception {
        ParserContext ctx = new ParserContext("lu_pep_council_of_state", sampleHtml, null, null,
                Map.of("yearsAfterEnd", "5"));

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertNotNull(records);
        assertTrue(records.size() >= 20);

        for (SourceRecord r : records) {
            assertNotNull(r.entityId(), "L'entityId ne doit pas être null");
            assertTrue(r.entityId().startsWith("lu-pep-council-of-state-"));
            assertEquals("Person", r.schema());
            assertNotNull(r.name());
            assertFalse(r.name().isBlank());
        }
    }

    @Test
    @DisplayName("Découpage des noms (Nom Prénom, Particules)")
    void testParseName() {
        var p1 = LuPepCouncilOfStateParser.parseName("Thewes Marc");
        assertEquals("Marc", p1.firstName());
        assertEquals("Thewes", p1.lastName());
        assertEquals("Marc Thewes", p1.displayName());

        var p2 = LuPepCouncilOfStateParser.parseName("Du Bois Deidre");
        assertEquals("Deidre", p2.firstName());
        assertEquals("Du Bois", p2.lastName());
        assertEquals("Deidre Du Bois", p2.displayName());

        var p3 = LuPepCouncilOfStateParser.parseName("Theisen Dan,");
        assertEquals("Dan", p3.firstName());
        assertEquals("Theisen", p3.lastName());
        assertEquals("Dan Theisen", p3.displayName());
    }

    @Test
    @DisplayName("L'URL source est résolue dynamiquement depuis ParserContext")
    void testDynamicSourceUrlFromContext() throws Exception {
        String customUrl = "https://custom.conseil-etat.public.lu/composition.html";
        Path outputFile = tempDir.resolve("custom_council_output.ndjson");
        ParserContext ctx = new ParserContext(
                "lu_pep_council_of_state",
                sampleHtml,
                null,
                outputFile,
                Map.of("yearsAfterEnd", "5", "sourceUrl", customUrl));

        parser.parse(ctx);
        assertTrue(Files.exists(outputFile), "Le fichier ndjson de sortie doit exister");

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertNotNull(records);
        assertFalse(records.isEmpty());
        for (SourceRecord r : records) {
            assertEquals(customUrl, r.sourceRef(), "L'URL source de chaque enregistrement doit provenir dynamiquement du ParserContext");
        }
    }
}
