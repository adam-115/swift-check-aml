package com.swiftcheck.aml.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;

/** Écran Configuration : l'adresse d'Elasticsearch et le nom d'index de Yente passent par le DTO. */
class ApplicationConfigDTOTest {

    @Test
    void yenteIndexAllerRetour() {
        ApplicationConfigEntity e = new ApplicationConfigEntity();
        e.setYenteIndexUrl("http://index:9200");
        e.setYenteIndexName("yente");

        ApplicationConfigEntity back = ApplicationConfigDTO.from(e).toEntity();

        assertEquals("http://index:9200", back.getYenteIndexUrl());
        assertEquals("yente", back.getYenteIndexName());
    }
}
