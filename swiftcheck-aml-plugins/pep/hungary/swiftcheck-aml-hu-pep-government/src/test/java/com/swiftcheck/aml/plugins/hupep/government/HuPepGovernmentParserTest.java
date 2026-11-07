package com.swiftcheck.aml.plugins.hupep.government;

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

/** Section « A kormány tagjai » réelle de kormany.hu/kormanyzat (7 novembre 2026) : le Gouvernement de Magyar Péter. */
class HuPepGovernmentParserTest {

    @Test
    void readsTheMembersAndStopsBeforeTheSpokespersons() {
        var members = HuPepGovernmentParser.members(resource("members.html"));

        assertEquals(17, members.size());
        assertEquals("Magyar Péter", members.get(0).name());
        assertEquals("miniszterelnök", members.get(0).role());
        assertEquals("külügyminiszter, miniszterelnök-helyettes", members.get(1).role(), "fonction composée");
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Kátai-Németh Vilmos") && m.role().equals("szociális és családügyi miniszter")));
        assertTrue(members.stream().noneMatch(m -> m.role().equals("kormányszóvivő")), "porte-parole non repris");
        assertEquals("Vitézy Dávid", members.get(16).name());
    }

    @Test
    void addsTheGivenNameFirstAlias() {
        assertEquals(Set.of("Péter Magyar"), HuPepGovernmentParser.aliases("Magyar Péter"));
        assertTrue(HuPepGovernmentParser.aliases("Ruszin-Szendi Romulusz").contains("Romulusz Ruszin-Szendi"));
    }

    @Test
    void downloadsThePage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        HuPepGovernmentParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, HuPepGovernmentParser.DEFAULT_URL, rawDir);

        assertEquals(List.of("https://kormany.hu/kormanyzat"), calls);
        assertTrue(Files.exists(rawDir.resolve("members.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("members.html"), resource("members.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(HuPepGovernmentParser.ID_PREFIX, HuPepGovernmentParser.TOPIC);

        String message = new HuPepGovernmentParser().convert(rawDir, HuPepGovernmentParser.DEFAULT_URL, dataset);

        assertTrue(message.startsWith("17 membres"), message);
        assertEquals(17, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        FtmEntity magyar = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Magyar Péter")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), magyar.getValues("topics"));
        assertEquals(Set.of("Péter Magyar"), magyar.getValues("alias"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.head")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new HuPepGovernmentParser().convert(rawDir, "x", new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> HuPepGovernmentParser.members("<html/>"));
    }

    private static String resource(String name) {
        try (InputStream in = HuPepGovernmentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
