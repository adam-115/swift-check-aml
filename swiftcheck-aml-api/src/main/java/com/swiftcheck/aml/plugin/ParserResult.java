package com.swiftcheck.aml.plugin;

import java.util.Map;

/**
 * Bilan d'une conversion, rendu par le plugin au core.
 *
 * <p>Le core ne s'y fie pas aveuglément : il recompte lui-même les entités du fichier produit. Les valeurs
 * rejetées et les conflits, en revanche, ne sont connus que du plugin ; ils servent aux contrôles qualité
 * (ex. plus de 1 % de valeurs rejetées → la liste n'est pas publiée).
 *
 * <p>Un plugin qui utilise {@code FtmDatasetBuilder} obtient ce bilan avec {@code dataset.toConversionResult()}.
 *
 * @param entityCount        nombre d'entités écrites
 * @param rejectedCount      nombre de valeurs refusées (pays ou date illisible, propriété inconnue…)
 * @param conflictCount      nombre d'identifiants utilisés pour deux types d'entité différents
 * @param rejectedByProperty détail des rejets, ex. {@code {"Person.nationality": 3}}
 * @param message            commentaire libre affiché dans l'historique (peut être {@code null})
 */
public record ParserResult(
        int entityCount,
        int rejectedCount,
        int conflictCount,
        Map<String, Integer> rejectedByProperty,
        String message) {
}
