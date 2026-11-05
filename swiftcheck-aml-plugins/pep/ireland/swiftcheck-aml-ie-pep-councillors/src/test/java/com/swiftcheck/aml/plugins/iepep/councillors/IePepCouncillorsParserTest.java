package com.swiftcheck.aml.plugins.iepep.councillors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Listes réelles de Dublin City Council (Windows-1252, 63 élus) et de Dún Laoghaire-Rathdown (UTF-8 avec BOM, 40 élus), 5/11/2026. */
class IePepCouncillorsParserTest {

    private static final Map<String, String> SOURCES = Map.of("Dublin City Council", "dcc", "Dún Laoghaire-Rathdown", "dlr");

    @Test
    void readsBothListsDespiteTheirDifferentFormats(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir);

        assertEquals(103, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void decodesWindows1252AndRemovesTheCllrTitle(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir);

        FtmEntity ali = person(dataset, "Ammar Ali");
        assertEquals(Set.of("role.pep"), ali.getValues("topics"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("description").stream().anyMatch(d -> d.contains("Parti : Fianna Fáil"))), "é décodé depuis Windows-1252");
        assertTrue(person(dataset, "Marie Baker").getValues("position").contains("Councillor, Dún Laoghaire-Rathdown"));
        assertTrue(person(dataset, "Barry Saul") != null, "espaces doubles après « Cllr » nettoyés");
    }

    @Test
    void parsesQuotedFieldsAndSkipsBlankLines() {
        var rows = IePepCouncillorsParser.parseCsv("Name,Party\n\"Doe, John\",\"Fine \"\"Gael\"\"\"\n\n,\n");
        assertEquals(2, rows.size());
        assertEquals("Doe, John", rows.get(1).get(0));
        assertEquals("Fine \"Gael\"", rows.get(1).get(1));
    }

    @Test
    void sourcesParameterIsValidated() {
        assertEquals(2, IePepCouncillorsParser.sources("A|http://a.csv; B|http://b.csv").size());
        assertThrows(IllegalStateException.class, () -> IePepCouncillorsParser.sources(" "));
        assertThrows(IllegalStateException.class, () -> IePepCouncillorsParser.sources("sans-adresse"));
    }

    @Test
    void failsClearlyWhenTheNameColumnIsMissing(@TempDir Path rawDir) throws Exception {
        java.nio.file.Files.writeString(rawDir.resolve("council-0.csv"), "Nom,Parti\nX,Y\n");
        assertThrows(IllegalStateException.class, () -> new IePepCouncillorsParser().convert(rawDir,
                Map.of("Conseil", "http://x"), new FtmDatasetBuilder(IePepCouncillorsParser.ID_PREFIX)));
    }

    private static FtmDatasetBuilder run(Path rawDir) throws Exception {
        // ordre fixe : Dublin City Council puis Dún Laoghaire-Rathdown
        java.util.LinkedHashMap<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("Dublin City Council", "dcc");
        sources.put("Dún Laoghaire-Rathdown", "dlr");
        IePepCouncillorsParser.download(url -> bytes(url + ".csv"), sources, rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(IePepCouncillorsParser.ID_PREFIX, IePepCouncillorsParser.TOPIC);
        new IePepCouncillorsParser().convert(rawDir, sources, dataset);
        return dataset;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow(() -> new AssertionError("Personne absente : " + name));
    }

    private static byte[] bytes(String file) throws IOException {
        try (InputStream in = IePepCouncillorsParserTest.class.getResourceAsStream("/" + file)) {
            return in.readAllBytes();
        }
    }
}
