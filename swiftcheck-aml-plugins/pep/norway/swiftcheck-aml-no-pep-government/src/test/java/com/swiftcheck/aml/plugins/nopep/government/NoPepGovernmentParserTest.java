package com.swiftcheck.aml.plugins.nopep.government;

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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Réponse réelle de {@code data.stortinget.no/eksport/regjering} (6 novembre 2026) : 20 membres du gouvernement. */
class NoPepGovernmentParserTest {

    private static final String API = "https://data.stortinget.no/eksport";

    @Test
    void downloadsTheCabinetFile(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        NoPepGovernmentParser.download(url -> {
            calls.add(url);
            return resource();
        }, API + "/", rawDir);

        assertEquals(List.of(API + "/regjering"), calls);
        assertTrue(Files.exists(rawDir.resolve("regjering.xml")));
    }

    @Test
    void readsThePrimeMinisterAndTheMinisters() {
        List<NoPepGovernmentParser.Member> members = NoPepGovernmentParser.members(resource());

        assertEquals(20, members.size());
        var pm = members.stream().filter(m -> "Statsminister".equals(m.title())).findFirst().orElseThrow();
        assertEquals("Jonas Gahr Støre", pm.fullName());
        assertEquals("1960-08-25", pm.birthDate());
        assertEquals("Arbeiderpartiet", pm.party());
        assertTrue(members.stream().anyMatch(m -> "Jan Christian Vestre".equals(m.fullName())
                && "Helse- og omsorgsdepartementet".equals(m.department())));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("regjering.xml"), resource(), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(NoPepGovernmentParser.ID_PREFIX, NoPepGovernmentParser.TOPIC);

        int n = new NoPepGovernmentParser().convert(rawDir, dataset);

        assertEquals(20, n);
        FtmEntity pm = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Jonas Gahr Støre")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), pm.getValues("topics"));
        assertEquals(Set.of("1960-08-25"), pm.getValues("birthDate"));
        assertEquals(Set.of("male"), pm.getValues("gender"));
        assertEquals(20, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        assertEquals(20, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenTheFileHasNoMember(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("regjering.xml"), "<regjeringsmedlemmer_oversikt/>", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> new NoPepGovernmentParser()
                .convert(rawDir, new FtmDatasetBuilder(NoPepGovernmentParser.ID_PREFIX, NoPepGovernmentParser.TOPIC)));
    }

    private static String resource() {
        try (InputStream in = NoPepGovernmentParserTest.class.getResourceAsStream("/regjering.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
