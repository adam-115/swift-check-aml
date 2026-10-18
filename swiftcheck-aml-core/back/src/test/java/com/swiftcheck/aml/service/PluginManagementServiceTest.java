package com.swiftcheck.aml.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.spring.SpringPluginManager;
import org.springframework.context.support.GenericApplicationContext;

import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;

/** Bouton « Vérifier » et chargement automatique du JAR indiqué dans le parser, avec un vrai PF4J. */
class PluginManagementServiceTest {

    /** JAR du plugin UE construit par Maven avant ce module (voir le pom racine). */
    private static final Path EU_JAR = Path.of("../../swiftcheck-aml-plugins/swiftcheck-aml-eu/target")
            .toAbsolutePath().normalize().resolve("swiftcheck-aml-eu-0.0.1-SNAPSHOT.jar");

    @TempDir
    Path pluginsDir;

    private PluginManagementService newService() {
        SpringPluginManager manager = new SpringPluginManager(pluginsDir);
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        manager.setApplicationContext(context);
        return new PluginManagementService(manager, mock(ParserExecutionRepository.class));
    }

    @Test
    void verifierUnJarValideLeChargeEtTrouveLeParser() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(EU_JAR), "JAR UE non construit");
        PluginManagementService service = newService();

        var check = service.verify(EU_JAR.toString());

        assertEquals("eu-parser", check.pluginId());
        assertEquals("com.swiftcheck.aml.plugins.eu.EuSanctionsParser", check.parserClass());
        assertTrue(service.findParser("eu-parser").isPresent());
        // Vérifier une 2e fois remplace la version chargée au lieu d'échouer
        assertEquals("eu-parser", service.verify(EU_JAR.toString()).pluginId());
    }

    /** Plugin PEP Luxembourg : se charge et trouve Jackson dans le core (dépendance « provided »). */
    @Test
    void lePluginLuxembourgPepSeCharge() {
        Path jar = Path.of("../../swiftcheck-aml-plugins/swiftcheck-aml-lu-pep/target").toAbsolutePath().normalize()
                .resolve("swiftcheck-aml-lu-pep-0.0.1-SNAPSHOT.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(jar), "JAR LU non construit");
        PluginManagementService service = newService();
        var check = service.verify(jar.toString());
        assertEquals("lu-pep", check.pluginId());
        assertEquals("com.swiftcheck.aml.plugins.lu.LuxembourgPepParser", check.parserClass());

        // Relecture des fichiers d'exemple par le plugin chargé par PF4J (JSON des communes → Jackson du core)
        Path samples = Path.of("../../swiftcheck-aml-plugins/swiftcheck-aml-lu-pep/src/test/resources/lu").toAbsolutePath().normalize();
        var records = assertDoesNotThrow(() -> service.findParser("lu-pep").orElseThrow().readSourceRecords(
                new com.swiftcheck.aml.plugin.ParserContext("lu_pep", null, samples, null,
                        java.util.Map.of("referenceDate", "2026-10-16"))));
        assertEquals(9, records.size());
    }

    @Test
    void cheminInvalideDonneUnMessageClair() {
        PluginManagementService service = newService();
        var error = assertThrows(IllegalArgumentException.class, () -> service.verify("/nulle/part/plugin.jar"));
        assertTrue(error.getMessage().contains("introuvable"));
    }

    @Test
    void leParserChargeSonJarSIlNEstPasCharge() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(EU_JAR), "JAR UE non construit");
        PluginManagementService service = newService();
        Parser parser = new Parser();
        parser.setPluginId("eu-parser");
        parser.setPluginUrl(EU_JAR.toString());

        assertTrue(service.findParser(parser).isPresent());

        parser.setPluginId("autre-plugin");
        var error = assertThrows(IllegalStateException.class, () -> newService().findParser(parser));
        assertTrue(error.getMessage().contains("eu-parser"));
    }
}
