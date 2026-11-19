package com.swiftcheck.aml.dto;

import java.time.Instant;

/** Un événement du journal d'indexation de Yente pour un dataset (écran « Yente », historique). */
public record YenteEventDTO(String type, String version, Instant at, String message) {
}
