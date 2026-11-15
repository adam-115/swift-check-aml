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

    @Test
    @DisplayName("Les prénoms composés sont lus en entier (« Jean-Pol », « Jean-Louis »), sans fusion des deux officiers")
    void shouldKeepHyphenatedFirstNames() {
        String html = "<p>Général-major Jean-Pol Baugnée, commandant de la Composante Terre.</p>"
                + "<p>Général de brigade Jean-Louis Crucifix, commandant de l'École royale militaire.</p>";

        List<String> names = BePepArmyParser.dropIncompleteNames(parser.parsePageHtml(html, "https://www.mil.be/fr/"))
                .stream().map(OfficerRecord::fullName).toList();

        assertEquals(List.of("Jean-Pol Baugnée", "Jean-Louis Crucifix"), names);
    }

    @Test
    @DisplayName("La fonction s'arrête à la virgule qui suit (pas de citation collée à l'intitulé)")
    void shouldStopRoleAtComma() {
        String html = "<p>Le général-major Michel Van Strythem, commandant du Cyber Command, complète : « Nous avons inauguré la première Cyber Defense Factory ».</p>";

        List<OfficerRecord> records = parser.parsePageHtml(html, "https://www.mil.be/fr/");

        assertEquals(1, records.size());
        assertEquals("Général-major – Commandant du Cyber Command", records.get(0).position());
    }

    @Test
    @DisplayName("Les mentions sans prénom d'un officier déjà extrait en entier sont écartées")
    void shouldDropIncompleteDuplicates() {
        List<OfficerRecord> records = new ArrayList<>();
        records.addAll(parser.parsePageHtml(hqHtml, "https://www.mil.be/fr/news/futur-quartier-general-a-evere-la-premiere-pierre-est-posee/"));
        records.addAll(parser.parsePageHtml(landHtml, "https://www.mil.be/fr/news/pose-de-la-premiere-pierre-des-nouvelles-infrastructures-pour-les-nouveaux-vehicules-de-la-force-terrestre-a-leopoldsburg/"));
        records.add(new OfficerRecord("Paul Van Goethem", "Paul", "Van Goethem", "Général-major", "Général-major – test", "https://www.mil.be/fr/"));

        List<String> names = BePepArmyParser.dropIncompleteNames(records).stream().map(OfficerRecord::fullName).toList();

        assertTrue(names.contains("Frédéric Goetynck"), names.toString());
        assertFalse(names.contains("Goetynck"), "Le nom d'un seul mot doit être écarté : " + names);
        assertTrue(names.contains("Paul Van Goethem"), names.toString());
        assertFalse(names.contains("Van Goethem"), "Le doublon sans prénom doit être écarté : " + names);
    }

    @Test
    @DisplayName("Un nom sans prénom est gardé quand c'est la seule mention de l'officier")
    void shouldKeepSurnameOnlyWhenAlone() {
        List<String> names = BePepArmyParser.dropIncompleteNames(
                        parser.parsePageHtml(landHtml, "https://www.mil.be/fr/"))
                .stream().map(OfficerRecord::fullName).toList();

        assertTrue(names.contains("Van Goethem"), names.toString());
    }

    @Test
    @DisplayName("La configuration armyPages est chargée exclusivement depuis les paramètres du parser")
    void shouldLoadConfigsStrictlyFromParameters() throws IOException {
        assertThrows(IllegalStateException.class, () -> parser.loadConfigs(null));
        assertThrows(IllegalStateException.class, () -> parser.loadConfigs(""));

        String json = readResource("/army-pages.json");
        List<BePepArmyParser.ArmyPageConfig> configs = parser.loadConfigs(json);
        assertEquals(5, configs.size());
    }
}
