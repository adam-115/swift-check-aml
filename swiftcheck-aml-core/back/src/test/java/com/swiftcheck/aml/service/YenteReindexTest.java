package com.swiftcheck.aml.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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
     * Nouveau dataset : Yente doit d'abord relire manifest.yml (YENTE_CRONTAB), la réindexation est donc différée.
     * Dataset déjà présent avec la même entrée : seule la liste a changé, la réindexation est immédiate.
     */
    @Test
    void reindexationDiffereeSeulementQuandLeManifestChange(@TempDir Path dir) throws Exception {
        HttpServer yente = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        yente.createContext("/updatez", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        yente.start();
        YenteManifestService service = new YenteManifestService();
        service.setManifestRefreshDelay(Duration.ofMillis(500));
        try {
            ApplicationConfigEntity config = new ApplicationConfigEntity();
            config.setYenteUrl("http://127.0.0.1:" + yente.getAddress().getPort());
            config.setYenteUpdateToken("bon");
            config.setYenteManifestPath(dir.resolve("manifest.yml").toString());
            config.setYenteContainerDataPath("/data");

            service.addDataset("lu_pep_chamber", "PEP Luxembourg – députés", config);
            assertEquals(0, calls.get(), "nouveau dataset : pas d'appel immédiat");
            waitUntil(() -> calls.get() == 1);
            assertTrue(Files.readString(dir.resolve("manifest.yml")).contains("lu_pep_chamber"));

            service.addDataset("lu_pep_chamber", "PEP Luxembourg – députés", config);
            assertEquals(2, calls.get(), "même entrée : appel immédiat");
        } finally {
            service.stopDelayedReload();
            yente.stop(0);
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "délai dépassé");
            Thread.sleep(50);
        }
    }
}
