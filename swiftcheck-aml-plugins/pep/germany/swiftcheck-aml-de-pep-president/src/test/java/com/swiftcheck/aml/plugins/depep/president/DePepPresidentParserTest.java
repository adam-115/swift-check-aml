package com.swiftcheck.aml.plugins.depep.president;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugins.depep.president.DePepPresidentParser.President;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class DePepPresidentParserTest {

    @Test
    void readsPresidentInfo() throws Exception {
        String html;
        try (InputStream in = getClass().getResourceAsStream("/steinmeier.html")) {
            assertNotNull(in);
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        President p = DePepPresidentParser.read(html);
        assertEquals("Frank-Walter Steinmeier", p.name());
        assertEquals("Frank-Walter", p.firstName());
        assertEquals("Steinmeier", p.lastName());
        assertEquals("1956", p.birthDate());
        assertEquals("Detmold", p.birthPlace());
        assertEquals("2017", p.startYear());
        assertTrue(p.photo().contains("Steinmeier-Portraet-Querformat.jpg"));

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepPresidentParser.ID_PREFIX, DePepPresidentParser.TOPIC);
        new DePepPresidentParser().convert(p, dataset);

        FtmEntity person = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("Frank-Walter Steinmeier"), person.getValues("name"));
        assertEquals(Set.of("de"), person.getValues("country"));
        assertEquals(Set.of("1956"), person.getValues("birthDate"));
        assertEquals(Set.of("Detmold"), person.getValues("birthPlace"));

        FtmEntity position = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("Bundespräsident"), position.getValues("name"));
        assertEquals(Set.of("de"), position.getValues("country"));

        List<FtmEntity> occupancies = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertEquals(1, occupancies.size());
        assertEquals(Set.of("2017"), occupancies.get(0).getValues("startDate"));
    }

    /** Page biographie (gabarit de novembre 2026) : titre « Bundespräsident Frank-Walter Steinmeier », og:title « Biographie ». */
    @Test
    void readsBiographyPage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        String html;
        try (InputStream in = getClass().getResourceAsStream("/biographie.html")) {
            assertNotNull(in);
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        President p = DePepPresidentParser.read(html);
        assertEquals("Frank-Walter Steinmeier", p.name());
        assertEquals("Steinmeier", p.lastName());
        assertEquals("1956", p.birthDate());
        assertEquals("Detmold", p.birthPlace());
        assertEquals("2017", p.startYear());
        assertEquals(DePepPresidentParser.DEFAULT_URL, p.pageUrl());
        assertTrue(p.photo().contains("Steinmeier-Portraet-Querformat.jpg"));

        java.nio.file.Path input = tempDir.resolve("biographie.html");
        java.nio.file.Files.writeString(input, html, StandardCharsets.UTF_8);
        java.nio.file.Path out = tempDir.resolve("out.ndjson");
        new DePepPresidentParser().parse(new com.swiftcheck.aml.plugin.ParserContext("de_pep_president", input, tempDir, out, java.util.Map.of()));
        String person = java.nio.file.Files.readAllLines(out).stream().filter(l -> l.contains("\"schema\":\"Person\"")).findFirst().orElseThrow();
        assertTrue(person.contains("pep_class: A1") && person.contains("level: FEDERAL") && person.contains("sha256: "));
    }

    @Test
    void pageTitleIsNeverTakenAsAName() {
        assertEquals(null, DePepPresidentParser.cleanName("Biographie"));
        assertEquals("Frank-Walter Steinmeier", DePepPresidentParser.cleanName("Bundespräsident Frank-Walter Steinmeier"));
        assertEquals("Julia Klöckner", DePepPresidentParser.cleanName("Bundespräsidentin Julia Klöckner"));
    }
}

