package com.swiftcheck.aml.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class PepIndexServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private static List<String> index(Path dir, String... lines) throws Exception {
        Path f = dir.resolve("ds.ndjson");
        Files.write(f, List.of(lines), StandardCharsets.UTF_8);
        Path out = dir.resolve("index.ndjson");
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            PepIndexService.write("de_pep_test", DatasetFile.read(f), w, TODAY);
        }
        return Files.readAllLines(out);
    }

    @Test
    void statusFollowsOccupancies(@TempDir Path dir) throws Exception {
        List<String> lines = index(dir,
                "{\"id\":\"p1\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Anna Aktiv\"],\"topics\":[\"role.pep\"],\"position\":[\"Ministerin\"]}}",
                "{\"id\":\"o1\",\"schema\":\"Occupancy\",\"properties\":{\"holder\":[\"p1\"],\"status\":[\"current\"]}}",
                "{\"id\":\"p2\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Bernd Bald\"],\"topics\":[\"role.pep\"]}}",
                "{\"id\":\"o2\",\"schema\":\"Occupancy\",\"properties\":{\"holder\":[\"p2\"],\"status\":[\"ended\"],\"endDate\":[\"2026-03-31\"]}}",
                "{\"id\":\"p3\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Carl Alt\"],\"topics\":[\"role.pep\"]}}",
                "{\"id\":\"o3\",\"schema\":\"Occupancy\",\"properties\":{\"holder\":[\"p3\"],\"status\":[\"ended\"],\"endDate\":[\"1998-10-26\"]}}",
                "{\"id\":\"p4\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Dora Liste\"],\"topics\":[\"role.pep\"]}}",
                "{\"id\":\"p5\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Emil Nahe\"],\"topics\":[\"role.rca\"]}}");
        assertEquals(4, lines.size(), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Anna Aktiv") && l.contains("\"status\":\"ACTIVE_PEP\"")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Bernd Bald") && l.contains("\"status\":\"COOLING\"")
                && l.contains("\"endDate\":\"2026-03-31\"")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Carl Alt") && l.contains("\"status\":\"EXPIRED\"")));
        // Liste des titulaires actuels sans mandat détaillé : en fonction.
        assertTrue(lines.stream().anyMatch(l -> l.contains("Dora Liste") && l.contains("\"status\":\"ACTIVE_PEP\"")));
        assertTrue(lines.stream().noneMatch(l -> l.contains("Emil Nahe")));
    }

    @Test
    void receiptIsStableAndSmall(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("index.ndjson");
        Files.writeString(index, "{}\n");
        var result = new PepIndexService.Result(index, 1, 1, "abc", java.util.Map.of("de_pep_test", 1));
        Path a = PepIndexService.writeReceipt(result, Files.createDirectories(dir.resolve("a")));
        Path b = PepIndexService.writeReceipt(result, Files.createDirectories(dir.resolve("b")));
        assertEquals(Files.readString(a), Files.readString(b));
        assertTrue(Files.readString(a).contains("\"de_pep_test\" : 1"), Files.readString(a));
    }

    /**
     * Index réel, lancé à la main : {@code -Dpep.yenteDir=<dossier data de Yente> -Dpep.indexOut=<fichier>}.
     * Lit tous les jeux dont le nom contient « _pep_ » (sauf de_pep_lobbyregister).
     */
    @Test
    @EnabledIfSystemProperty(named = "pep.yenteDir", matches = ".+")
    void buildRealIndex() throws Exception {
        Path root = Path.of(System.getProperty("pep.yenteDir"));
        Path out = Path.of(System.getProperty("pep.indexOut"));
        int total = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out); var dirs = Files.list(root)) {
            for (Path d : dirs.sorted().toList()) {
                String ds = d.getFileName().toString();
                Path f = d.resolve(ds + ".ndjson");
                if (!ds.contains("_pep_") || ds.equals("de_pep_lobbyregister") || !Files.exists(f)) {
                    continue;
                }
                total += PepIndexService.write(ds, DatasetFile.read(f), w, LocalDate.now());
            }
        }
        System.out.println("Index PEP : " + total + " PEP -> " + out);
        assertTrue(total > 0);
    }
}
