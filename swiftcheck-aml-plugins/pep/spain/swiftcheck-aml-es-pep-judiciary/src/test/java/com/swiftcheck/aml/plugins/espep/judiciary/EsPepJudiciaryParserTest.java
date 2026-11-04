package com.swiftcheck.aml.plugins.espep.judiciary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class EsPepJudiciaryParserTest {

    private static Path prepareRawDir(Path dir) throws IOException {
        String[] files = {
                "tribunal-supremo.html",
                "doc-BOE-A-2024-15351.xml",
                "doc-BOE-A-2024-15352.xml",
                "doc-BOE-A-2024-17786.xml",
                "doc-BOE-A-2023-712.xml",
                "doc-BOE-A-2023-713.xml",
                "doc-BOE-A-2024-15661.xml"
        };
        for (String f : files) {
            try (InputStream in = EsPepJudiciaryParserTest.class.getResourceAsStream("/judiciary/" + f)) {
                assertNotNull(in, "Ressource manquante : /judiciary/" + f);
                Files.copy(in, dir.resolve(f));
            }
        }
        return dir;
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(EsPepJudiciaryParser.ID_PREFIX, EsPepJudiciaryParser.TOPIC);
    }

    @Test
    void convertsOfficialJudiciaryDocuments(@TempDir Path dir) throws Exception {
        Path rawDir = prepareRawDir(dir);
        FtmDatasetBuilder dataset = dataset();
        EsPepJudiciaryParser.Report report = new EsPepJudiciaryParser().convert(rawDir, dataset);

        assertTrue(report.totalJudges() >= 30, "Au moins 30 magistrats attendus");
        assertTrue(report.cgpjCount() >= 20, "Au moins 20 membres du CGPJ");
        assertTrue(report.tsCount() >= 10, "Au moins 10 membres du Tribunal Supremo");

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        List<FtmEntity> persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).toList();
        assertFalse(persons.isEmpty());

        for (FtmEntity person : persons) {
            assertEquals(Set.of("role.pep"), person.getValues("topics"));
            assertEquals(Set.of("es"), person.getValues("nationality"));
            assertFalse(person.getValues("position").isEmpty());
        }

        // Vérification de la Présidente du Tribunal Supremo et CGPJ
        FtmEntity presidenta = persons.stream()
                .filter(p -> p.getValues("name").iterator().next().contains("Perelló"))
                .findFirst().orElseThrow();
        assertNotNull(presidenta);

        // Vérification du Président du TC
        FtmEntity presTc = persons.stream()
                .filter(p -> p.getValues("name").iterator().next().contains("Conde-Pumpido"))
                .findFirst().orElseThrow();
        assertNotNull(presTc);
    }

    @Test
    void parsesTribunalSupremoHtmlDirectly(@TempDir Path dir) throws Exception {
        prepareRawDir(dir);
        List<EsPepJudiciaryParser.Judge> list = EsPepJudiciaryParser.parseTribunalSupremo(dir.resolve("tribunal-supremo.html"));
        assertTrue(list.size() >= 12);
        assertTrue(list.stream().anyMatch(j -> j.cleanName().contains("Berberoff")));
        assertTrue(list.stream().anyMatch(j -> j.positionName().contains("Vicepresidente")));
    }
}
