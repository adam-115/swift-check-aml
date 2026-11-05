package com.swiftcheck.aml.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.spring.SpringPluginManager;

import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;

/** Liste des JAR connus : ceux du dossier des plugins et ceux que les parsers indiquent, chargés ou non. */
class PluginAvailabilityTest {

    @TempDir
    Path folder;

    @TempDir
    Path elsewhere;

    @Test
    void listsFolderJarsAndParserJarsWithTheirPluginIdAndVersion() throws IOException {
        jar(folder.resolve("a-1.0.jar"), "plugin-a", "1.0.0");
        jar(folder.resolve("not-a-plugin.jar"), null, null);
        Path outside = jar(elsewhere.resolve("b-2.0.jar"), "plugin-b", "2.0.0");
        Parser parser = new Parser();
        parser.setPluginUrl(outside.toString());
        ParserRepository parsers = mock(ParserRepository.class);
        when(parsers.findAll()).thenReturn(List.of(parser, parser));

        List<PluginManagementService.AvailablePlugin> list = service(parsers).listAvailable();

        assertEquals(3, list.size());
        var a = byId(list, "plugin-a");
        assertEquals("1.0.0", a.version());
        assertTrue(a.inFolder());
        assertFalse(a.loaded(), "aucun plugin n'est chargé dans ce test");
        assertEquals(0, a.parsers());
        var b = byId(list, "plugin-b");
        assertFalse(b.inFolder(), "indiqué par un parser, hors du dossier");
        assertEquals(2, b.parsers());
        assertNull(b.error());
    }

    @Test
    void aJarWithoutPluginIdIsReportedWithItsErrorInsteadOfFailingTheList() throws IOException {
        jar(folder.resolve("not-a-plugin.jar"), null, null);

        var list = service(null).listAvailable();

        assertEquals(1, list.size());
        assertNull(list.get(0).pluginId());
        assertNotNull(list.get(0).error());
        assertTrue(list.get(0).error().contains("Plugin-Id"));
    }

    @Test
    void aMissingJarOfAParserIsReported() {
        Parser parser = new Parser();
        parser.setPluginUrl(elsewhere.resolve("absent.jar").toString());
        ParserRepository parsers = mock(ParserRepository.class);
        when(parsers.findAll()).thenReturn(List.of(parser));

        var list = service(parsers).listAvailable();

        assertEquals(1, list.size());
        assertTrue(list.get(0).error().contains("introuvable"));
        assertEquals(1, list.get(0).parsers());
    }

    private PluginManagementService service(ParserRepository parsers) {
        return new PluginManagementService(new SpringPluginManager(folder), mock(ParserExecutionRepository.class),
                parsers, folder.toString());
    }

    private static PluginManagementService.AvailablePlugin byId(List<PluginManagementService.AvailablePlugin> list, String id) {
        return list.stream().filter(p -> id.equals(p.pluginId())).findFirst().orElseThrow();
    }

    private static Path jar(Path path, String pluginId, String version) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (pluginId != null) {
            manifest.getMainAttributes().putValue("Plugin-Id", pluginId);
            manifest.getMainAttributes().putValue("Plugin-Version", version);
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            // JAR sans classe : seul le MANIFEST compte pour cette liste
        }
        return path;
    }
}
