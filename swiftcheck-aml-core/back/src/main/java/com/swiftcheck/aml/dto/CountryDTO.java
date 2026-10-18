package com.swiftcheck.aml.dto;

import java.time.LocalDate;

import com.swiftcheck.aml.entities.Country;

/**
 * Pays couvert, tel qu'échangé avec l'interface.
 *
 * @param officialListReference liste officielle des fonctions PEP déclarée par le pays, ex. « JO C/2023/724 »
 */
public record CountryDTO(
        String iso,
        String name,
        boolean euMember,
        String legalBasis,
        int retentionMonths,
        String officialListReference,
        LocalDate officialListDate,
        String officialListUrl) {

    public static CountryDTO from(Country entity) {
        return new CountryDTO(entity.getIso(), entity.getName(), entity.isEuMember(), entity.getLegalBasis(),
                entity.getRetentionMonths(), entity.getOfficialListReference(), entity.getOfficialListDate(),
                entity.getOfficialListUrl());
    }
}
