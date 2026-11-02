package com.swiftcheck.aml.plugin.be.regionalparliaments;

import com.swiftcheck.aml.plugin.be.regionalparliaments.BePepRegionalParliamentsParser.DeputyRecord;
import com.swiftcheck.aml.plugin.be.regionalparliaments.BePepRegionalParliamentsParser.ParliamentConfig;
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

class BePepRegionalParliamentsParserTest {

    private BePepRegionalParliamentsParser parser;
    private String pfwbHtml;
    private String pdgMembersHtml;
    private String pdgPraesidiumHtml;

    @BeforeEach
    void setUp() throws IOException {
        parser = new BePepRegionalParliamentsParser();
        pfwbHtml = readResource("/pfwb.html");
        pdgMembersHtml = readResource("/pdg-members.html");
        pdgPraesidiumHtml = readResource("/pdg-praesidium.html");
    }

    private String readResource(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            assertNotNull(is, "La ressource de test " + path + " doit exister");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Doit extraire les 94 députés du Parlement de la Fédération Wallonie-Bruxelles et les 25 membres du PDG")
    void shouldExtractPfwbAndPdgMembers() throws IOException {
        Map<String, ParliamentConfig> cfgByCode = parser.loadConfigs().stream()
                .collect(java.util.stream.Collectors.toMap(ParliamentConfig::code, c -> c));

        List<DeputyRecord> pfwbRecords = parser.parseParliamentHtml(cfgByCode.get("pfwb"), pfwbHtml, null);
        List<DeputyRecord> pdgRecords = parser.parseParliamentHtml(cfgByCode.get("pdg"), pdgMembersHtml, pdgPraesidiumHtml);

        assertEquals(94, pfwbRecords.size(), "Le PFWB doit compter 94 députés");
        assertEquals(25, pdgRecords.size(), "Le Parlement de la Communauté germanophone doit compter 25 membres");

        List<DeputyRecord> allRecords = new ArrayList<>();
        allRecords.addAll(pfwbRecords);
        allRecords.addAll(pdgRecords);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                BePepRegionalParliamentsParser.ID_PREFIX, BePepRegionalParliamentsParser.TOPIC);
        for (DeputyRecord record : allRecords) {
            BePepRegionalParliamentsParser.writeRecord(record, dataset);
        }

        List<FtmEntity> entities = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertEquals(119, entities.size());

        // Vérifier Benoît Dispa (Président du PFWB)
        assertTrue(entities.stream().anyMatch(e ->
                        e.getValues("name").stream().anyMatch(n -> n.contains("Dispa"))
                                && e.getValues("position").stream().anyMatch(p -> p.contains("Président du Parlement"))
                                && e.getValues("political").contains("Les Engagés")),
                "Doit contenir Benoît Dispa (Président du Parlement de la Fédération Wallonie-Bruxelles)");

        // Vérifier Patricia Creutz-Vilvoye (Présidente du PDG)
        assertTrue(entities.stream().anyMatch(e ->
                        e.getValues("name").contains("Patricia Creutz-Vilvoye")
                                && e.getValues("position").stream().anyMatch(p -> p.contains("Parlamentspräsidentin"))
                                && e.getValues("political").contains("CSP")),
                "Doit contenir Patricia Creutz-Vilvoye (Présidente du Parlement de la Communauté germanophone)");

        for (FtmEntity entity : entities) {
            assertEquals("Person", entity.getSchema());
            assertTrue(entity.getValues("topics").contains("role.pep"));
            assertTrue(entity.getValues("country").contains("be"));
            assertTrue(entity.getValues("address").isEmpty());
            assertTrue(entity.getValues("email").isEmpty());
            assertTrue(entity.getValues("phone").isEmpty());
        }
    }
}
