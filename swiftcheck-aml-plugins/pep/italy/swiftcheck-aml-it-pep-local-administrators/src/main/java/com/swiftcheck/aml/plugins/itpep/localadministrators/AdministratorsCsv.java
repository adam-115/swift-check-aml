package com.swiftcheck.aml.plugins.itpep.localadministrators;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lecture des CSV de l'« Anagrafe degli amministratori » du ministère de l'Intérieur
 * ({@code dait.interno.gov.it/documenti/ammcom.csv} et {@code ammreg.csv}). Format : deux lignes de titre
 * (« Amministratori Comunali - elenco completo Italia » puis « Aggiornato al 24/08/2026 »), une ligne d'en-têtes, puis une
 * ligne par personne ; séparateur « ; », valeurs entre guillemets, champs vides non quotés.
 */
final class AdministratorsCsv {

    private AdministratorsCsv() {
    }

    /** Une ligne : nom de colonne → valeur. */
    record Record(Map<String, String> values) {
        String get(String column) {
            String v = values.get(column);
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    /** Date de mise à jour annoncée par le fichier (« 24/08/2026 »), ou {@code null}. */
    static String updatedOn(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine();
            String second = reader.readLine();
            if (second == null) {
                return null;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2}/\\d{2}/\\d{4})").matcher(second);
            return m.find() ? m.group(1) : null;
        }
    }

    /** Parcourt le fichier ligne par ligne sans le charger en mémoire. */
    static int forEach(Path file, Consumer<Record> consumer) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            List<String> header = null;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> fields = split(line);
                if (header == null) {
                    if (fields.contains("cognome") && fields.contains("nome")) {
                        header = fields;
                    }
                    continue;                        // lignes de titre avant les en-têtes
                }
                Map<String, String> values = new HashMap<>();
                for (int i = 0; i < header.size() && i < fields.size(); i++) {
                    values.put(header.get(i), fields.get(i));
                }
                consumer.accept(new Record(values));
                count++;
            }
            if (header == null) {
                throw new IllegalStateException("En-têtes introuvables (colonnes « cognome » et « nome ») dans " + file.getFileName()
                        + " : le format du fichier a changé.");
            }
        }
        return count;
    }

    /** Découpe une ligne sur « ; » en respectant les guillemets (un guillemet doublé reste un guillemet). */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
