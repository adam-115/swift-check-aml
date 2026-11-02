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
     * @param reason  pourquoi la fonction a cet état (« Couverte à 100 % : … », « Partielle : … »)
     * @param issues  problèmes de fonctionnement qui pèsent sur l'état : parser désactivé, jamais publié, dernière
     *                exécution bloquée ; vide s'il n'y en a pas
     * @param parsers parsers qui couvrent la fonction, avec leur complétude, ce qu'ils couvrent et ce qui manque
     */
    public record FunctionCoverage(
            long id,
            String label,
            String institution,
            Integer expectedHolders,
            Status status,
            String reason,
            List<String> issues,
            List<CoveringParser> parsers) {
    }

    /**
     * Un parser qui couvre la fonction, avec la complétude déclarée pour cette fonction ({@code completeness},
     * {@code includesFormer}). {@code personCount} est le total du parser (un parser peut couvrir
     * plusieurs fonctions, ex. bourgmestres et échevins). {@code coveredScope} et {@code gaps} : ce que le parser
     * couvre de la fonction et ce qui manque, saisis sur le lien.
     */
    public record CoveringParser(
            long id,
            String name,
            String datasetName,
            boolean enabled,
            Completeness completeness,
            boolean includesFormer,
            String coveredScope,
            String gaps,
            ParserExecutionStatus lastStatus,
            Instant lastPublishedAt,
            int personCount,
            int retainedCount) {
    }
}
