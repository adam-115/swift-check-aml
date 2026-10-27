package com.swiftcheck.aml.dto;

import java.time.Instant;
import java.util.List;

import com.swiftcheck.aml.entities.Completeness;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.PepCategoryKind;

/**
 * Page « Couverture PEP » d'un pays : pour chaque catégorie de la liste officielle, les fonctions, les parsers
 * qui les couvrent et le nombre de personnes publiées à leur dernière publication.
 *
 * @param officialCategories        nombre de catégories de la liste officielle
 * @param coveredOfficialCategories catégories officielles dont toutes les fonctions sont couvertes
 * @param personCount               personnes publiées par l'ensemble des parsers du pays
 */
public record CoverageDTO(
        CountryDTO country,
        int officialCategories,
        int coveredOfficialCategories,
        int personCount,
        List<CategoryCoverage> categories) {

    /** État de couverture d'une catégorie ou d'une fonction. */
    public enum Status {
        /** Toutes les fonctions ont un parser actif qui a déjà publié. */
        COVERED,
        /** Une partie seulement des fonctions est couverte. */
        PARTIAL,
        /** Aucune fonction couverte. */
        NONE
    }

    public record CategoryCoverage(
            String code,
            String label,
            PepCategoryKind kind,
            int riskClass,
            String legalReference,
            Status status,
            List<FunctionCoverage> functions) {
    }

    /**
     * @param status  COVERED si un parser actif complet a publié, PARTIAL si seuls des parsers partiels l'ont fait
     * @param parsers parsers qui couvrent la fonction, avec leur complétude
     */
    public record FunctionCoverage(
            long id,
            String label,
            String institution,
            Integer expectedHolders,
            Status status,
            List<CoveringParser> parsers) {
    }

    /**
     * Un parser qui couvre la fonction, avec la complétude déclarée pour cette fonction ({@code completeness},
     * {@code includesFormer}). {@code personCount} est le total du parser (un parser peut couvrir
     * plusieurs fonctions, ex. bourgmestres et échevins).
     */
    public record CoveringParser(
            long id,
            String name,
            String datasetName,
            boolean enabled,
            Completeness completeness,
            boolean includesFormer,
            ParserExecutionStatus lastStatus,
            Instant lastPublishedAt,
            int personCount,
            int retainedCount) {
    }
}
