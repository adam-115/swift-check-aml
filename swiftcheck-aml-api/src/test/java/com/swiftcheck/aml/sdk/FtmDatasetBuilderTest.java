package com.swiftcheck.aml.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FtmDatasetBuilderTest {

    @Test
    void buildsStableIds() {
        FtmDatasetBuilder e = new FtmDatasetBuilder("un-data", "sanction");
        assertEquals("un-data-6908876", e.buildEntityId("6908876"));
        assertEquals("un-data-6908876-sanction", e.buildEntityId("6908876", "sanction"));
        assertEquals("lu-pep-Q42-lu-pos-depute", new FtmDatasetBuilder("lu-pep").buildEntityId("Q42", "lu pos député"));
        String longId = e.buildEntityId("x".repeat(200));
        assertEquals("un-data-".length() + 40, longId.length());
        assertThrows(IllegalArgumentException.class, () -> e.buildEntityId(" ", null));
    }

    @Test
    void normalizesRejectsAndAppliesTopics() {
        FtmDatasetBuilder e = new FtmDatasetBuilder("t", "sanction");
        FtmEntity p = e.newEntity("Person", "1")
                .addValue("name", "  ERIC   BADEGE ")
                .addValue("nationality", "Democratic Republic of the Congo")
                .addValue("nationality", "2016-10-13")
                .addValue("nationality", "n.d.")
                .addValue("birthDate", "1971")
                .addValue("birthDate", "not a date");
        e.addEntity(p);

        assertEquals(Set.of("ERIC BADEGE"), p.getValues("name"));
        assertEquals(Set.of("cd"), p.getValues("nationality"));
        assertEquals(Set.of("1971"), p.getValues("birthDate"));
        assertEquals(Set.of("sanction"), p.getValues("topics"));
        assertEquals(2, e.qualityReport().getTotalRejected());
        assertEquals(1, e.qualityReport().getRejectedByProperty().get("Person.nationality"));
    }

    @Test
    void topicsNotAppliedToLinkSchemas() {
        FtmDatasetBuilder e = new FtmDatasetBuilder("t", "sanction");
        FtmEntity s = e.newEntity("Sanction", "1", "sanction").addValue("entity", "t-1");
        e.addEntity(s);
        assertTrue(s.getValues("topics").isEmpty());
    }

    @Test
    void mergesDuplicateIdsAndRejectsSchemaConflicts() {
        FtmDatasetBuilder e = new FtmDatasetBuilder("t");
        e.addEntity(e.newEntity("Person", "1").addValue("name", "A"));
        e.addEntity(e.newEntity("Person", "1").addValue("alias", "B"));
        e.addEntity(e.newEntity("Organization", "1").addValue("name", "C"));
        e.addEntity(e.newEntity("Person", "2"));

        assertEquals(1, e.entities().size());
        FtmEntity merged = e.entities().iterator().next();
        assertEquals(Set.of("A"), merged.getValues("name"));
        assertEquals(Set.of("B"), merged.getValues("alias"));
        assertEquals(1, e.qualityReport().getMergedDuplicateCount());
        assertEquals(1, e.qualityReport().getIdConflictCount());
        assertEquals(1, e.qualityReport().getEmptyEntityCount());
    }

    @Test
    void rejectsPropertiesThatDoNotExistForTheSchema() {
        FtmDatasetBuilder e = new FtmDatasetBuilder("t");
        FtmEntity p = e.newEntity("Person", "1").addValue("nationalty", "France").addValue("imoNumber", "123");
        assertTrue(p.isEmpty());
        assertEquals(2, e.qualityReport().getTotalRejected());
        assertTrue(e.qualityReport().getRejectedSamples().get(0).contains("propriété inconnue"));
    }

    @Test
    void rejectsUnknownSchema() {
        FtmDatasetBuilder e = new FtmDatasetBuilder("t");
        assertThrows(IllegalArgumentException.class, () -> e.newEntity("Persn", "1"));
    }

    @Test
    void writesValidNdjsonAtomically(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder e = new FtmDatasetBuilder("t", "role.pep");
        e.addEntity(e.newEntity("Person", "1").addValue("name", "Jean \"Le\" Dupont\\").addValue("notes", "ligne1\nligne2"));
        Path out = dir.resolve("sub/out.ndjson");
        e.writeNdjsonFile(out);

        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertEquals("{\"id\":\"t-1\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Jean \\\"Le\\\" Dupont\\\\\"],"
                + "\"notes\":[\"ligne1 ligne2\"],\"topics\":[\"role.pep\"]}}", lines.get(0));
        assertTrue(Files.notExists(dir.resolve("sub/out.ndjson.tmp")));
    }
}
