package com.swiftcheck.aml.plugin.be.intlorgs;

import com.swiftcheck.aml.plugin.be.intlorgs.BePepInternationalOrgsParser.LeaderRecord;
import com.swiftcheck.aml.plugin.be.intlorgs.BePepInternationalOrgsParser.OrgConfig;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BePepInternationalOrgsParserTest {

    private BePepInternationalOrgsParser parser;
    private String natoHtml;
    private String beneluxHtml;
    private String wcoHtml;

    @BeforeEach
    void setUp() throws IOException {
        parser = new BePepInternationalOrgsParser();
        natoHtml = readResource("/pages/nato.html");
        beneluxHtml = readResource("/pages/benelux.html");
        wcoHtml = readResource("/pages/wco.html");
    }

    private String readResource(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            assertNotNull(is, "La ressource de test " + path + " doit exister");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Doit extraire les dirigeants de l'OTAN, de l'Union Benelux et de l'OMD/WCO")
    void shouldExtractInternationalOrgLeaders() throws IOException {
        Map<String, OrgConfig> cfgByCode = parser.loadConfigs().stream()
                .collect(java.util.stream.Collectors.toMap(OrgConfig::code, c -> c));

        List<LeaderRecord> natoRecords = parser.parseOrganisationHtml(cfgByCode.get("nato"), natoHtml);
        List<LeaderRecord> beneluxRecords = parser.parseOrganisationHtml(cfgByCode.get("benelux"), beneluxHtml);
        List<LeaderRecord> wcoRecords = parser.parseOrganisationHtml(cfgByCode.get("wco"), wcoHtml);

        assertEquals(4, natoRecords.size(), "L'OTAN doit compter 4 hauts dirigeants sur la page Who we are");
        assertEquals(3, beneluxRecords.size(), "L'Union Benelux doit compter 3 membres du Collège des Secrétaires généraux");
        assertEquals(1, wcoRecords.size(), "L'OMD/WCO doit compter 1 Secrétaire général");

        List<LeaderRecord> allRecords = new ArrayList<>();
        allRecords.addAll(natoRecords);
        allRecords.addAll(beneluxRecords);
        allRecords.addAll(wcoRecords);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                BePepInternationalOrgsParser.ID_PREFIX, BePepInternationalOrgsParser.TOPIC);
        for (LeaderRecord record : allRecords) {
            BePepInternationalOrgsParser.writeRecord(record, dataset);
        }

        List<FtmEntity> entities = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertEquals(8, entities.size());

        // Vérifier Mark Rutte (OTAN)
        assertTrue(entities.stream().anyMatch(e ->
                        e.getValues("name").contains("Mark Rutte")
                                && e.getValues("position").stream().anyMatch(p -> p.contains("NATO Secretary General"))),
                "Doit contenir Mark Rutte (NATO Secretary General)");

        // Vérifier Ariadne PETRIDIS (Union Benelux)
        assertTrue(entities.stream().anyMatch(e ->
                        e.getValues("name").contains("Ariadne PETRIDIS")
                                && e.getValues("position").stream().anyMatch(p -> p.contains("Secrétaire générale"))),
                "Doit contenir Ariadne PETRIDIS (Secrétaire générale de l'Union Benelux)");

        // Vérifier Ian SAUNDERS (OMD/WCO)
        assertTrue(entities.stream().anyMatch(e ->
                        e.getValues("name").contains("Ian SAUNDERS")
                                && e.getValues("position").stream().anyMatch(p -> p.contains("Secrétaire général"))),
                "Doit contenir Ian SAUNDERS (Secrétaire général de l'OMD)");

        for (FtmEntity entity : entities) {
            assertEquals("Person", entity.getSchema());
            assertTrue(entity.getValues("topics").contains("role.pep"));
            assertTrue(entity.getValues("country").contains("be"));
        }
    }
}
