package com.swiftcheck.aml.dto.batch;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** Résultat de la lecture d'un fichier CSV, avant vérification. */
@Data
public class BatchParseResultDTO {
    private String fileName;
    /** Séparateur détecté (« ; », « , » ou tabulation). */
    private String separator;
    /** Colonnes reconnues : en-tête du fichier → champ de l'application. */
    private List<String> recognizedColumns = new ArrayList<>();
    /** Colonnes de l'en-tête que l'application ne sait pas exploiter. */
    private List<String> ignoredColumns = new ArrayList<>();
    private List<BatchEntityDTO> entities = new ArrayList<>();
    private int validCount;
    private int invalidCount;
    /** Avertissements globaux (lignes au-delà de la limite...). */
    private List<String> warnings = new ArrayList<>();
}
