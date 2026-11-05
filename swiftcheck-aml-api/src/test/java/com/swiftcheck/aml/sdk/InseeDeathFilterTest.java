package com.swiftcheck.aml.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class InseeDeathFilterTest {

    @Test
    void matchesDeceasedPersonFromCsv() throws Exception {
        String csv = "nom;prenom;sexe;date_naissance;code_insee_naissance;commune_naissance;date_deces;code_insee_deces;numero_acte\n"
                   + "GISCARD D ESTAING;VALERY RENE MARIE;1;19260202;99109;COBLENCE;20201202;41018;001\n"
                   + "CHIRAC;JACQUES RENE;1;19321129;75105;PARIS 5E ARRONDISSEMENT;20190926;75106;002\n";

        InseeDeathFilter filter = new InseeDeathFilter();
        filter.load(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertTrue(filter.isDeceased("GISCARD D ESTAING", "Valéry", "1926-02-02"));
        assertTrue(filter.isDeceased("CHIRAC", "Jacques", "1932-11-29"));
        assertFalse(filter.isDeceased("MACRON", "Emmanuel", "1977-12-21"));

        LocalDate deathDate = filter.getDeathDate("CHIRAC", "Jacques", "1932-11-29");
        assertNotNull(deathDate);
        assertEquals(LocalDate.of(2019, 9, 26), deathDate);
    }

    @Test
    void enrichesDatasetWithDeathDate() throws Exception {
        String csv = "nom;prenom;sexe;date_naissance;code_insee_naissance;commune_naissance;date_deces;code_insee_deces;numero_acte\n"
                   + "DUPONT;JEAN;1;19400510;75101;PARIS;20210315;75101;001\n";

        InseeDeathFilter filter = new InseeDeathFilter();
        filter.load(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        FtmDatasetBuilder dataset = new FtmDatasetBuilder("test-filter", "role.pep");
        FtmEntity p1 = dataset.newEntity("Person", "p1");
        p1.addValue("name", "Jean Dupont");
        p1.addValue("lastName", "Dupont");
        p1.addValue("firstName", "Jean");
        p1.addValue("birthDate", "1940-05-10");
        dataset.addEntity(p1);

        FtmEntity o1 = dataset.newEntity("Occupancy", "o1");
        o1.addValue("holder", p1.getId());
        o1.addValue("status", "ended");
        dataset.addEntity(o1);

        int count = filter.processDataset(dataset, true);
        assertEquals(1, count);
        // The former deceased PEP and ended occupancy should be safely removed to prevent false positives
        assertTrue(dataset.entities().isEmpty());
    }
}
