package com.swiftcheck.aml.plugins.iepep.parliament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Extraits réels de l'API api.oireachtas.ie (novembre 2026) : la liste des chambres, sept membres du 34e Dáil
 * (Taoiseach, ministre des Finances, ancien ministre des Finances et ancienne députée, Ceann Comhairle…) et deux
 * sénateurs du 27e Seanad (un en fonction, un parti en mai 2026).
 */
class IePepParliamentParserTest {

    private static final String API = "https://api.oireachtas.ie/v1";

    @Test
    void readsOnlyTheCurrentHousesAndTheRecentlyEndedOnes(@TempDir Path rawDir) throws Exception {
        List<String> requested = new ArrayList<>();
        int read = IePepParliamentParser.download(fetcher(requested), API, LocalDate.of(2026, 11, 5), 12, rawDir);

        assertEquals(2, read, "34e Dáil et 27e Seanad ; les chambres terminées avant novembre 2025 sont ignorées");
        assertTrue(requested.contains(API + "/members?house_no=34&chamber=dail&limit=1000"));
        assertTrue(requested.contains(API + "/members?house_no=27&chamber=seanad&limit=1000"));
        assertEquals(3, requested.size(), "houses + 2 chambres");
    }

    @Test
    void keepsCurrentAndRecentlyFormerMembers(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, LocalDate.of(2026, 1, 15));

        FtmEntity taoiseach = person(dataset, "Micheál Martin");
        assertTrue(taoiseach.getValues("position").contains("Taoiseach"));
        assertEquals(Set.of("role.pep"), taoiseach.getValues("topics"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Taoiseach") && e.getValues("topics").contains("gov.head")));

        assertTrue(person(dataset, "Paschal Donohoe").getValues("position").contains("Teachta Dála (Member of Dáil Éireann)"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("endDate").contains("2025-11-21") && e.getValues("status").contains("ended")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void dropsFormerMembersAndOldOfficesAfterTheRetentionWindow(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, LocalDate.of(2026, 11, 5));

        assertTrue(dataset.entities().stream().noneMatch(e -> e.getValues("name").contains("Catherine Connolly")),
                "mandat terminé le 2025-10-25, plus de douze mois");
        assertTrue(dataset.entities().stream().anyMatch(e -> e.getValues("name").contains("Paschal Donohoe")));
        FtmEntity harris = person(dataset, "Simon Harris");
        assertTrue(harris.getValues("position").contains("Minister for Finance"));
        assertFalse(harris.getValues("position").contains("Minister for Foreign Affairs"),
                "fonction terminée le 2025-03-25, hors période");
    }

    @Test
    void writesPartyConstituencyAndPanelInTheMandate(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, LocalDate.of(2026, 1, 15));

        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("description").stream().anyMatch(d -> d.contains("Circonscription : Dublin South-West") && d.contains("Labour Party"))));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("description").stream().anyMatch(d -> d.contains("Panel : Administrative Panel"))));
    }

    @Test
    void failsClearlyWithoutAnyCurrentHouse(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> IePepParliamentParser.download(
                url -> "{\"results\":[]}", API, LocalDate.of(2026, 11, 5), 12, rawDir));
    }

    private static FtmDatasetBuilder run(Path rawDir, LocalDate today) throws Exception {
        IePepParliamentParser.download(fetcher(new ArrayList<>()), API, today, 12, rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(IePepParliamentParser.ID_PREFIX, IePepParliamentParser.TOPIC);
        new IePepParliamentParser().convert(rawDir, today, 12, dataset);
        return dataset;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow();
    }

    private static IePepParliamentParser.Fetcher fetcher(List<String> requested) {
        return url -> {
            requested.add(url);
            if (url.contains("/houses")) {
                return resource("houses.json");
            }
            return resource(url.contains("chamber=seanad") ? "members-seanad-27.json" : "members-dail-34.json");
        };
    }

    private static String resource(String name) {
        try (InputStream in = IePepParliamentParserTest.class.getResourceAsStream("/parliament/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
