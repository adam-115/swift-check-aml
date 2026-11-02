package com.swiftcheck.aml.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Résultats réels de SearXNG (catégorie images, 01/11/2026) pour « "Pierre Moscovici" Cour des comptes ». */
class PhotoSearchServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> INSTITUTIONS = Institutions.of(List.of("Premier président de la Cour des comptes"));

    private static JsonNode result(String title, String content, String img, String url, String thumbnail) throws Exception {
        return JSON.createObjectNode().put("title", title).put("content", content).put("img_src", img).put("url", url)
                .put("thumbnail_src", thumbnail).put("engine", "google cse images");
    }

    @Test
    void nameAndFunctionAroundTheImageGiveTheScore() throws Exception {
        PhotoSearchService.Candidate c = PhotoSearchService.score("Pierre Moscovici", INSTITUTIONS, result(
                "Pierre Moscovici Appointed First President of the Cour des comptes ...",
                "Pierre Moscovici Appointed First President of the Cour des comptes ...",
                "https://intosaijournal.org/wp-content/uploads/2023/02/Pierre-Moscovici_First-President_SAI-France.jpg",
                "https://intosaijournal.org/journal-entry/france-news-summer2020/",
                "https://encrypted-tbn0.gstatic.com/images?q=tbn%3AANd9GcS1OXSq"));

        assertEquals(80, c.score(), "nom + fonction, site non officiel");
        assertEquals(List.of("nom présent", "fonction citée (cour-des-comptes)"), c.reasons());
        assertEquals("intosaijournal.org", c.domain());
        assertEquals("https://encrypted-tbn0.gstatic.com/images?q=tbn%3AANd9GcS1OXSq", c.thumbnailUrl());
    }

    @Test
    void officialSiteAddsToTheScore() throws Exception {
        PhotoSearchService.Candidate c = PhotoSearchService.score("Pierre Moscovici", INSTITUTIONS, result(
                "Pierre Moscovici, Premier président", "Cour des comptes",
                "https://www.ccomptes.fr/sites/default/files/moscovici.jpg", "https://www.ccomptes.fr/fr/pierre-moscovici", ""));
        assertEquals(100, c.score());
        assertTrue(c.reasons().contains("site officiel"));
        assertEquals(c.imageUrl(), c.thumbnailUrl(), "sans vignette : l'image elle-même");
    }

    @Test
    void anImageWithoutTheFullNameIsDropped() throws Exception {
        assertNull(PhotoSearchService.score("Pierre Moscovici", INSTITUTIONS, result(
                "Cour des comptes : rapport annuel", "Pierre Martin", "https://example.org/rapport.jpg",
                "https://example.org/rapport", "")));
        assertNull(PhotoSearchService.score("Pierre Moscovici", INSTITUTIONS, result(
                "Pierre Moscovici", "", "data:image/png;base64,AAAA", "https://example.org", "")), "pas une adresse web");
    }

    @Test
    void onlyRecentlyShownImagesCanBeRelayed() {
        assertFalse(new PhotoSearchService().recentlyShown("http://169.254.169.254/latest/meta-data"));
        assertFalse(new PhotoSearchService().recentlyShown(null));
    }
}
