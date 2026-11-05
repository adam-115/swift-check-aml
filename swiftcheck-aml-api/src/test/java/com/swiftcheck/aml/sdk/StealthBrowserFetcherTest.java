package com.swiftcheck.aml.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StealthBrowserFetcherTest {

    @TempDir
    Path tempDir;

    @Test
    void testStealthFetchLocalOrFallback() throws Exception {
        Path target = tempDir.resolve("sample.html");
        // Test fetch on a standard accessible endpoint (data.gouv or wikipedia or mock)
        StealthBrowserFetcher.fetchToFile("https://example.com", target);
        assertTrue(Files.exists(target));
        assertTrue(Files.size(target) > 0);
        String content = Files.readString(target);
        assertNotNull(content);
        assertTrue(content.contains("Example Domain") || content.contains("<html"));
    }
}

