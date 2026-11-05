package com.swiftcheck.aml.plugins.itpep.institutions;

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

/** Pages réelles des institutions italiennes (5 novembre 2026). */
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
    void readsTheBankOfItalyDirectorateWithTheirStartDates() throws IOException {
        List<InstitutionPages.Member> members = InstitutionPages.bancaDItalia(page("banca_ditalia.html"));

        assertEquals(5, members.size());
        assertEquals("Governatore", find(members, "Fabio Panetta").role());
        assertEquals("2023-11-01", find(members, "Fabio Panetta").start());
        assertEquals("Direttore generale", find(members, "Paolo Angelini").role());
        assertEquals("2026-04-01", find(members, "Paolo Angelini").start());
        assertEquals("Vice Direttrice generale", find(members, "Chiara Scotti").role());
        assertEquals("2025-07-02", find(members, "Sergio Nicoletti Altimari").start());
        assertEquals(null, find(members, "Sergio Nicoletti Altimari").lastName(), "nom composé : pas de découpage devinette");
    }

    @Test
    void readsThePrivacyAuthorityIncludingAMemberWhoLeftThisYear() throws IOException {
        List<InstitutionPages.Member> members = InstitutionPages.garantePrivacy(page("garante_privacy.html"));

        assertEquals(4, members.size());
        assertEquals("Presidente", find(members, "Pasquale Stanzione").role());
        assertEquals("Vice Presidente", find(members, "Ginevra Cerrina Feroni").role());
        InstitutionPages.Member scorza = find(members, "Guido Scorza");
        assertEquals("2020-07-29", scorza.start());
        assertEquals("2026-01-19", scorza.end());
        assertEquals(null, find(members, "Agostino Ghiglia").end());
    }

    @Test
    void readsTheCompetitionAuthorityAndTheCommunicationsAuthority() throws IOException {
        List<InstitutionPages.Member> agcm = InstitutionPages.agcm(page("agcm.html"));
        assertEquals(2, agcm.size(), agcm.toString());
        assertEquals("Componente", find(agcm, "Elisabetta Iossa").role());
        assertTrue(agcm.stream().anyMatch(m -> m.name().equals("Alberto Bagnai")));

        List<InstitutionPages.Member> agcom = InstitutionPages.agcom(page("agcom.html"));
        assertEquals(4, agcom.size(), agcom.toString());
        assertEquals("Commissario", find(agcom, "Laura Aria").role());
    }

    @Test
    void readsTheInsuranceSupervisorAndTheChiefOfDefence() throws IOException {
        List<InstitutionPages.Member> ivass = InstitutionPages.ivass(page("ivass.html"));
        assertEquals(7, ivass.size(), ivass.toString());
        assertTrue(ivass.stream().anyMatch(m -> m.name().equals("Rita Laura D'Ecclesia")));

        List<InstitutionPages.Member> smd = InstitutionPages.difesaSmd(page("difesa_smd.html"));
        assertEquals(1, smd.size());
        assertEquals("Luciano Portolano", smd.get(0).name());
        assertEquals("Capo di Stato Maggiore della Difesa", smd.get(0).role());
    }


    @Test
    void readsTheAuthorityPresidentsAndTheInternationalOrganisations() throws IOException {
        assertEquals("Saverio Valentino", InstitutionPages.agcmPresident(page("agcm_president.html")).get(0).name());
        assertEquals("Giacomo Lasorella", InstitutionPages.agcomPresident(page("agcom_president.html")).get(0).name());

        List<InstitutionPages.Member> fao = InstitutionPages.fao(page("fao.html"));
        assertEquals("Qu Dongyu", fao.get(0).name());
        assertEquals("Director-General", fao.get(0).role());

        List<InstitutionPages.Member> wfp = InstitutionPages.wfp(page("wfp.html"));
        assertEquals("Executive Director", find(wfp, "Luke J. Lindberg").role());
        assertTrue(wfp.stream().anyMatch(m -> m.name().equals("Carl Skau") && m.role().startsWith("Deputy Executive Director")));
        assertTrue(wfp.size() >= 5, wfp.toString());

        List<InstitutionPages.Member> ifad = InstitutionPages.ifad(page("ifad.html"));
        assertEquals("President", find(ifad, "Alvaro Lario").role());
        assertEquals("Vice-President", find(ifad, "Gérardine Mukeshimana").role());
        assertTrue(ifad.size() >= 6, ifad.toString());
    }
    @Test
    void writesPersonsPositionsAndMandatesAndDropsOldEndedTerms() throws IOException {
        ItPepInstitutionsParser.Config config = ItPepInstitutionsParser.select(ItPepInstitutionsParser.loadConfig(),
                "banca_ditalia,garante_privacy");
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ItPepInstitutionsParser.ID_PREFIX, ItPepInstitutionsParser.TOPIC);

        List<ItPepInstitutionsParser.Outcome> outcomes = ItPepInstitutionsParser.readAll(config,
                url -> url.contains("bancaditalia") ? page("banca_ditalia.html") : page("garante_privacy.html"), null);
        String message = ItPepInstitutionsParser.convert(outcomes, dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertTrue(message.startsWith("9 personnes lues sur 2 institution(s)") || message.startsWith("8 personnes lues sur 2"), message);
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("topics").contains("gov.financial")));
    }

    @Test
    void anInstitutionWhosePageFailsIsReportedAndNoneReadableStops(@TempDir Path dir) throws IOException {
        ItPepInstitutionsParser.Config config = ItPepInstitutionsParser.select(ItPepInstitutionsParser.loadConfig(),
                "banca_ditalia,ivass");

        List<ItPepInstitutionsParser.Outcome> outcomes = ItPepInstitutionsParser.readAll(config, url -> {
            if (url.contains("ivass")) {
                throw new IOException("HTTP 503");
            }
            return page("banca_ditalia.html");
        }, dir);
        assertTrue(Files.exists(dir.resolve("banca_ditalia.html")));
        assertTrue(ItPepInstitutionsParser.convert(outcomes, new FtmDatasetBuilder("x", "role.pep"))
                .contains("Institutions non lues : IVASS (HTTP 503)"));
        assertThrows(IllegalStateException.class, () -> ItPepInstitutionsParser.readAll(config,
                url -> "<html>captcha</html>", dir));
        assertThrows(IllegalArgumentException.class, () -> ItPepInstitutionsParser.select(ItPepInstitutionsParser.loadConfig(), "nope"));
    }
}
