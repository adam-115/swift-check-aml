package com.swiftcheck.aml.plugins.ropep.army;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepArmedForcesParserTest {

    private static final Path FIXTURE = Path.of("src/test/resources/defense-conducere.html");

    @Test
    void extractsGeneralsFromRealPage() throws Exception {
        List<String[]> found = RoPepArmedForcesParser.extract(Files.readString(FIXTURE, StandardCharsets.UTF_8));
        assertTrue(found.size() >= 7, "au moins 7 officiers attendus, trouvés : " + found.size());
        assertTrue(found.stream().anyMatch(f -> f[0].equals("Gheorghiță Vlad")));
        assertTrue(found.stream().anyMatch(f -> f[0].equals("Adrian Brînză")), "entités HTML décodées");
        assertEquals(found.size(), found.stream().map(f -> f[0]).distinct().count());
    }

    @Test
    void convertsExtractedOfficers(@TempDir Path dir) throws Exception {
        List<String[]> found = RoPepArmedForcesParser.extract(Files.readString(FIXTURE, StandardCharsets.UTF_8));
        ObjectMapper om = new ObjectMapper();
        var root = om.createObjectNode();
        var arr = root.putArray("officers");
        for (String[] f : found) {
            arr.addObject().put("name", f[0]).put("position", f[1]).put("branch", "Statul Major al Apărării");
        }
        Path raw = dir.resolve("raw.json");
        Files.writeString(raw, om.writeValueAsString(root), StandardCharsets.UTF_8);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-army", "role.pep");
        assertEquals(found.size(), new RoPepArmedForcesParser().convert(raw, dataset));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
    }
}
