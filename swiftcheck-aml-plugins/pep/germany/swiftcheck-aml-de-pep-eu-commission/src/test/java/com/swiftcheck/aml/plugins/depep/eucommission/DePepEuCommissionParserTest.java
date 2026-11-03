package com.swiftcheck.aml.plugins.depep.eucommission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugins.depep.eucommission.DePepEuCommissionParser.Commissioner;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class DePepEuCommissionParserTest {

    @Test
    void readsPresidentInfo() throws Exception {
        String html;
        try (InputStream in = getClass().getResourceAsStream("/ursula-von-der-leyen.html")) {
            assertNotNull(in);
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Commissioner c = DePepEuCommissionParser.read(html);
        assertEquals("Ursula von der Leyen", c.name());
        assertEquals("Présidente", c.role());
        assertEquals("Présidence de la Commission européenne", c.portfolio());
        assertEquals("2024", c.startYear());
        assertEquals("2029", c.endYear());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepEuCommissionParser.ID_PREFIX, DePepEuCommissionParser.TOPIC);
        new DePepEuCommissionParser().convert(c, dataset);

        FtmEntity person = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("Ursula von der Leyen"), person.getValues("name"));
        assertEquals(Set.of("de"), person.getValues("country"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));

        FtmEntity position = dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())).findFirst().orElseThrow();
        assertEquals(Set.of("de"), position.getValues("country"));

        List<FtmEntity> occupancies = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).toList();
        assertEquals(1, occupancies.size());
    }
}

