package com.swiftcheck.aml.plugins.frpep.army;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Biographies réelles de defense.gouv.fr (octobre 2026) : CEMA, CEMAT et CEMAAE. */
class FrPepArmyParserTest {

    private static final Map<String, String> PARAMS = Map.of(
            "cemaUrl", "https://www.defense.gouv.fr/ema/chef-detat-major-armees",
            "cematUrl", "https://www.defense.gouv.fr/terre/chef-detat-major-larmee-terre/biographie-du-chef-detat-major-larmee-terre",
            "cemmUrl", "",
            "cemaaeUrl", "https://www.defense.gouv.fr/air/biographie-du-chef-detat-major-larmee-lair-lespace");

    @Test
    void readsTheThreeBiographies() throws Exception {
        assertEquals(new ChiefPage.Chief("général d'armée aérienne", "Fabien Mandon", "1969-10-19", "Montmorency", null),
                ChiefPage.read(resource("cema.html")));
        assertEquals(new ChiefPage.Chief("général d'armée", "Jacques de Montgros", "1969-06-15", "Petit-Quevilly", "2026-07-26"),
                ChiefPage.read(resource("cemat.html")));
        assertEquals(new ChiefPage.Chief("général d'armée aérienne", "Jérôme BELLANGER", "1969-03-20", "Montreuil-sous-Bois", "2024-09-16"),
                ChiefPage.read(resource("cemaae.html")));
    }

    @Test
    void refusesAPageWithoutAChief() {
        assertThrows(IllegalStateException.class, () -> ChiefPage.read("<html><p>Page non trouvée</p></html>"));
    }

    @Test
    void convertsTheConfiguredFunctions(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        FrPepArmyParser.download(url -> {
            calls.add(url);
            return resource(url.contains("/ema/") ? "cema.html" : url.contains("/terre/") ? "cemat.html" : "cemaae.html");
        }, PARAMS, rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepArmyParser.ID_PREFIX, FrPepArmyParser.TOPIC);

        List<String> covered = new FrPepArmyParser().convert(rawDir, PARAMS, dataset);

        assertEquals(3, calls.size(), "pas d'appel pour la Marine, sans adresse");
        assertEquals(List.of("cema", "cemat", "cemaae"), covered);
        assertEquals(3, dataset.qualityReport().getEntities("Person"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        FtmEntity cemat = dataset.entities().stream().filter(e -> e.getValues("name").contains("Jacques de Montgros"))
                .findFirst().orElseThrow();
        assertEquals(Set.of("Chef d'état-major de l'armée de Terre"), cemat.getValues("position"));
        assertEquals(Set.of("1969-06-15"), cemat.getValues("birthDate"));
        assertEquals(Set.of("role.pep"), cemat.getValues("topics"));
        FtmEntity mandate = entity(dataset, dataset.buildEntityId("Jacques de Montgros", "1969-06-15", "cemat"));
        assertEquals(Set.of("2026-07-26"), mandate.getValues("startDate"));

        List<SourceRecord> records = new FrPepArmyParser()
                .readSourceRecords(new ParserContext("fr_pep_army", null, rawDir, null, PARAMS));
        assertEquals(3, records.size());
    }

    @Test
    void requiresAtLeastOneAddress(@TempDir Path rawDir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FrPepArmyParser.download(url -> "", Map.of("cemmUrl", ""), rawDir));
        assertTrue(e.getMessage().contains("cemaUrl"));
    }

    private static FtmEntity entity(FtmDatasetBuilder dataset, String id) {
        return dataset.entities().stream().filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }

    private static String resource(String file) throws IOException {
        try (InputStream in = FrPepArmyParserTest.class.getResourceAsStream("/defense/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
