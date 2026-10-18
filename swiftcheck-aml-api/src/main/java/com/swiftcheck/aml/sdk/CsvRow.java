package com.swiftcheck.aml.sdk;

import java.util.List;
import java.util.Map;

/**
 * Une ligne d'un fichier CSV, lue par {@link CsvRecordReader}. Les valeurs sont lues par le nom de leur
 * colonne (première ligne du fichier).
 *
 * <pre>{@code
 * String nom = ligne.get("NAME");          // "Adam", ou null si la cellule est vide
 * }</pre>
 */
public final class CsvRow {

    private final Map<String, Integer> columns;
    private final List<String> cells;
    private final int lineNumber;

    CsvRow(Map<String, Integer> columns, List<String> cells, int lineNumber) {
        this.columns = columns;
        this.cells = cells;
        this.lineNumber = lineNumber;
    }

    /**
     * @return la valeur de la colonne, sans espaces autour ; {@code null} si la cellule est vide ou si la
     *         ligne est plus courte que l'en-tête
     * @throws IllegalArgumentException si la colonne n'existe pas dans le fichier (nom mal écrit, ou la
     *                                  source a changé de format)
     */
    public String get(String column) {
        Integer index = columns.get(column);
        if (index == null) {
            throw new IllegalArgumentException("Colonne « " + column + " » absente du fichier CSV. Colonnes : "
                    + columns.keySet());
        }
        if (index >= cells.size()) {
            return null;
        }
        String value = cells.get(index).trim();
        return value.isEmpty() ? null : value;
    }

    /** Numéro de la ligne dans le fichier (l'en-tête est la ligne 1), pour les messages d'erreur. */
    public int lineNumber() {
        return lineNumber;
    }

    @Override
    public String toString() {
        return "ligne " + lineNumber + " " + cells;
    }
}
