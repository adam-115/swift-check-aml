package com.swiftcheck.aml.dto;

import java.time.Instant;

import com.swiftcheck.aml.entities.ListProvider;
import com.swiftcheck.aml.entities.ListType;

import lombok.Data;

/** Fournisseur de listes, tel qu'échangé avec l'interface. */
@Data
public class ListProviderDTO {
    private long id;
    private String code;
    private String name;
    private String description;
    /** Code ISO du pays (ex. {@code LU}), vide pour une organisation internationale. */
    private String country;
    private ListType listType;
    private String websiteUrl;
    private byte[] logo;            // transmis en Base64 dans le JSON
    private boolean enabled;
    private Instant createdAt;

    public static ListProviderDTO from(ListProvider entity) {
        ListProviderDTO dto = new ListProviderDTO();
        dto.setId(entity.getId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setCountry(entity.getCountry());
        dto.setListType(entity.getListType());
        dto.setWebsiteUrl(entity.getWebsiteUrl());
        dto.setLogo(entity.getLogo());
        dto.setEnabled(entity.isEnabled());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    /** Copie les champs modifiables dans l'entité (l'identifiant et la date de création ne changent pas). */
    public void copyTo(ListProvider entity) {
        entity.setCode(code);
        entity.setName(name);
        entity.setDescription(description);
        // code ISO du pays (ex. LU) ou vide pour une organisation internationale
        entity.setCountry(country == null || country.isBlank() ? null : country.trim().toUpperCase());
        entity.setListType(listType);
        entity.setWebsiteUrl(websiteUrl);
        entity.setLogo(logo);
        entity.setEnabled(enabled);
    }
}
