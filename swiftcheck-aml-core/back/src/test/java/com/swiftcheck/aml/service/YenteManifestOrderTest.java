package com.swiftcheck.aml.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;

/**
 * Yente 5.5 retire de l'alias tous les index « yente-entities-<dataset>* » quand il réindexe <dataset>. Un
 * prolongement ({@code nl_pep_parliament_history}) doit donc passer après son dataset de base dans le manifest,
 * sinon il est supprimé comme orphelin : collections en tête, puis datasets par ordre alphabétique.
 */
class YenteManifestOrderTest {

    @TempDir
    Path dir;

    private final YenteManifestService service = new YenteManifestService();
    private ApplicationConfigEntity config;

    @BeforeEach
    void setUp() {
        config = new ApplicationConfigEntity();
        config.setYenteDataFolder(dir.resolve("data").toString());
        config.setYenteManifestPath(dir.resolve("manifest.yml").toString());
        config.setYenteContainerDataPath("/data");
    }

    @Test
    void republierUnDatasetDeBaseLeLaisseAvantSesProlongements() throws Exception {
        service.addDataset("nl_pep_parliament_history", "Historique", config);
        service.addDataset("nl_pep_parliament", "Députés", config);
        service.addDataset("be_pep_chamber", "Chambre", config);
        service.addDataset("nl_pep_parliament", "Députés", config);   // republication : ne part pas en fin de liste

        assertEquals(List.of("default", "be_pep_chamber", "nl_pep_parliament", "nl_pep_parliament_history"),
                datasetNames());
    }

    @Test
    void reindexDatasetRemetEnOrdreUnManifestDesordonne() throws Exception {
        Files.writeString(dir.resolve("manifest.yml"), """
                datasets:
                - name: "default"
                  title: "Toutes"
                  children: ["nl_pep_parliament_history", "nl_pep_parliament"]
                - name: "nl_pep_parliament_history"
                  title: "Historique"
                  path: "/data/nl_pep_parliament_history/nl_pep_parliament_history.ndjson"
                - name: "nl_pep_parliament"
                  title: "Députés"
                  path: "/data/nl_pep_parliament/nl_pep_parliament.ndjson"
                """);
        Path file = dir.resolve("data/nl_pep_parliament/nl_pep_parliament.ndjson");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}\n");

        service.reindexDataset("nl_pep_parliament", config);

        assertEquals(List.of("default", "nl_pep_parliament", "nl_pep_parliament_history"), datasetNames());
    }

    @Test
    void datasetNamesSansLesCollections() throws Exception {
        service.addDataset("nl_pep_parliament", "Députés", config);
        assertEquals(java.util.Set.of("nl_pep_parliament"), service.datasetNames(config));
    }

    @Test
    void datasetNamesLeveSiLeManifestEstIntrouvable() {
        var error = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> service.datasetNames(config));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("introuvable"), error.getMessage());
    }

    /** Noms des entrées {@code - name:} du manifest, dans l'ordre du fichier. */
    private List<String> datasetNames() throws Exception {
        Matcher m = Pattern.compile("(?m)^- name: \"?([^\"\\s]+)\"?").matcher(Files.readString(dir.resolve("manifest.yml")));
        List<String> names = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
