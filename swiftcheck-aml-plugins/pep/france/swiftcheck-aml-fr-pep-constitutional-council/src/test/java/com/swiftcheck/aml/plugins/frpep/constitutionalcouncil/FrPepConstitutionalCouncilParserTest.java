package com.swiftcheck.aml.plugins.frpep.constitutionalcouncil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Pages réelles de conseil-constitutionnel.fr (octobre 2026) : la page des membres et la fiche de Richard Ferrand. */
class FrPepConstitutionalCouncilParserTest {

    private static final String URL = "https://www.conseil-constitutionnel.fr/le-conseil-constitutionnel/les-membres";

    @Test
    void readsEachMemberOnce() throws Exception {
        List<CouncilPages.Member> members = CouncilPages.members(resource("les-membres.html"));

        assertEquals(9, members.size(), "chaque membre apparaît deux fois sur la page");
        assertEquals(new CouncilPages.Member("richard-ferrand", "Richard FERRAND", true, "2025-02-20", "Président de la République"),
                members.get(0));
        assertEquals(1, members.stream().filter(CouncilPages.Member::president).count());
        CouncilPages.Member gourault = members.stream().filter(m -> m.slug().equals("jacqueline-gourault")).findFirst().orElseThrow();
        assertEquals("2022-03-01", gourault.nominationDate(), "« 1<sup>er</sup> mars 2022 »");
        assertTrue(members.stream().anyMatch(m -> "Présidente de l'Assemblée nationale".equals(m.nominatedBy())));
    }

    @Test
    void readsTheOathAndBirthOnTheProfile() throws Exception {
        assertEquals(new CouncilPages.Profile("2025-03-07", "1962-07-01", "Rodez"),
                CouncilPages.profile(resource("richard-ferrand.html")));
        assertArrayEquals(new String[] { "Jacques", "MÉZARD" }, CouncilPages.splitName("Jacques MÉZARD"));
    }

    @Test
    void convertsMembersAndThePresidency(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve(FrPepConstitutionalCouncilParser.MEMBERS_FILE), resource("les-membres.html"));
        Files.writeString(rawDir.resolve(FrPepConstitutionalCouncilParser.profileFile("richard-ferrand")), resource("richard-ferrand.html"));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepConstitutionalCouncilParser.ID_PREFIX, FrPepConstitutionalCouncilParser.TOPIC);

        assertEquals(9, new FrPepConstitutionalCouncilParser().convert(rawDir, URL, dataset));

        assertEquals(9, dataset.qualityReport().getEntities("Person"));
        assertEquals(10, dataset.qualityReport().getEntities("Occupancy"), "9 membres + la présidence");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        FtmEntity ferrand = entity(dataset, dataset.buildEntityId("richard-ferrand"));
        assertEquals(Set.of("1962-07-01"), ferrand.getValues("birthDate"));
        assertEquals(Set.of("FERRAND"), ferrand.getValues("lastName"));
        assertEquals(Set.of("Membre du Conseil constitutionnel", "Président du Conseil constitutionnel"), ferrand.getValues("position"));
        assertEquals(Set.of("role.pep"), ferrand.getValues("topics"));
        FtmEntity mandate = entity(dataset, dataset.buildEntityId("richard-ferrand", "member"));
        assertEquals(Set.of("2025-03-07"), mandate.getValues("startDate"), "date de prestation de serment");
        // Fiche non téléchargée : date de nomination
        assertEquals(Set.of("2019-02-22"), entity(dataset, dataset.buildEntityId("jacques-mezard", "member")).getValues("startDate"));
    }

    @Test
    void downloadsThePageThenEachProfile(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        List<String> failed = FrPepConstitutionalCouncilParser.download(url -> {
            calls.add(url);
            if (url.equals(URL)) {
                return resource("les-membres.html");
            }
            if (url.endsWith("alain-juppe")) {
                throw new IOException("HTTP 500");
            }
            return resource("richard-ferrand.html");
        }, URL, 0, rawDir);

        assertEquals(10, calls.size());
        assertEquals("https://www.conseil-constitutionnel.fr/membres/richard-ferrand", calls.get(1));
        assertEquals(List.of("https://www.conseil-constitutionnel.fr/membres/alain-juppe"), failed);
        List<SourceRecord> records = new FrPepConstitutionalCouncilParser()
                .readSourceRecords(new ParserContext("fr_pep_constitutional_council", null, rawDir, null, Map.of()));
        assertEquals(9, records.size());
        assertThrows(IOException.class, () -> FrPepConstitutionalCouncilParser.download(url -> "<html></html>", URL, 0, rawDir));
    }

    private static FtmEntity entity(FtmDatasetBuilder dataset, String id) {
        return dataset.entities().stream().filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }

    private static String resource(String file) throws IOException {
        try (InputStream in = FrPepConstitutionalCouncilParserTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
