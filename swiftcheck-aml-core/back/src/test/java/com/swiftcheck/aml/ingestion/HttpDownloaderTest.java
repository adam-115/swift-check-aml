package com.swiftcheck.aml.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** Réponse HTML : erreur pour un fichier de données, normale pour une source qui est une page web. */
class HttpDownloaderTest {

    @Test
    void htmlIsAcceptedOnlyWhenTheSourceIsAWebPage(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] page = "<html><body>Luc FRIEDEN</body></html>".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            Path htmlTarget = dir.resolve("page.html");
            new HttpDownloader().download(base + "/fr/gouvernement.html", htmlTarget);
            assertEquals("<html><body>Luc FRIEDEN</body></html>", Files.readString(htmlTarget));

            IOException error = assertThrows(IOException.class,
                    () -> new HttpDownloader().download(base + "/liste.csv", dir.resolve("liste.csv")));
            assertTrue(error.getMessage().startsWith("Le serveur renvoie une page HTML"), error.getMessage());

            // Si htmlExpected est vrai (parser WEB_PAGE), une URL sans extension .html est acceptée
            Path targetWithoutExt = dir.resolve("target.download");
            new HttpDownloader().download(base + "/college-commissioners_fr", targetWithoutExt, true);
            assertEquals("<html><body>Luc FRIEDEN</body></html>", Files.readString(targetWithoutExt));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webPageIsRecognisedByItsExtension() {
        assertTrue(HttpDownloader.isHtmlPage("https://gouvernement.lu/fr/gouvernement.html"));
        assertTrue(HttpDownloader.isHtmlPage("https://example.org/liste.HTM?lang=fr"));
        assertFalse(HttpDownloader.isHtmlPage("https://data.public.lu/fr/datasets/r/a5b6e7f9-b488-41a6-9bd1-be913a5c35b5"));
        assertFalse(HttpDownloader.isHtmlPage("https://example.org/html/liste.csv"));
    }
}
