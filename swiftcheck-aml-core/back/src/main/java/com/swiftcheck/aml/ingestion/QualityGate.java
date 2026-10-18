package com.swiftcheck.aml.ingestion;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.swiftcheck.aml.plugin.ParserResult;

/**
 * Contrôles qualité d'un fichier produit par un plugin, <b>avant</b> sa publication dans Yente.
 *
 * <p>Si un contrôle échoue, l'exécution est BLOCKED : le nouveau fichier n'est pas publié et Yente continue
 * d'utiliser la dernière version valide. Exemple typique : la source change de format, le plugin ne trouve
 * plus rien, et sans ce contrôle la liste de sanctions serait vidée sans que personne ne s'en aperçoive.
 *
 * <p>Seuils réglables dans application.properties ({@code swiftcheck.quality.*}).
 */
@Component
public class QualityGate {

    private final double maxDropPercent;
    private final double maxRejectedPercent;

    public QualityGate(@Value("${swiftcheck.quality.max-drop-percent:20}") double maxDropPercent,
                       @Value("${swiftcheck.quality.max-rejected-percent:1.0}") double maxRejectedPercent) {
        this.maxDropPercent = maxDropPercent;
        this.maxRejectedPercent = maxRejectedPercent;
    }

    /**
     * @param produced      fichier écrit par le plugin, déjà lu par {@link DatasetFile#read}
     * @param result        bilan rendu par le plugin
     * @param previousCount nombre d'entités de la dernière version publiée (0 si aucune)
     * @return les problèmes trouvés, en français ; liste vide si tout est correct
     */
    public List<String> check(DatasetFile produced, ParserResult result, int previousCount) {
        List<String> problems = new ArrayList<>();

        if (!produced.errors().isEmpty()) {
            problems.add("Fichier produit invalide : " + String.join(" ; ", produced.errors()));
        }

        int count = produced.size();
        if (count == 0) {
            problems.add("Aucune entité produite : le format de la source a probablement changé.");
            return problems;
        }

        if (previousCount > 0) {
            double dropPercent = 100.0 * (previousCount - count) / previousCount;
            if (dropPercent > maxDropPercent) {
                problems.add(String.format("Baisse de %.1f %% du nombre d'entités (%d → %d), seuil %.0f %%.",
                        dropPercent, previousCount, count, maxDropPercent));
            }
        }

        double rejectedPercent = 100.0 * result.rejectedCount() / count;
        if (rejectedPercent > maxRejectedPercent) {
            problems.add(String.format("%d valeurs rejetées pour %d entités (%.1f %%), seuil %.1f %% : %s",
                    result.rejectedCount(), count, rejectedPercent, maxRejectedPercent, result.rejectedByProperty()));
        }

        if (result.conflictCount() > 0) {
            problems.add(result.conflictCount() + " identifiant(s) utilisé(s) pour deux types d'entité différents.");
        }
        return problems;
    }
}
