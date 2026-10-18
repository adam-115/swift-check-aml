package com.swiftcheck.aml.dto;

import com.swiftcheck.aml.entities.PepFunction;

import lombok.Data;

/**
 * Fonction PEP, telle qu'échangée avec l'interface. En entrée, seuls {@code categoryId}, {@code label},
 * {@code institution} et {@code expectedHolders} sont utilisés ; les autres champs aident à l'affichage.
 */
@Data
public class PepFunctionDTO {
    private long id;
    private long categoryId;
    private String categoryCode;
    private String categoryLabel;
    private String countryIso;
    private String label;
    private String institution;
    private Integer expectedHolders;

    public static PepFunctionDTO from(PepFunction entity) {
        PepFunctionDTO dto = new PepFunctionDTO();
        dto.setId(entity.getId());
        dto.setCategoryId(entity.getCategory().getId());
        dto.setCategoryCode(entity.getCategory().getCode());
        dto.setCategoryLabel(entity.getCategory().getLabel());
        dto.setCountryIso(entity.getCategory().getCountry().getIso());
        dto.setLabel(entity.getLabel());
        dto.setInstitution(entity.getInstitution());
        dto.setExpectedHolders(entity.getExpectedHolders());
        return dto;
    }
}
