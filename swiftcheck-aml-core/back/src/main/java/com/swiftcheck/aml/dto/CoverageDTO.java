package com.swiftcheck.aml.dto;

import java.time.Instant;
import java.util.List;

import com.swiftcheck.aml.entities.Completeness;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.ParserKind;
import com.swiftcheck.aml.entities.PepCategoryKind;
import com.swiftcheck.aml.entities.PepSourceStatus;
import com.swiftcheck.aml.entities.SourceAccess;

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
     * @param parsers parsers qui couvrent la fonction
     * @param sources sources identifiées où récupérer ses titulaires (implémentées ou non)
     */
    public record FunctionCoverage(
            long id,
            String label,
            String institution,
            Integer expectedHolders,
            Status status,
            List<CoveringParser> parsers,
            List<SourceRef> sources) {
    }

    /**
     * Une source identifiée pour la fonction.
     *
     * @param implemented vrai si un parser lit déjà cette source
     */
    public record SourceRef(
            long id,
            String name,
            String url,
            ParserKind parserKind,
            SourceAccess access,
            PepSourceStatus status,
            Completeness completeness,
            boolean includesFormer,
            boolean implemented) {
    }

    /**
     * Un parser qui couvre la fonction. {@code personCount} est le total du parser (un parser peut couvrir
     * plusieurs fonctions, ex. bourgmestres et échevins).
     */
    public record CoveringParser(
            long id,
            String name,
            String datasetName,
            boolean enabled,
            ParserExecutionStatus lastStatus,
            Instant lastPublishedAt,
            int personCount,
            int retainedCount) {
    }
}
