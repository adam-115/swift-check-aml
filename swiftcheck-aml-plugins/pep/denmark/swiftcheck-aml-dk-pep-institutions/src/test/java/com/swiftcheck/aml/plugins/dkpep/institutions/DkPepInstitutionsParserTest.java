package com.swiftcheck.aml.plugins.dkpep.institutions;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Page réelle « Organisation » de la Rigsrevisionen (6 novembre 2026). */
class DkPepInstitutionsParserTest {

    @Test
    void readsTheAuditorGeneralAndHerDeputyOnly() {
        var heads = DkPepInstitutionsParser.read(resource());

        assertEquals(2, heads.size(), "les chefs de département ne sont pas repris");
        assertEquals("Birgitte Hansen", heads.get(0).name());
        assertEquals(false, heads.get(0).deputy());
        assertEquals("Lene Schmidt", heads.get(1).name());
        assertTrue(heads.get(1).deputy());
        assertEquals("Vicerigsrevisor og afdelingschef", heads.get(1).title());
    }

    @Test
    void downloadsThePage(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        DkPepInstitutionsParser.download(url -> {
            calls.add(url);
            return "<html/>";
        }, DkPepInstitutionsParser.URL, rawDir);

        assertEquals(List.of(DkPepInstitutionsParser.URL), calls);
        assertTrue(Files.exists(rawDir.resolve("rigsrevisionen.html")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("rigsrevisionen.html"), resource(), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DkPepInstitutionsParser.ID_PREFIX, DkPepInstitutionsParser.TOPIC);

        int n = new DkPepInstitutionsParser().convert(rawDir, DkPepInstitutionsParser.URL, dataset);

        assertEquals(2, n);
        assertEquals(2, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void failsClearlyWhenThePageChanged() {
        assertThrows(IllegalStateException.class, () -> DkPepInstitutionsParser.read("<html><body>Forbidden</body></html>"));
    }

    private static String resource() {
        try (InputStream in = DkPepInstitutionsParserTest.class.getResourceAsStream("/rigsrevisionen.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
