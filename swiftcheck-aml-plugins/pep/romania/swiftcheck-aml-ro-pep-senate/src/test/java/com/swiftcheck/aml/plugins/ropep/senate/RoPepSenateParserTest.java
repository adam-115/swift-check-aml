package com.swiftcheck.aml.plugins.ropep.senate;

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
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Fiches réelles de www.senat.ro/FisaSenatori.aspx (7 novembre 2026) : quelques sénateurs ; la page cite chacun deux fois. */
class RoPepSenateParserTest {

    @Test
    void readsTheCardsOnceEach() {
        var senators = RoPepSenateParser.senators(resource("senators.html"));

        assertTrue(senators.size() >= 3, senators.toString());
        assertEquals(senators.size(), senators.stream().map(RoPepSenateParser.Senator::id).distinct().count());
        var abrudean = senators.stream().filter(s -> s.listedName().equals("ABRUDEAN Mircea")).findFirst().orElseThrow();
        assertEquals("Mircea Abrudean", abrudean.name());
        assertEquals("1984-07-23", abrudean.birth());
        assertEquals("7ff0cd22-7bbb-4ca8-ada1-0473697459bd", abrudean.id());
        assertEquals("Circumscripţia electorală nr.13 Cluj", abrudean.district());
        assertTrue(abrudean.group().startsWith("Grupul parlamentar al Partidului Naţional Liberal"));
        var aldea = senators.stream().filter(s -> s.listedName().startsWith("ALDEA")).findFirst().orElseThrow();
        assertEquals("Valentina-Mariana Aldea", aldea.name(), "prénom composé");
    }

    @Test
    void reordersSurnamesInCapitals() {
        assertEquals("Mircea Abrudean", RoPepSenateParser.reorder("ABRUDEAN Mircea"));
        assertEquals("Anca Bahnean-Palfi", RoPepSenateParser.reorder("BAHNEAN-PALFI Anca"));
        assertEquals("Ion Maria Popa Ionescu", RoPepSenateParser.reorder("POPA IONESCU Ion Maria"));
        assertEquals("Mircea Abrudean", RoPepSenateParser.reorder("Mircea Abrudean"), "déjà dans l'ordre : inchangé");
    }

    @Test
    void downloadsThePage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        RoPepSenateParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, RoPepSenateParser.DEFAULT_URL, rawDir);

        assertEquals(List.of("https://www.senat.ro/FisaSenatori.aspx"), calls);
        assertTrue(Files.exists(rawDir.resolve("senators.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("senators.html"), resource("senators.html"), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(RoPepSenateParser.ID_PREFIX, RoPepSenateParser.TOPIC);

        String message = new RoPepSenateParser().convert(rawDir, dataset);

        assertTrue(message.contains("avec date de naissance"), message);
        Set<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of("Mircea Abrudean", "Valentina-Mariana Aldea")), names.toString());
        FtmEntity abrudean = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Mircea Abrudean")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), abrudean.getValues("topics"));
        assertEquals(Set.of("1984-07-23"), abrudean.getValues("birthDate"));
        assertEquals(Set.of("ABRUDEAN Mircea"), abrudean.getValues("alias"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChangedOrIsMissing(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new RoPepSenateParser().convert(rawDir, new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> RoPepSenateParser.senators("<html/>"));
    }

    private static String resource(String name) {
        try (InputStream in = RoPepSenateParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
