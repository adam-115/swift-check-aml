package com.swiftcheck.aml.plugin.be.diplomats;

import com.swiftcheck.aml.plugin.be.diplomats.BePepDiplomatsParser.DiplomatRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BePepDiplomatsParserTest {

    private BePepDiplomatsParser parser;
    private String sampleJson;

    @BeforeEach
    void setUp() throws IOException {
        parser = new BePepDiplomatsParser();
        try (InputStream is = getClass().getResourceAsStream("/diplomats-posts.json")) {
            assertNotNull(is, "La ressource de test diplomats-posts.json doit exister");
            sampleJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Doit extraire les ambassadeurs et consuls généraux de carrière en excluant les consuls honoraires et les coordonnées")
    void shouldExtractCareerDiplomatsOnly() throws Exception {
        List<DiplomatRecord> records = parser.parsePostsJson(sampleJson);
        assertFalse(records.isEmpty(), "La liste des diplomates extraits ne doit pas être vide");
        assertTrue(records.size() >= 3, "Doit extraire au moins l'ambassadeur et les consuls généraux en France");

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                BePepDiplomatsParser.ID_PREFIX, BePepDiplomatsParser.TOPIC);
        for (DiplomatRecord record : records) {
            BePepDiplomatsParser.writeRecord(record, dataset);
        }

        List<FtmEntity> entities = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertEquals(records.size(), entities.size());

        // Vérifier Jo INDEKEU (Ambassadeur à Paris)
        assertTrue(entities.stream().anyMatch(e ->
                        e.getValues("name").contains("Jo INDEKEU")
                                && e.getValues("position").stream().anyMatch(p -> p.contains("Ambassadeur") && p.contains("Paris"))),
                "Doit contenir Jo INDEKEU (Ambassadeur de Belgique à Paris)");

        // Vérifier qu'aucun consul honoraire n'a été extrait
        assertFalse(entities.stream().anyMatch(e ->
                        e.getValues("position").stream().anyMatch(p -> p.toLowerCase().contains("honoraire"))),
                "Aucun consul honoraire ne doit être extrait");

        for (FtmEntity entity : entities) {
            assertEquals("Person", entity.getSchema());
            assertTrue(entity.getValues("topics").contains("role.pep"));
            assertTrue(entity.getValues("country").contains("be"));
            assertTrue(entity.getValues("address").isEmpty(), "Aucune adresse ne doit être extraite");
            assertTrue(entity.getValues("email").isEmpty(), "Aucun email ne doit être extrait");
            assertTrue(entity.getValues("phone").isEmpty(), "Aucun téléphone ne doit être extrait");
        }
    }
}
