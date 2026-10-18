package com.swiftcheck.aml.dto;

import java.time.Instant;

import com.swiftcheck.aml.entities.AlertType;
import com.swiftcheck.aml.entities.ParserAlert;

/** Alerte sur un parser, en lecture seule. */
public record ParserAlertDTO(
        long id,
        long parserId,
        String parserName,
        String datasetName,
        AlertType type,
        Instant raisedAt,
        Instant resolvedAt,
        String message) {

    public static ParserAlertDTO from(ParserAlert a) {
        return new ParserAlertDTO(a.getId(), a.getParser().getId(), a.getParser().getName(),
                a.getParser().getDatasetName(), a.getType(), a.getRaisedAt(), a.getResolvedAt(), a.getMessage());
    }
}
