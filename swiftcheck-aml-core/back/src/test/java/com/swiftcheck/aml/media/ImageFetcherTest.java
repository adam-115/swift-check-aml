package com.swiftcheck.aml.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

class ImageFetcherTest {

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1};

    private static byte[] gzip(byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(b);
        }
        return out.toByteArray();
    }

    /** bmvg.de envoie ses photos en gzip (Content-Encoding: gzip) : l'archive doit garder le JPEG, pas le gzip. */
    @Test
    void decompressesGzipBodies() throws IOException {
        assertThat(ImageFetcher.decoded(gzip(JPEG), "gzip")).isEqualTo(JPEG);
        // même sans en-tête, la signature gzip suffit
        assertThat(ImageFetcher.decoded(gzip(JPEG), "")).isEqualTo(JPEG);
        assertThat(ImageFetcher.decoded(JPEG, "")).isEqualTo(JPEG);
    }

    @Test
    void recognisesImageSignatures() throws IOException {
        assertThat(ImageFetcher.looksLikeImage(JPEG)).isTrue();
        assertThat(ImageFetcher.looksLikeImage(gzip(JPEG))).isFalse();
        assertThat(ImageFetcher.looksLikeImage("<!DOCTYPE html><html>".getBytes())).isFalse();
    }
}
