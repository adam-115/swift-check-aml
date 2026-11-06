package com.swiftcheck.aml.plugins.ltpep.seimas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Pages réelles de www.lrs.lt (6 novembre 2026) : trois membres de la page « Seimo nariai » (chaque membre y est cité deux fois)
 * et, pour deux d'entre eux, le tableau de naissance de leur fiche.
 */
class LtPepSeimasParserTest {

    private static final String LIST_URL = "https://www.lrs.lt/sip/portal.show?p_r=35299&p_k=1";

    @Test
    void listsEachMemberOnceWithFactionAndAddress() {
        var members = LtPepSeimasParser.listed(resource("list.html"));

        assertEquals(3, members.size());
        var alekna = members.get(0);
        assertEquals("79162", alekna.id());
        assertEquals("Virgilijus Alekna", alekna.name());
        assertEquals("Liberalų sąjūdžio frakcija", alekna.faction());
        assertEquals("https://www.lrs.lt/sip/portal.show?p_r=35299&p_k=1&p_a=498&p_asm_id=79162", alekna.url());
        assertEquals("member-79162.html", alekna.file());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Arvydas Anušauskas")));
    }

    @Test
    void readsBirthDateAndPlaceFromTheMemberPage() {
        var alekna = LtPepSeimasParser.birth(resource("member-79162.html"));
        assertEquals("1972-02-13", alekna.date());
        assertEquals("Terpeikiai, Kupiškio r., Lietuva", alekna.place());

        var aleknaviciene = LtPepSeimasParser.birth(resource("member-90939.html"));
        assertTrue(aleknaviciene.date() != null && aleknaviciene.date().matches("\\d{4}-\\d{2}-\\d{2}"), aleknaviciene.toString());
    }

    @Test
    void convertsLithuanianDatesWithGenitiveMonths() {
        assertEquals("1972-02-13", LtPepSeimasParser.isoDate("1972 m. vasario 13 d."));
        assertEquals("1980-08-01", LtPepSeimasParser.isoDate("1980 m. rugpjūčio 1 d."));
        assertEquals("1965-12-24", LtPepSeimasParser.isoDate("1965 m. gruodžio 24 d."));
        assertNull(LtPepSeimasParser.isoDate("1972 vasario 13"));
        assertNull(LtPepSeimasParser.isoDate("1972 m. februar 13 d."));
    }

    @Test
    void downloadsTheListAndOptionallyEveryMemberPage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        LtPepSeimasParser.download(url -> {
            calls.add(url);
            return url.equals(LIST_URL) ? resource("list.html") : "<html/>";
        }, LIST_URL, true, rawDir);
        assertEquals(4, calls.size(), calls.toString());
        assertEquals(LIST_URL, calls.get(0));
        assertTrue(Files.exists(rawDir.resolve("member-90939.html")));

        List<String> listOnly = new ArrayList<>();
        LtPepSeimasParser.download(url -> {
            listOnly.add(url);
            return resource("list.html");
        }, LIST_URL, false, rawDir);
        assertEquals(List.of(LIST_URL), listOnly, "details=false : une seule requête");
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        for (String f : List.of("list.html", "member-79162.html", "member-90939.html")) {
            Files.writeString(rawDir.resolve(f), resource(f), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LtPepSeimasParser.ID_PREFIX, LtPepSeimasParser.TOPIC);

        String message = new LtPepSeimasParser().convert(rawDir, dataset);

        assertEquals("3 membres du Seimas, dont 2 avec date de naissance. Source : Seimas, www.lrs.lt.", message);
        Set<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
        assertEquals(Set.of("Virgilijus Alekna", "Vaida Aleknavičienė", "Arvydas Anušauskas"), names);
        FtmEntity alekna = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Virgilijus Alekna")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), alekna.getValues("topics"));
        assertEquals(Set.of("1972-02-13"), alekna.getValues("birthDate"));
        assertEquals(3, dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count());
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("description").contains("Groupe : Liberalų sąjūdžio frakcija")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) throws Exception {
        assertThrows(IllegalStateException.class, () -> new LtPepSeimasParser().convert(rawDir, new FtmDatasetBuilder("x", "role.pep")));
        Files.writeString(rawDir.resolve("list.html"), "<html/>", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new LtPepSeimasParser().convert(rawDir, new FtmDatasetBuilder("x", "role.pep")));
    }

    private static String resource(String name) {
        try (InputStream in = LtPepSeimasParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
