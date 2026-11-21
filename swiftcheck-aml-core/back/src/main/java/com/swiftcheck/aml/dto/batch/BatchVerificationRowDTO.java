package com.swiftcheck.aml.dto.batch;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.swiftcheck.aml.dto.AmlMatchDTO;

import lombok.Data;

/** Résultat de la vérification d'une entité du lot. */
@Data
public class BatchVerificationRowDTO {

    /** HIT : correspondance sur une liste ; COUNTRY_ALERT : seule la juridiction est à risque (GAFI) ;
     *  CLEAN : rien trouvé ; INVALID : ligne non vérifiable ; ERROR : échec de l'appel. */
    public enum Status { HIT, COUNTRY_ALERT, CLEAN, INVALID, ERROR }

    private BatchEntityDTO entity;
    private Status status;
    private int matchesCount;
    private Double topScore;
    private String topMatchCaption;
    /** Risque le plus élevé parmi les correspondances : CRITICAL, HIGH, MEDIUM, LOW. */
    private String highestRisk;
    private List<AmlMatchDTO> matches = new ArrayList<>();
    private String errorMessage;

    // Champs à plat pour le rapport Jasper.
    @JsonIgnore private String lineLabel;
    @JsonIgnore private String entityLabel;
    @JsonIgnore private String entityDetails;
    @JsonIgnore private String statusLabel;
    @JsonIgnore private String statusColor;
    @JsonIgnore private String matchesHtml;
}
