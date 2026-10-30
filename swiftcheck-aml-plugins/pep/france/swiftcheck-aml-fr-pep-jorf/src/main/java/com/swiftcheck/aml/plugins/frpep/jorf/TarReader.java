package com.swiftcheck.aml.plugins.frpep.jorf;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Lecteur minimal d'archive tar (format GNU ou ustar, celui des fichiers de la DILA), lu en flux : une archive de
 * 1,7 Go n'est jamais chargée en mémoire. Gère les noms longs GNU (type {@code L}), le préfixe ustar et le chemin pax
 * ({@code x}), même si la DILA n'en utilise pas aujourd'hui.
 */
final class TarReader {

    private static final int BLOCK = 512;

    /** Reçoit chaque fichier de l'archive : son chemin et son contenu, à lire avant de rendre la main. */
    interface EntryHandler {
        void handle(String path, long size, InputStream content) throws IOException;
    }

    private TarReader() {
    }

    /** Parcourt l'archive non compressée {@code tar} et passe chaque fichier ordinaire à {@code handler}. */
    static void forEachFile(InputStream tar, EntryHandler handler) throws IOException {
        byte[] header = new byte[BLOCK];
        String longName = null;
        while (readBlock(tar, header)) {
            if (isZero(header)) {
                return;                 // fin d'archive
            }
            String name = string(header, 0, 100);
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            String prefix = string(header, 345, 155);
            long padded = (size + BLOCK - 1) / BLOCK * BLOCK;

            if (type == 'L' || type == 'x') {
                String data = new String(tar.readNBytes((int) size), StandardCharsets.UTF_8);
                skip(tar, padded - size);
                longName = type == 'L' ? data.replace("\0", "") : paxPath(data, longName);
                continue;
            }
            String path = longName != null ? longName : prefix.isEmpty() ? name : prefix + "/" + name;
            longName = null;
            if (type == '0' || type == '\0') {
                BoundedInputStream content = new BoundedInputStream(tar, size);
                handler.handle(path, size, content);
                skip(tar, content.remaining + (padded - size));
            } else {
                skip(tar, padded);      // dossier, lien… : rien à lire
            }
        }
    }

    /** Chemin d'un en-tête pax (« 27 path=dossier/fichier.xml »), ou {@code current} s'il n'y en a pas. */
    private static String paxPath(String data, String current) {
        for (String record : data.split("\n")) {
            int eq = record.indexOf(" path=");
            if (eq >= 0) {
                return record.substring(eq + 6);
            }
        }
        return current;
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int read = in.readNBytes(block, 0, BLOCK);
        if (read == 0) {
            return false;
        }
        if (read < BLOCK) {
            throw new EOFException("Archive tar tronquée");
        }
        return true;
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String string(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] header, int offset, int length) {
        String value = string(header, offset, length);
        return value.isEmpty() ? 0 : Long.parseLong(value, 8);
    }

    private static void skip(InputStream in, long bytes) throws IOException {
        long left = bytes;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("Archive tar tronquée");
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    /** Le contenu d'une entrée : le gestionnaire ne peut pas lire au-delà, et ce qu'il laisse est sauté ensuite. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        BoundedInputStream(InputStream in, long size) {
            this.in = in;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public void close() {
            // l'archive reste ouverte pour l'entrée suivante
        }
    }
}
