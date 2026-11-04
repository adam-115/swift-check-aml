package com.swiftcheck.aml.plugins.espep.senado;

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

class EsPepSenadoParserTest {

    private static Path prepareRawDir(Path dir) throws IOException {
        try (InputStream in = EsPepSenadoParserTest.class.getResourceAsStream("/senadores-legis15.html")) {
            assertNotNull(in, "Ressource manquante : /senadores-legis15.html");
            Files.copy(in, dir.resolve(EsPepSenadoParser.HTML_FILE));
        }
        return dir;
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(EsPepSenadoParser.ID_PREFIX, EsPepSenadoParser.TOPIC);
    }

    @Test
    void convertsOfficialSenatorsList(@TempDir Path dir) throws Exception {
        Path rawDir = prepareRawDir(dir);
        FtmDatasetBuilder dataset = dataset();
        EsPepSenadoParser.Report report = new EsPepSenadoParser().convert(rawDir, dataset);

        assertTrue(report.count() >= 265, "Tous les sénateurs de la législature doivent être présents");
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        List<FtmEntity> persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).toList();
        assertEquals(report.count(), persons.size());

        for (FtmEntity person : persons) {
            assertEquals(Set.of("role.pep"), person.getValues("topics"));
            assertEquals(Set.of("es"), person.getValues("nationality"));
            assertEquals(Set.of("es"), person.getValues("country"));
            assertFalse(person.getValues("position").isEmpty());
            assertTrue(person.getValues("sourceUrl").iterator().next().contains("senado.es"));
        }

        // Vérification de sénateurs connus
        assertTrue(persons.stream().anyMatch(p -> p.getValues("name").iterator().next().toLowerCase().contains("abdelhakim")));
        assertTrue(persons.stream().anyMatch(p -> p.getValues("name").iterator().next().toLowerCase().contains("zubiaga")));
    }

    @Test
    void parsesHtmlRegexDetails(@TempDir Path dir) throws Exception {
        prepareRawDir(dir);
        List<EsPepSenadoParser.Senator> senators = EsPepSenadoParser.readHtml(dir.resolve(EsPepSenadoParser.HTML_FILE));
        assertTrue(senators.size() >= 300);
        EsPepSenadoParser.Senator first = senators.get(0);
        assertNotNull(first.id());
        assertNotNull(first.fullName());
        assertNotNull(first.groupCode());
        assertNotNull(first.mandate());
    }
}
