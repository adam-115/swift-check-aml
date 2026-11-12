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
    void findsThePortraitOfEachChief() throws Exception {
        assertEquals("https://www.defense.gouv.fr/sites/default/files/styles/16_9_md/public/ema/Photo%20officielle%20CEMA%20169.jpg?itok=TgJNbo7-",
                ChiefPage.photo(resource("cema.html"), PARAMS.get("cemaUrl")), "image de partage, en https");
        assertEquals("https://www.defense.gouv.fr/sites/default/files/styles/authority_image/public/terre/General-d-armee-de-montgros.jpg?h=d1cb525d&itok=EqhSurw9",
                ChiefPage.photo(resource("cemat.html"), PARAMS.get("cematUrl")));
        assertEquals("https://www.defense.gouv.fr/sites/default/files/styles/authority_image/public/air/2024_ASAP_001_D_055_009-Modifier%20carre.jpg?itok=iKi6yqjU",
                ChiefPage.photo(resource("cemaae.html"), PARAMS.get("cemaaeUrl")));
        assertEquals(null, ChiefPage.photo("<html></html>", PARAMS.get("cemaUrl")));
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
        assertEquals(3, dataset.media().size(), "un portrait par chef");
        assertTrue(dataset.media().stream().anyMatch(m -> m.entityId().equals(cemat.getId())
                && m.licence().equals("© Ministère des Armées")));

        List<SourceRecord> records = new FrPepArmyParser()
                .readSourceRecords(new ParserContext("fr_pep_army", null, rawDir, null, PARAMS));
        assertEquals(3, records.size());
    }

    @Test
    void readsTheNavyChiefFromTheOfficialArticle() throws Exception {
        // Article du 1er septembre 2023 : nom et prise de fonction ; ni naissance ni portrait
        assertEquals(new ChiefPage.Chief("amiral", "Nicolas Vaujour", null, null, "2023-08-31"),
                ChiefPage.read(resource("cemm.html")));
    }

    @Test
    void coversTheFourChiefsWhenTheNavyAddressIsSet(@TempDir Path rawDir) throws Exception {
        Map<String, String> params = new java.util.HashMap<>(PARAMS);
        params.put("cemmUrl", "https://www.defense.gouv.fr/marine/actualites/amiral-nicolas-vaujour-chef-detat-major-marine");
        FrPepArmyParser.download(url -> resource(url.contains("/ema/") ? "cema.html" : url.contains("/terre/") ? "cemat.html"
                : url.contains("/marine/") ? "cemm.html" : "cemaae.html"), params, rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepArmyParser.ID_PREFIX, FrPepArmyParser.TOPIC);

        List<String> covered = new FrPepArmyParser().convert(rawDir, params, dataset);

        assertEquals(List.of("cema", "cemat", "cemm", "cemaae"), covered);
        assertEquals(4, dataset.qualityReport().getEntities("Person"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        FtmEntity cemm = dataset.entities().stream().filter(e -> e.getValues("name").contains("Nicolas Vaujour"))
                .findFirst().orElseThrow();
        assertEquals(Set.of("Chef d'état-major de la Marine"), cemm.getValues("position"));
        assertEquals(3, dataset.media().size(), "pas de portrait pour la Marine : l'image de l'article n'est pas un portrait");
        assertTrue(dataset.media().stream().noneMatch(m -> m.entityId().equals(cemm.getId())));
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
