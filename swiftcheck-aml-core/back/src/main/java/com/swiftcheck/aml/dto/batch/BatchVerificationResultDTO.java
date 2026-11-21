package com.swiftcheck.aml.dto.batch;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** Rapport de vérification d'un lot. */
@Data
public class BatchVerificationResultDTO {
    private String fileName;
    private String dataset;
    /** Début de la vérification, ISO-8601. */
    private String startedAt;
    private long durationMs;
    private int total;
    private int hits;
    private int countryAlerts;
    private int clean;
    private int invalid;
    private int errors;
    private List<BatchVerificationRowDTO> rows = new ArrayList<>();
}
