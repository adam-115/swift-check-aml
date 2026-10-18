package com.swiftcheck.aml.sdk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture simple d'un fichier CSV, ligne par ligne, avec accès aux valeurs par nom de colonne.
 *
 * <pre>{@code
 * CsvRecordReader.readEach(fichier, ligne -> {
 *     String nom = ligne.get("NAME");
 *     String prenom = ligne.get("FIRSTNAME");
 * });
 * }</pre>
 *
 * <ul>
 *   <li><b>Encodage détecté automatiquement</b> : UTF-8 (avec ou sans BOM) si le fichier est de l'UTF-8 valide,
 *       sinon Windows-1252, l'encodage des exports Excel sous Windows. Les sources officielles mélangent
 *       les deux (ex. Chambre des Députés du Luxembourg).</li>
 *   <li><b>Format CSV standard</b> (RFC 4180) : valeurs entre guillemets, guillemets doublés ({@code ""}),
 *       retours à la ligne dans une valeur entre guillemets.</li>
 *   <li>La première ligne est l'en-tête ; les lignes vides sont ignorées.</li>
 * </ul>
 *
 * <p>Le fichier est chargé entièrement en mémoire : prévu pour des fichiers de quelques dizaines de Mo au plus.
 */
public final class CsvRecordReader {

    /** Action à exécuter pour chaque ligne lue. */
    @FunctionalInterface
    public interface RowHandler {
        void handle(CsvRow row) throws Exception;
    }

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private CsvRecordReader() {
    }

    /** Lit un fichier séparé par des virgules. */
    public static void readEach(Path file, RowHandler handler) throws Exception {
        readEach(file, ',', handler);
    }

    /** Lit un fichier avec un autre séparateur (ex. {@code ';'} pour les exports Excel français). */
    public static void readEach(Path file, char separator, RowHandler handler) throws Exception {
        List<List<String>> lines = parse(decode(Files.readAllBytes(file)), separator);
        if (lines.isEmpty()) {
            return;
        }
        Map<String, Integer> columns = new LinkedHashMap<>();
        List<String> header = lines.get(0);
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).trim(), i);
        }
        for (int i = 1; i < lines.size(); i++) {
            handler.handle(new CsvRow(columns, lines.get(i), i + 1));
        }
    }

    /** Décode le fichier : UTF-8 s'il est valide (BOM retiré), sinon Windows-1252. */
    static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, WINDOWS_1252);
        }
    }

    /**
     * Découpe le texte en lignes et en cellules. Une ligne vide est ignorée.
     * Lecture caractère par caractère : {@code inQuotes} indique si l'on est entre guillemets.
     */
    static List<List<String>> parse(String text, char separator) throws IOException {
        List<List<String>> lines = new ArrayList<>();
        List<String> line = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');           // "" entre guillemets = un guillemet dans la valeur
                    i++;
                } else if (c == '"') {
                    inQuotes = false;           // fin de la valeur entre guillemets
                } else {
                    cell.append(c);             // y compris virgules et retours à la ligne
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == separator) {
                line.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;                        // fin de ligne Windows "\r\n"
                }
                endLine(lines, line, cell);
                line = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (inQuotes) {
            throw new IOException("Fichier CSV invalide : guillemet non fermé.");
        }
        endLine(lines, line, cell);
        return lines;
    }

    /** Termine la ligne en cours ; une ligne entièrement vide n'est pas gardée. */
    private static void endLine(List<List<String>> lines, List<String> line, StringBuilder cell) {
        line.add(cell.toString());
        cell.setLength(0);
        boolean empty = line.stream().allMatch(String::isBlank);
        if (!empty) {
            lines.add(line);
        }
    }
}
