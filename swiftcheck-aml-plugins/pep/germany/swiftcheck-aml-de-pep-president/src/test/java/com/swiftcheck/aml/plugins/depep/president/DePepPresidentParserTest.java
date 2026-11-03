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
        assertEquals(Set.of("Président fédéral (Bundespräsident)"), position.getValues("name"));
        assertEquals(Set.of("de"), position.getValues("country"));

        List<FtmEntity> occupancies = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertEquals(1, occupancies.size());
        assertEquals(Set.of("2017"), occupancies.get(0).getValues("startDate"));
    }
}

