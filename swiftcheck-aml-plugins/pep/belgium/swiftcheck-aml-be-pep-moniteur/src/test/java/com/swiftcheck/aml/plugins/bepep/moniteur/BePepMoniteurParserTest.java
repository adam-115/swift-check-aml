package com.swiftcheck.aml.plugins.bepep.moniteur;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class BePepMoniteurParserTest {

        private final Path testResources = Paths.get("src/test/resources/moniteur");

        @Test
        void testConvertMoniteurAndJudicialSources() throws Exception {
                BePepMoniteurParser parser = new BePepMoniteurParser();
                FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepMoniteurParser.ID_PREFIX,
                                BePepMoniteurParser.TOPIC);
                LocalDate fixedToday = LocalDate.of(2026, 11, 1);
                LocalDate oldestEnd = fixedToday.minusYears(5);

                BePepMoniteurParser.Report report = parser.convert(testResources, oldestEnd, fixedToday, dataset);

                assertNotNull(report);
                assertTrue(report.articlesRead() >= 5, "Au moins 5 actes lus");
                assertTrue(report.eventsFound() >= 50, "Événements Moniteur + Cour de cassation + Collège trouvés");
                assertTrue(report.pepEvents() >= 50, "Au moins 50 événements PEP identifiés");

                List<FtmEntity> persons = dataset.entities().stream()
                                .filter(e -> "Person".equals(e.getSchema()))
                                .toList();
                assertTrue(persons.size() >= 60,
                                "Attendu >= 60 personnes PEP (magistrats Cour de cassation + cours d'appel/travail + Moniteur), obtenu: "
                                                + persons.size());

                // Chaque Person doit posséder une fonction rattachée (propriété position) et un
                // prénom complet (pas d'initiale seule)
                for (FtmEntity person : persons) {
                        String name = person.getValues("name").iterator().next();
                        assertFalse(person.getValues("position").isEmpty(),
                                        "Chaque personne doit avoir au moins une propriété 'position' : " + name);
                        assertFalse(person.getValues("firstName").isEmpty(),
                                        "Chaque personne doit avoir un prénom : " + name);
                        String firstName = person.getValues("firstName").iterator().next();
                        assertTrue(firstName.replaceAll("[^\\p{L}]", "").length() >= 2 && !firstName.contains("."),
                                        "Aucun prénom réduit à une initiale ne doit être publié : " + name
                                                        + " (firstName=" + firstName + ")");
                }

                Set<String> names = persons.stream()
                                .flatMap(p -> p.getValues("name").stream())
                                .collect(Collectors.toSet());

                // Vérifier la présence des hauts magistrats de la Cour de cassation (siège et
                // parquet)
                assertTrue(names.contains("Eric de Formanoir de la Cazerie"),
                                "Premier président de la Cour de cassation");
                assertTrue(names.contains("Filip Van Volsem"), "Président de la Cour de cassation");
                assertTrue(names.contains("Ria Mortier"), "Procureur général près la Cour de cassation");
                assertTrue(names.contains("Michel Nolet de Brauwere van Steeland"),
                                "Premier avocat général près la Cour de cassation");
                assertTrue(names.contains("Damien Vandermeersch"),
                                "Avocat général près la Cour de cassation (enrichi malgré initiale au Moniteur)");
                assertTrue(names.contains("Marie-Noëlle Borlée"),
                                "Conseiller à la Cour de cassation (installée en 2025)");
                assertTrue(names.contains("Jérémie Van Meerbeeck"),
                                "Avocat général près la Cour de cassation (installé en 2026)");

                // Vérifier les premiers présidents des cours d'appel et cours du travail
                assertTrue(names.contains("Evelyne Lahaye"), "Premier président de la cour d'appel de Liège");
                assertTrue(names.contains("Philippe Morandini"), "Premier président de la cour d'appel de Mons");
                assertTrue(names.contains("Michel Oosterlinck"), "Premier président de la cour d'appel de Gand");
                assertTrue(names.contains("Dirk Torfs"), "Premier président de la cour du travail d'Anvers");

                // Vérifier la présence des fonctions Cour de cassation / Cour d'appel / Cour du
                // travail
                boolean hasCassation = dataset.entities().stream()
                                .anyMatch(e -> "Position".equals(e.getSchema())
                                                && e.getValues("name").stream()
                                                                .anyMatch(n -> n.toLowerCase().contains("cassation")));
                boolean hasAppel = dataset.entities().stream()
                                .anyMatch(e -> "Position".equals(e.getSchema())
                                                && e.getValues("name").stream()
                                                                .anyMatch(n -> n.toLowerCase().contains("appel")));
                boolean hasTravail = dataset.entities().stream()
                                .anyMatch(e -> "Position".equals(e.getSchema())
                                                && e.getValues("name").stream()
                                                                .anyMatch(n -> n.toLowerCase().contains("travail")));

                assertTrue(hasCassation, "Doit contenir une fonction liée à la Cour de cassation");
                assertTrue(hasAppel, "Doit contenir une fonction liée à la Cour d'appel");
                assertTrue(hasTravail, "Doit contenir une fonction liée à la Cour du travail");
        }

        @Test
        void testSplitNameAndInitialRejection() {
                assertEquals(List.of("Colienne", "LEJEUNE DE SCHIERVEL"),
                                List.of(BePepMoniteurParser.splitName("Colienne LEJEUNE DE SCHIERVEL")));
                assertEquals(List.of("Colienne", "LEJEUNE DE SCHIERVEL"),
                                List.of(BePepMoniteurParser.splitName("LEJEUNE DE SCHIERVEL Colienne")));
                assertEquals(List.of("Dennis", "SOETE"),
                                List.of(BePepMoniteurParser.splitName("SOETE Dennis")));
                assertEquals(List.of("Lars", "VAN SANTVOORT"),
                                List.of(BePepMoniteurParser.splitName("VAN SANTVOORT Lars")));
                assertEquals(List.of("Eric", "de Formanoir de la Cazerie"),
                                List.of(BePepMoniteurParser.splitName("Eric de Formanoir de la Cazerie")));
                assertEquals(List.of("Pieter", "De Crem"),
                                List.of(BePepMoniteurParser.splitName("Pieter De Crem")));

                assertTrue(BePepMoniteurParser.isInitialOnlyName("Algoet I."));
                assertTrue(BePepMoniteurParser.isInitialOnlyName("Collinet T."));
                assertTrue(BePepMoniteurParser.isInitialOnlyName("Verschueren J-M."));
                assertFalse(BePepMoniteurParser.isInitialOnlyName("Colienne LEJEUNE DE SCHIERVEL"));
                assertFalse(BePepMoniteurParser.isInitialOnlyName("Jean-Marc GASPARD"));
        }

        @Test
        void testReadSourceRecords() throws Exception {
                BePepMoniteurParser parser = new BePepMoniteurParser();
                ParserContext ctx = new ParserContext("be_pep_moniteur", null, testResources, null, Map.of());
                List<SourceRecord> records = parser.readSourceRecords(ctx);

                assertNotNull(records);
                assertFalse(records.isEmpty());
                for (SourceRecord r : records) {
                        assertNotNull(r.entityId());
                        assertNotNull(r.name());
                        assertFalse(r.name().isBlank());
                }
        }
}
