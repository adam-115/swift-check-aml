package com.swiftcheck.aml.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;
import com.swiftcheck.aml.entities.ApplicationConfigEntity;

/** Bouton « Réindexer Yente » : POST /updatez avec le jeton, erreurs renvoyées clairement. */
class YenteReindexTest {

    @Test
    void reindexAppelleUpdatezAvecLeJeton() throws Exception {
        HttpServer yente = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String[] received = new String[2];
        yente.createContext("/updatez", exchange -> {
            received[0] = exchange.getRequestMethod();
            received[1] = exchange.getRequestURI().getQuery();
            int status = "token=bon".equals(received[1]) ? 200 : 403;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        yente.start();
        try {
            ApplicationConfigEntity config = new ApplicationConfigEntity();
            config.setYenteUrl("http://127.0.0.1:" + yente.getAddress().getPort() + "/");
            config.setYenteUpdateToken("bon");
            YenteManifestService service = new YenteManifestService();

            assertDoesNotThrow(() -> service.reindex(config));
            assertEquals("POST", received[0]);
            assertEquals("token=bon", received[1]);

            config.setYenteUpdateToken("mauvais");
            var refused = assertThrows(IllegalStateException.class, () -> service.reindex(config));
            assertTrue(refused.getMessage().contains("jeton"), refused.getMessage());
        } finally {
            yente.stop(0);
        }

        ApplicationConfigEntity unreachable = new ApplicationConfigEntity();
        unreachable.setYenteUrl("http://127.0.0.1:1");
        var down = assertThrows(IllegalStateException.class, () -> new YenteManifestService().reindex(unreachable));
        assertTrue(down.getMessage().startsWith("Yente injoignable"), down.getMessage());
    }

    /**
     * Ajout, mise à jour et retrait d'un dataset : le manifest change, mais Yente n'est jamais appelé
     * (YENTE_AUTO_REINDEX le réindexe seul à son prochain passage).
     */
    @Test
    void publierOuRetirerNAppellePasYente(@TempDir Path dir) throws Exception {
        HttpServer yente = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        yente.createContext("/updatez", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        yente.start();
        YenteManifestService service = new YenteManifestService();
        try {
            ApplicationConfigEntity config = new ApplicationConfigEntity();
            config.setYenteUrl("http://127.0.0.1:" + yente.getAddress().getPort());
            config.setYenteUpdateToken("bon");
            config.setYenteManifestPath(dir.resolve("manifest.yml").toString());
            config.setYenteContainerDataPath("/data");

            service.addDataset("lu_pep_chamber", "PEP Luxembourg – députés", config);
            service.addDataset("lu_pep_chamber", "PEP Luxembourg – députés", config);
            Thread.sleep(300);
            assertEquals(0, calls.get(), "publication : Yente n'est pas appelé");
            assertTrue(Files.readString(dir.resolve("manifest.yml")).contains("lu_pep_chamber"));

            service.removeDataset("lu_pep_chamber", config);
            Thread.sleep(300);
            assertEquals(0, calls.get(), "retrait : Yente n'est pas appelé");
            assertFalse(Files.readString(dir.resolve("manifest.yml")).contains("lu_pep_chamber"));
        } finally {
            yente.stop(0);
        }
    }
}
