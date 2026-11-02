package com.swiftcheck.aml.plugins.be.pep.senate;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires hors-ligne de {@link BePepSenateParser} sur la liste officielle
 * des 60 sénateurs extraite de {@code senate.be}.
 */
class BePepSenateParserTest {

    @Test
    void parsesAllSixtySenatorsAndStripsPrivateAddresses(@TempDir Path tempDir) throws IOException {
        copyResource("senate-list.html", tempDir.resolve(SenatePageDownloader.FILE_NAME));

        BePepSenateParser parser = new BePepSenateParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepSenateParser.ID_PREFIX, BePepSenateParser.TOPIC);

        List<BePepSenateParser.SenatorRecord> records = parser.convert(tempDir, dataset);
        assertEquals(60, records.size(), "Le Sénat au complet compte 60 sénateurs");

        BePepSenateParser.SenatorRecord president = records.stream()
                .filter(BePepSenateParser.SenatorRecord::president)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Le Président du Sénat doit être identifié"));
        assertEquals("Vincent", president.firstName());
        assertEquals("Blondel", president.lastName());
        assertEquals("Vincent Blondel", president.displayName());
        assertEquals("Parlement wallon", president.originParliament());

        assertTrue(records.stream().anyMatch(r ->
                "Bob".equals(r.firstName()) && "De Brabandere".equals(r.lastName())));
        assertTrue(records.stream().anyMatch(r ->
                "Elhadj Moussa".equals(r.firstName()) && "Diallo".equals(r.lastName())));
        assertTrue(records.stream().anyMatch(r ->
                "Hajib".equals(r.firstName()) && "El Hajjaji".equals(r.lastName())));

        long cooptedCount = records.stream().filter(BePepSenateParser.SenatorRecord::coopted).count();
        long federatedCount = records.stream().filter(r -> !r.coopted()).count();
        assertEquals(10L, cooptedCount, "10 sénateurs cooptés attendus");
        assertEquals(50L, federatedCount, "50 sénateurs des entités fédérées attendus");

        List<FtmEntity> persons = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertEquals(60, persons.size());

        for (FtmEntity p : persons) {
            assertTrue(p.getValues("address").isEmpty(),
                    "Aucune adresse privée ne doit figurer dans l'entité Person");
            assertTrue(p.getValues("phone").isEmpty(),
                    "Aucun numéro de téléphone privé ne doit figurer dans l'entité Person");
            assertTrue(p.getValues("email").isEmpty(),
                    "Aucun courriel ne doit figurer dans l'entité Person");
        }
    }

    private static void copyResource(String resourceName, Path target) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepSenateParserTest.class.getClassLoader().getResourceAsStream(resourceName),
                "Ressource de test introuvable : " + resourceName)) {
            Files.copy(in, target);
        }
    }
}
