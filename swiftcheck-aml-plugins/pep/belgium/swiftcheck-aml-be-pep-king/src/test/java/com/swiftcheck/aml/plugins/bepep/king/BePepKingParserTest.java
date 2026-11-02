package com.swiftcheck.aml.plugins.bepep.king;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page « Le Roi » de monarchie.be et réponse Wikidata réelles (02/11/2026) : le roi Philippe. */
class BePepKingParserTest {

    @Test
    void theKingComesFromWikidataCheckedAgainstTheOfficialPage(@TempDir Path rawDir) throws Exception {
        copy(rawDir, text(BePepKingParser.WIKIDATA_FILE));
        FtmDatasetBuilder dataset = dataset();

        BePepKingParser.King king = new BePepKingParser().convert(rawDir, BePepKingParser.DEFAULT_PAGE_URL, dataset);

        assertEquals("Philippe de Belgique", king.name());
        assertEquals("2013-07-21", king.start());
        assertEquals("1960-04-15", king.birthDate());
        assertEquals("Bruxelles", king.birthPlace(), "la page officielle fait foi (Wikidata : Laeken)");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        FtmEntity person = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("Q155004"), person.getValues("wikidataId"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        assertTrue(dataset.media().isEmpty(), "pas de photo de groupe");
    }

    @Test
    void stopsWhenWikidataDoesNotMatchTheOfficialPage(@TempDir Path rawDir) throws Exception {
        copy(rawDir, text(BePepKingParser.WIKIDATA_FILE).replace("1960-04-15", "1934-06-06"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new BePepKingParser().convert(rawDir, BePepKingParser.DEFAULT_PAGE_URL, dataset()));
        assertTrue(e.getMessage().contains("changement de règne"));
    }

    @Test
    void stopsWhenWikidataHasNoSingleKing(@TempDir Path rawDir) throws Exception {
        copy(rawDir, "{\"results\":{\"bindings\":[]}}");
        assertThrows(IllegalStateException.class,
                () -> new BePepKingParser().convert(rawDir, BePepKingParser.DEFAULT_PAGE_URL, dataset()));
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(BePepKingParser.ID_PREFIX, BePepKingParser.TOPIC);
    }

    private static void copy(Path rawDir, String wikidata) throws IOException {
        Files.writeString(rawDir.resolve(BePepKingParser.PAGE_FILE), text(BePepKingParser.PAGE_FILE));
        Files.writeString(rawDir.resolve(BePepKingParser.WIKIDATA_FILE), wikidata);
    }

    private static String text(String file) throws IOException {
        try (InputStream in = BePepKingParserTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
