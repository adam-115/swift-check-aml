package com.swiftcheck.aml.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AmlMatchDTO {
    private String id;
    private String caption;
    private String schema;
    private Double score;
    private Map<String, List<String>> properties;
    private List<String> datasets;
    
    // Enrichissements Réglementaires AML / LCB-FT
    private String pepStatus;          // ACTIVE_PEP, COOLING_OFF, FORMER_PEP, RCA, SANCTION
    private String pepStatusLabel;     // Ex: "PEP Actif", "Période de Refroidissement (12 mois)"
    private String pepTier;            // TIER_1, TIER_2, TIER_3, TIER_4
    private String pepTierLabel;       // Ex: "Tier 1 - Risque Très Élevé"
    private String riskCategory;       // CRITICAL, HIGH, MEDIUM, LOW
    private String coolingOffEndDate;  // Date d'expiration du refroidissement
    
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String matchDetailsHtml;
}
