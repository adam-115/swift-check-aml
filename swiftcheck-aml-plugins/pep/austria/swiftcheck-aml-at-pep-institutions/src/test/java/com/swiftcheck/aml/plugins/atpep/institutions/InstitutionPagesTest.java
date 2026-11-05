package com.swiftcheck.aml.plugins.atpep.institutions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Pages réelles des institutions autrichiennes (5 novembre 2026). */
class InstitutionPagesTest {

    private static final Path DIR = Path.of("src/test/resources/institutions");

    private static String page(String name) throws IOException {
        return Files.readString(DIR.resolve(name), StandardCharsets.UTF_8);
    }

    private static InstitutionPages.Member find(List<InstitutionPages.Member> members, String name) {
        return members.stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("absent : " + name + " dans " + members.stream().map(InstitutionPages.Member::name).toList()));
    }

    @Test
    void readsTheFourteenJudgesOfTheConstitutionalCourt() throws IOException {
        List<InstitutionPages.Member> members = InstitutionPages.vfgh(page("vfgh-mitglieder.html"));

        assertEquals(14, members.size());
        InstitutionPages.Member president = find(members, "Christoph Grabenwarter");
        assertEquals("Präsident", president.role());
        assertEquals("Christoph", president.firstName());
        assertEquals("Grabenwarter", president.lastName());
        assertEquals("Vizepräsidentin", find(members, "Verena Madner").role());
        assertEquals("Verfassungsrichterin", find(members, "Ingrid Siess-Scherz").role());
    }

    @Test
    void readsTheSubstituteMembers() throws IOException {
        List<InstitutionPages.Member> members = InstitutionPages.vfgh(page("vfgh-ersatzmitglieder.html"));

        assertEquals(6, members.size());
        assertEquals("Ersatzmitglied", find(members, "Barbara Leitl-Staudinger").role());
        assertEquals("Leitl-Staudinger", find(members, "Barbara Leitl-Staudinger").lastName());
    }

    @Test
    void readsTheSupremeCourtWithoutAcademicTitles() throws IOException {
        List<InstitutionPages.Member> members = InstitutionPages.ogh(page("ogh.html"));

        assertTrue(members.size() >= 55, "l'OGH compte 60 juges : " + members.size());
        assertEquals("Präsident des OGH", find(members, "Georg Kodek").role());
        assertEquals("Vizepräsidentin des OGH", find(members, "Eva Marek").role());
        assertEquals("Jürgen Rassi", find(members, "Jürgen Rassi").name(), "« Hon.-Prof. PD Dr. » sont des titres");
        assertTrue(members.stream().noneMatch(m -> m.name().contains(".") || m.name().contains("PD ")));
    }

    @Test
    void readsTheAdministrativeCourtWithSurnameFirst() throws IOException {
        List<InstitutionPages.Member> members = InstitutionPages.vwgh(page("vwgh.html"));

        assertTrue(members.size() >= 40, "le VwGH compte une centaine de juges : " + members.size());
        InstitutionPages.Member president = find(members, "Albert Posch");
        assertEquals("Präsident des VwGH", president.role());
        assertEquals("Posch", president.lastName());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Hans Peter Lehofer")
                && m.role().contains("Senatspräsident")), "prénom composé et rôle de la rubrique");
        assertTrue(members.stream().noneMatch(m -> m.name().contains("Dr") || m.name().contains(",")));
    }

    @Test
    void readsTheCentralBankBoardWithItsTerms() throws IOException {
        List<InstitutionPages.Member> members = InstitutionPages.oenb(page("oenb.html"));

        assertEquals(4, members.size());
        InstitutionPages.Member governor = find(members, "Martin Kocher");
        assertEquals("Gouverneur der OeNB", governor.role());
        assertEquals("2025-09-01", governor.start());
        assertEquals(null, governor.end(), "la fin de période (2031) est future : elle n'est pas publiée");
        assertEquals("Vize-Gouverneurin der OeNB", find(members, "Edeltraud Stiftinger").role());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Thomas Steiner")));
    }

    @Test
    void readsThePresidentOfTheCourtOfAudit() throws IOException {
        List<InstitutionPages.Member> members = InstitutionPages.rechnungshof(page("rechnungshof.html"));

        assertEquals(1, members.size());
        assertEquals("Margit Kraker", members.get(0).name());
        assertEquals("2016-07-01", members.get(0).start());
    }


    @Test
    void readsTheLandGovernments() throws IOException {
        List<InstitutionPages.Member> noe = InstitutionPages.EXTRACTORS.get("land_noe").apply(page("land_noe.html"));
        assertEquals(9, noe.size());
        assertEquals("Landeshauptfrau (NÖ)", find(noe, "Johanna Mikl-Leitner").role());

        List<InstitutionPages.Member> sbg = InstitutionPages.EXTRACTORS.get("land_salzburg").apply(page("land_salzburg.html"));
        assertEquals(7, sbg.size());
        assertEquals("Landeshauptfrau (Salzburg)", find(sbg, "Karoline Edtstadler").role());

        List<InstitutionPages.Member> tirol = InstitutionPages.EXTRACTORS.get("land_tirol").apply(page("land_tirol.html"));
        assertEquals("Landeshauptmann (Tirol)", find(tirol, "Anton Mattle").role());
        assertTrue(tirol.size() >= 5, tirol.toString());

        List<InstitutionPages.Member> vbg = InstitutionPages.EXTRACTORS.get("land_vorarlberg").apply(page("land_vorarlberg.html"));
        assertEquals("Landeshauptmann (Vorarlberg)", find(vbg, "Markus Wallner").role());
        assertEquals(7, vbg.size());

        List<InstitutionPages.Member> ooe = InstitutionPages.EXTRACTORS.get("land_ooe").apply(page("land_ooe.html"));
        assertEquals("Landeshauptmann (OÖ)", find(ooe, "Thomas Stelzer").role());
        assertEquals(9, ooe.size(), ooe.toString());
    }

    @Test
    void readsTheStateHoldingCompanyBoards() throws IOException {
        List<InstitutionPages.Member> board = InstitutionPages.oebagBoard(page("oebag_board.html"));
        assertEquals(3, board.size(), board.toString());
        assertEquals("Vorstand der ÖBAG", find(board, "Edith Hlawati").role());

        List<InstitutionPages.Member> council = InstitutionPages.oebagSupervisory(page("oebag_supervisory.html"));
        assertTrue(council.size() >= 9, council.toString());
        assertEquals("Mitglied des Aufsichtsrats der ÖBAG", find(council, "Günther Ofner").role());
    }

    @Test
    void readsTheViennaGovernment() throws IOException {
        List<InstitutionPages.Member> wien = InstitutionPages.landWien(page("land_wien.html"));
        assertEquals(8, wien.size(), wien.toString());
        assertEquals("Bürgermeister und Landeshauptmann (Wien)", find(wien, "Michael Ludwig").role());
        assertEquals("Stadträtin (Wien)", find(wien, "Ulli Sima").role());
        assertEquals("Stadtrat (Wien)", find(wien, "Peter Hacker").role());
    }
    @Test
    void writesPersonsPositionsAndMandates() throws IOException {
        AtPepInstitutionsParser.Config config = AtPepInstitutionsParser.select(AtPepInstitutionsParser.loadConfig(),
                "vfgh,oenb");
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(AtPepInstitutionsParser.ID_PREFIX, AtPepInstitutionsParser.TOPIC);

        List<AtPepInstitutionsParser.Outcome> outcomes = AtPepInstitutionsParser.readAll(config, url -> url.contains("oenb")
                ? page("oenb.html") : page("vfgh-mitglieder.html"), null);
        String message = AtPepInstitutionsParser.convert(outcomes, dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertTrue(message.startsWith("18 personnes lues sur 2 institution(s)"), message);
        FtmEntity grabenwarter = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Christoph Grabenwarter")).findFirst().orElseThrow();
        assertEquals("Grabenwarter", grabenwarter.getValues("lastName").iterator().next());
        assertTrue(dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema()))
                .allMatch(e -> e.getValues("status").contains("current")));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("topics").contains("gov.financial")));
    }

    @Test
    void anInstitutionWhosePageFailsIsReportedAndNoneReadableStops(@TempDir Path dir) throws IOException {
        AtPepInstitutionsParser.Config config = AtPepInstitutionsParser.select(AtPepInstitutionsParser.loadConfig(),
                "vfgh,oenb");

        List<AtPepInstitutionsParser.Outcome> outcomes = AtPepInstitutionsParser.readAll(config, url -> {
            if (url.contains("oenb")) {
                throw new IOException("HTTP 503");
            }
            return page("vfgh-mitglieder.html");
        }, dir);
        assertEquals(null, outcomes.get(0).problem());
        assertTrue(Files.exists(dir.resolve("vfgh.html")), "page archivée dans rawDir");
        assertTrue(AtPepInstitutionsParser.convert(outcomes, new FtmDatasetBuilder("x", "role.pep"))
                .contains("Institutions non lues : OeNB (HTTP 503)"));

        assertThrows(IllegalStateException.class, () -> AtPepInstitutionsParser.readAll(config, url -> {
            throw new IOException("HTTP 503");
        }, dir));
        assertThrows(IllegalStateException.class,
                () -> AtPepInstitutionsParser.readAll(config, url -> "<html>autre page</html>", dir));
    }

    @Test
    void anUnknownKeyIsRefused() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AtPepInstitutionsParser.select(AtPepInstitutionsParser.loadConfig(), "vfgh,nope"));
        assertTrue(e.getMessage().contains("nope"));
    }
}
