package com.swiftcheck.aml.plugins.be.pep.councilofstate;

import com.swiftcheck.aml.plugins.be.pep.councilofstate.CouncilOfStateDocuments.Member;
import com.swiftcheck.aml.plugins.be.pep.councilofstate.CouncilOfStateDocuments.Members;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests hors ligne de {@link BePepCouncilOfStateParser} et {@link CouncilOfStateDocuments} : extraits de texte pour
 * chaque règle, puis les documents officiels du 12/11/2026 (page de direction, chambres FR et NL, auditorat FR et NL).
 */
class BePepCouncilOfStateParserTest {

    @Test
    @DisplayName("Chambres : rubriques Président / Membres / Suppléants / Assesseurs, greffiers et leurs suppléants écartés")
    void chambersRoles() {
        String text = """
                B. Section du contentieux administratif
                 Ve chambre
                Mardi et jeudi
                Présidents W. Van Vaerenbergh
                P. Vandernacht
                Membres
                G. Van Haegendoren
                P.-O. de Broux
                M. Belmessieri
                Greffiers Gr. Delannay (greffier en chef)
                Fr. Bontinck
                Suppléant W. Geurts
                 chambre Vbis
                Présidents C. Adams
                Membres
                K. Leus
                Greffier V. Wiame
                Suppléant C. Bertin, greffier assumé
                Assesseurs Chr. Behrendt, J. Englebert, Ph. De Bruycker
                N.
                Bruxelles, le 16 octobre 2026.
                Le Premier Président du Conseil d'État,
                Wilfried VAN VAERENBERGH
                """;

        List<Member> found = CouncilOfStateDocuments.parseChambers(text);

        assertEquals(List.of(
                "W. Van Vaerenbergh|Président de chambre", "P. Vandernacht|Président de chambre",
                "G. Van Haegendoren|Conseiller d'État", "P.-O. de Broux|Conseiller d'État",
                "M. Belmessieri|Conseiller d'État", "C. Adams|Président de chambre", "K. Leus|Conseiller d'État",
                "Chr. Behrendt|Assesseur", "J. Englebert|Assesseur", "Ph. De Bruycker|Assesseur"),
                found.stream().map(m -> m.displayName() + "|" + m.function()).toList());
    }

    @Test
    @DisplayName("Chambres NL : noms coupés sur deux lignes, dates entre parenthèses, deux noms sur une ligne")
    void chambersWrappedNames() {
        String text = """
                Voorzitter
                P. LEFRANC (t.e.m.
                30.09.2026)
                J. VAN
                NIEUWENHOVE
                Voorzitter C. ADAMS G. VAN HAEGENDOREN
                Leden
                E. VAN de VELDE
                A. D’ESPALLIER
                Assessoren J. VELAERS - M. TISON - St. LIERMAN (vanaf 28.04.2026)
                Griffiers
                W. GEURTS
                Plaatsvervanger
                C. BERTIN (toegevoegd griffier)
                """;

        List<String> names = CouncilOfStateDocuments.parseChambers(text).stream().map(Member::displayName).toList();

        assertEquals(List.of("P. LEFRANC", "J. VAN NIEUWENHOVE", "C. ADAMS", "G. VAN HAEGENDOREN", "E. VAN de VELDE",
                "A. D’ESPALLIER", "J. VELAERS", "M. TISON", "St. LIERMAN"), names);
    }

    @Test
    @DisplayName("Auditorat FR : fonction sur la même ligne, renvoyée à la ligne, ou donnée par la rubrique")
    void auditFr() {
        String text = """
                AUDITEUR GENERAL ADJOINT
                M. E. THIBAUT
                SECTION I
                M. X. DELGRANGE Premier auditeur chef de section
                M. Y. CHAUFFOUREAUX
                Mme V. SCHMITZ
                Premier auditeur
                Premier auditeur
                Mme N1 Auditeur adjoint
                M. L. STERCKX Auditeur adjoint (affectation provisoire en
                section III)
                Mme C. de LEMOS ESTEVES Auditeur adjoint
                """;

        assertEquals(List.of("E. THIBAUT|Auditeur général adjoint", "X. DELGRANGE|Premier auditeur chef de section",
                        "Y. CHAUFFOUREAUX|Premier auditeur", "V. SCHMITZ|Premier auditeur", "L. STERCKX|Auditeur adjoint",
                        "C. de LEMOS ESTEVES|Auditeur adjoint"),
                CouncilOfStateDocuments.parseAuditFr(text).stream().map(m -> m.displayName() + "|" + m.function()).toList());
    }

    @Test
    @DisplayName("Auditorat NL : prénom puis nom en capitales, fonctions néerlandaises traduites")
    void auditNl() {
        String text = """
                Auditoraat 16.07.2026
                Auditeur-generaal
                Luc VERMEIRE
                AFDELING I (Wetgeving)
                Kristine BAMS Eerste Auditeur-Afdelingshoofd
                Rein THIELEMANS Eerste Auditeur
                Rita VAN DEN EECKHOUT Eerste Auditeur
                Pierrot T’KINDT Eerste Auditeur
                Arne CARTON Auditeur
                Ben CLAES Adjunct-auditeur
                """;

        assertEquals(List.of("Luc VERMEIRE|Auditeur général", "Kristine BAMS|Premier auditeur chef de section",
                        "Rein THIELEMANS|Premier auditeur", "Rita VAN DEN EECKHOUT|Premier auditeur",
                        "Pierrot T’KINDT|Premier auditeur", "Arne CARTON|Auditeur", "Ben CLAES|Auditeur adjoint"),
                CouncilOfStateDocuments.parseAuditNl(text).stream().map(m -> m.displayName() + "|" + m.function()).toList());
    }

    @Test
    @DisplayName("Fusion : nom complet préféré aux initiales, fonction la plus haute, ancien nom gardé en alias")
    void mergesSamePerson() {
        Members members = new Members();
        members.add(new Member("W.", "Van Vaerenbergh", CouncilOfStateDocuments.PRESIDENT_CHAMBRE));
        members.add(new Member("Wilfried", "VAN VAERENBERGH", CouncilOfStateDocuments.PREMIER_PRESIDENT));
        members.add(new Member("W.", "VAN VAERENBERGH", CouncilOfStateDocuments.PRESIDENT_CHAMBRE));
        members.add(new Member("B.", "LAGASSE", CouncilOfStateDocuments.AUDITEUR));
        members.add(new Member("P.", "LAGASSE", CouncilOfStateDocuments.AUDITEUR));

        List<Member> list = members.list();
        assertEquals(3, list.size());
        assertEquals("Wilfried VAN VAERENBERGH", list.get(0).displayName());
        assertEquals(CouncilOfStateDocuments.PREMIER_PRESIDENT, list.get(0).function());
        assertTrue(members.aliasesOf(list.get(0)).contains("W. Van Vaerenbergh"));
    }

    @Test
    @DisplayName("Page de direction : entités HTML décodées, administratifs et poste vacant écartés")
    void directionPage() throws IOException {
        List<BePepCouncilOfStateParser.DirectionCard> cards =
                BePepCouncilOfStateParser.parseDirectionHtml(readResource("council-of-state-direction.html"));

        assertEquals(List.of("Luc VERMEIRE|Auditeur général", "Wilfried VAN VAERENBERGH|Premier président",
                        "Pascale VANDERNACHT|Président", "Eric THIBAUT|Auditeur général adjoint"),
                cards.stream().map(c -> c.member().displayName() + "|" + c.member().function()).toList());
    }

    @Test
    @DisplayName("Lien du PDF relu dans la page, sinon dernière adresse connue")
    void pdfLink() {
        String page = "<a href=\"/media/abc123/kamers.pdf\">Samenstelling</a>";
        assertEquals("https://www.raadvst-consetat.be/media/abc123/kamers.pdf",
                BePepCouncilOfStateParser.findPdfLink(page, "kamers.pdf", "repli"));
        assertEquals("repli", BePepCouncilOfStateParser.findPdfLink("<html></html>", "kamers.pdf", "repli"));
    }

    @Test
    @DisplayName("Documents officiels du 12/11/2026 : siège et auditorat, FR et NL")
    void officialDocuments(@TempDir Path dir) throws IOException {
        copyResource("council-of-state-direction.html", dir.resolve(BePepCouncilOfStateParser.FILE_NAME));
        for (BePepCouncilOfStateParser.Composition c : BePepCouncilOfStateParser.Composition.values()) {
            copyResource(c.fileName, dir.resolve(c.fileName));
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepCouncilOfStateParser.ID_PREFIX, BePepCouncilOfStateParser.TOPIC);

        BePepCouncilOfStateParser.Report report = new BePepCouncilOfStateParser().convert(dir, dataset);
        List<Member> members = report.members();

        assertEquals(169, members.size(), report.summary());
        assertEquals(14, members.stream().filter(m -> m.function().equals(CouncilOfStateDocuments.PRESIDENT_CHAMBRE)).count());
        assertTrue(members.stream().anyMatch(m -> m.displayName().equals("Kristine BAMS")));
        assertTrue(members.stream().anyMatch(m -> m.displayName().equals("V. SCHMITZ")));
        assertFalse(members.stream().anyMatch(m -> m.lastName().equalsIgnoreCase("Geurts")), "greffier");
        assertFalse(members.stream().anyMatch(m -> m.lastName().equalsIgnoreCase("Bertin")), "greffier assumé");
        assertFalse(members.stream().anyMatch(m -> m.lastName().equalsIgnoreCase("Delannay")), "greffier en chef");
        assertEquals(1, members.stream().filter(m -> m.lastName().equalsIgnoreCase("Van Vaerenbergh")).count());

        List<FtmEntity> persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).toList();
        assertEquals(169, persons.size());
        FtmEntity first = persons.stream().filter(p -> p.getValues("name").contains("Wilfried VAN VAERENBERGH")).findFirst().orElseThrow();
        assertTrue(first.getValues("position").contains("Premier président du Conseil d'État de Belgique"));
        assertTrue(first.getValues("alias").contains("W. Van Vaerenbergh"));
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepCouncilOfStateParserTest.class.getClassLoader().getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void copyResource(String name, Path target) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepCouncilOfStateParserTest.class.getClassLoader().getResourceAsStream(name),
                "Ressource de test introuvable : " + name)) {
            Files.copy(in, target);
        }
    }
}
