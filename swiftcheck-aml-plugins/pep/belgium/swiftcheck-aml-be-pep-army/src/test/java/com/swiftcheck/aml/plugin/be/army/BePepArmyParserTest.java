package com.swiftcheck.aml.plugin.be.army;

import com.swiftcheck.aml.plugin.be.army.BePepArmyParser.OfficerRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BePepArmyParserTest {

    private BePepArmyParser parser;
    private String chodHtml;
    private String hqHtml;
    private String landHtml;
    private String cyberHtml;

    @BeforeEach
    void setUp() throws IOException {
        parser = new BePepArmyParser();
        chodHtml = readResource("/mil-chod.html");
        hqHtml = readResource("/mil-hq.html");
        landHtml = readResource("/mil-land.html");
        cyberHtml = readResource("/mil-cyber.html");
    }

    private String readResource(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            assertNotNull(is, "La ressource de test " + path + " doit exister");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Doit extraire le Chef de la Défense (CHOD), le VCHOD et les officiers généraux commandants de composantes")
    void shouldExtractGeneralOfficers() {
        List<OfficerRecord> allRecords = new ArrayList<>();
        allRecords.addAll(parser.parsePageHtml(chodHtml, "https://www.mil.be/fr/news/voeux-de-nouvel-an-2026-la-defense-est-un-investissement-dans-notre-avenir/"));
        allRecords.addAll(parser.parsePageHtml(hqHtml, "https://www.mil.be/fr/news/futur-quartier-general-a-evere-la-premiere-pierre-est-posee/"));
        allRecords.addAll(parser.parsePageHtml(landHtml, "https://www.mil.be/fr/news/pose-de-la-premiere-pierre-des-nouvelles-infrastructures-pour-les-nouveaux-vehicules-de-la-force-terrestre-a-leopoldsburg/"));
        allRecords.addAll(parser.parsePageHtml(cyberHtml, "https://www.mil.be/fr/news/inauguration-d-une-deuxieme-cyber-defense-factory-a-bruges-sur-le-campus-de-howest/"));

        assertTrue(allRecords.size() >= 5,
                "Au moins 5 officiers généraux doivent être extraits, trouvé : " + allRecords.size());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepArmyParser.ID_PREFIX, BePepArmyParser.TOPIC);
        for (OfficerRecord record : allRecords) {
            BePepArmyParser.writeRecord(record, dataset);
        }

        List<FtmEntity> entities = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();

        // Vérifier le général aviateur Frederik Vansina (CHOD)
        assertTrue(entities.stream().anyMatch(e ->
                        e.getValues("name").contains("Frederik Vansina")
                                && e.getValues("position").stream().anyMatch(p -> p.contains("CHOD"))),
                "Doit contenir le général aviateur Frederik Vansina (Chief of Defence - CHOD)");

        // Vérifier le lieutenant-général Frédéric Goetynck (VCHOD)
        assertTrue(entities.stream().anyMatch(e ->
                        e.getValues("name").contains("Frédéric Goetynck")
                                && e.getValues("position").stream().anyMatch(p -> p.contains("VCHOD") || p.contains("Material Resources"))),
                "Doit contenir le lieutenant-général Frédéric Goetynck (VCHOD)");

        // Vérifier le général-major Michel Van Strythem (Cyber Command)
        assertTrue(entities.stream().anyMatch(e ->
                        e.getValues("name").contains("Michel Van Strythem")
                                && e.getValues("position").stream().anyMatch(p -> p.contains("Cyber Command"))),
                "Doit contenir le général-major Michel Van Strythem (commandant du Cyber Command)");

        for (FtmEntity entity : entities) {
            assertEquals("Person", entity.getSchema());
            assertTrue(entity.getValues("topics").contains("role.pep"));
            assertTrue(entity.getValues("country").contains("be"));
        }
    }
}
