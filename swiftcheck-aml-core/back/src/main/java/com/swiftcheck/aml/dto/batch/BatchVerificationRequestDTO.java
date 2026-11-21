package com.swiftcheck.aml.dto.batch;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** Demande de vérification d'un lot d'entités. */
@Data
public class BatchVerificationRequestDTO {
    private String fileName;
    /** Bases Yente : « default » ou une liste séparée par des virgules. */
    private String dataset = "default";
    private List<BatchEntityDTO> entities = new ArrayList<>();
}
