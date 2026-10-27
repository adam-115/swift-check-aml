package com.swiftcheck.aml.dto;

import com.swiftcheck.aml.entities.PepCategory;
import com.swiftcheck.aml.entities.PepCategoryKind;

import lombok.Data;

/** Catégorie de fonctions PEP, telle qu'échangée avec l'interface. */
@Data
public class PepCategoryDTO {
    private long id;
    private String countryIso;
    private String code;
    private String label;
    private PepCategoryKind kind;
    private int riskClass;
    private int sortOrder;
    private String legalReference;
    /** {@code null} à la création = active. */
    private Boolean active;
    /** Nombre de fonctions de la catégorie (lecture seule) ; une catégorie qui en a ne peut pas être supprimée. */
    private int functionCount;

    public static PepCategoryDTO from(PepCategory entity) {
        PepCategoryDTO dto = new PepCategoryDTO();
        dto.setId(entity.getId());
        dto.setCountryIso(entity.getCountry().getIso());
        dto.setCode(entity.getCode());
        dto.setLabel(entity.getLabel());
        dto.setKind(entity.getKind());
        dto.setRiskClass(entity.getRiskClass());
        dto.setSortOrder(entity.getSortOrder());
        dto.setLegalReference(entity.getLegalReference());
        dto.setActive(entity.isActive());
        dto.setFunctionCount(entity.getFunctions().size());
        return dto;
    }
}
