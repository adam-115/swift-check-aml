package com.swiftcheck.aml.plugins.lupep.chamberformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Conversion d'un extrait réel du fichier 106. Date du jour fixée au 17/10/2026 : avec 5 ans après la fin,
 * les mandats terminés avant le 17/10/2021 sont écartés.
 */
class LuPepChamberFormerParserTest {

    private static final LocalDate OLDEST_END_DATE =
            LuPepChamberFormerParser.oldestEndDate(Map.of(), LocalDate.of(2026, 10, 17));

    private static FtmDatasetBuilder result;

    @BeforeAll
    static void convertSample() throws Exception {
        result = convert(Path.of(LuPepChamberFormerParserTest.class.getResource("/chamber-former-deputies.csv").toURI()));
    }

    private static FtmDatasetBuilder convert(Path csv) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LuPepChamberFormerParser.ID_PREFIX, LuPepChamberFormerParser.TOPIC);
        new LuPepChamberFormerParser().convert(csv, OLDEST_END_DATE, dataset);
        return dataset;
    }

    private static List<FtmEntity> mandatesOf(String personId) {
        return result.entities().stream()
                .filter(e -> e.getSchema().equals("Occupancy") && e.getValues("holder").contains(personId))
                .toList();
    }

    @Test
    void defaultLimitIsFiveYears() {
        assertEquals(LocalDate.of(2021, 10, 17), OLDEST_END_DATE);
        assertThrows(IllegalArgumentException.class,
                () -> LuPepChamberFormerParser.oldestEndDate(Map.of("yearsAfterEnd", "cinq"), LocalDate.now()));
    }

    @Test
    void severalLinesOfTheSamePersonGiveOnePersonWithSeveralMandates() {
        // Martine Hansen : 3 lignes ; le mandat terminé en 2018 est écarté (plus de 5 ans)
        assertEquals(1, result.entities().stream()
                .filter(e -> e.getId().equals("lu-pep-chamber-former-Hansen-Martine-1965-12-10")).count());

        List<FtmEntity> mandates = mandatesOf("lu-pep-chamber-former-Hansen-Martine-1965-12-10");
        assertEquals(2, mandates.size());
        assertTrue(mandates.stream().allMatch(m -> m.getValues("status").equals(Set.of("ended"))));
    }

    @Test
    void mandatesEndedLongAgoAreDropped() {
        // Claude Adam : dernier mandat terminé le 16/04/2018
        assertTrue(result.entities().stream().noneMatch(e -> e.getId().equals("lu-pep-chamber-former-Adam-Claude")));
    }

    @Test
    void datesAreConverted() {
        FtmEntity mandate = mandatesOf("lu-pep-chamber-former-Ahmedova-Semiray-1981-04-21").get(0);
        assertEquals(Set.of("2019-10-08"), mandate.getValues("startDate"), "« 08/10/2019 » → 2019-10-08");
        assertEquals(Set.of("2023-10-24"), mandate.getValues("endDate"));
        assertEquals(0, result.qualityReport().getTotalRejected());
    }

    @Test
    void lineWithoutEndDateStopsTheConversion(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("broken.csv");
        Files.writeString(csv, "PERSON_TITLE,NAME,FIRSTNAME,START_DATE,END_DATE,POLITICAL_GROUP,BIRTH_DATE,POLITICAL_PARTY\n"
                + "Madame,Hansen,Martine,30/10/2018,,CSV,10/12/1965,CSV\n");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> convert(csv));
        assertTrue(error.getMessage().contains("ligne 2"), error.getMessage());
    }
}
