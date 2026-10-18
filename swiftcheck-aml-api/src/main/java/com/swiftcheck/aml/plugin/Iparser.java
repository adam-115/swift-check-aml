package com.swiftcheck.aml.plugin;

import java.util.List;

import org.pf4j.ExtensionPoint;

/**
 * Contrat d'un plugin de liste (un JAR PF4J par source : UE, ONU, PEP
 * Luxembourg…).
 *
 * <h2>Rôle du plugin : convertir, et rien d'autre</h2>
 * Le plugin lit les données de sa source et écrit des entités FollowTheMoney
 * (FtM) dans
 * {@link ParserContext#outputFile()}. Tout le reste est fait par le core, avant
 * et après l'appel :
 * <ul>
 * <li><b>avant</b> : téléchargement ou lecture du fichier source, empreinte
 * SHA-256, archivage, trace en base ;</li>
 * <li><b>après</b> : contrôles qualité, comparaison avec la version précédente,
 * publication dans Yente.</li>
 * </ul>
 * Le plugin ne doit donc jamais écrire dans le dossier de Yente, modifier le
 * manifest ni accéder à la base.
 *
 * <h2>Règle : aucun état</h2>
 * Une instance peut servir à plusieurs exécutions successives. Elle ne doit
 * garder aucune donnée d'une
 * exécution à l'autre dans ses champs : tout ce qui concerne une exécution vit
 * dans les variables locales
 * de {@link #parse} ou dans le {@link ParserContext} reçu.
 *
 * <h2>Exemple minimal</h2>
 * 
 * <pre>{@code
 * @Extension
 * public class EuSanctionsParser implements Iparser {
 *     public ParserResult parse(ParserContext ctx) throws Exception {
 *         FtmDatasetBuilder out = new FtmDatasetBuilder("eu-fsd", "sanction");
 *         try (InputStream in = Files.newInputStream(ctx.inputFile())) {
 *             XmlRecordReader.readEach(in, "sanctionEntity", fiche -> convertir(fiche, out));
 *         }
 *         out.writeNdjsonFile(ctx.outputFile());
 *         return out.toConversionResult();
 *     }
 * }
 * }</pre>
 */
public interface Iparser extends ExtensionPoint {

    /**
     * Convertit la source en entités FtM écrites dans {@code ctx.outputFile()}.
     *
     * @param ctx tout ce dont le plugin a besoin pour cette exécution (fichier
     *            d'entrée, dossiers, paramètres)
     * @return le bilan de la conversion (entités, valeurs rejetées…), utilisé par
     *         les contrôles qualité
     * @throws Exception en cas d'erreur : l'exécution est alors marquée FAILED et
     *                   rien n'est publié
     */
    ParserResult parse(ParserContext ctx) throws Exception;

    /**
     * Relit le fichier source et liste les éléments qu'il contient (personnes, organisations, navires…),
     * <b>sans passer par la conversion</b>. Sert au test du parser : le core cherche chaque élément dans
     * Yente pour prouver qu'il a bien été converti, indexé et qu'on le retrouve par son nom.
     *
     * <p>À implémenter en lisant la source directement (pas en relisant le NDJSON produit), et en calculant
     * l'ID avec {@code FtmDatasetBuilder.buildEntityId(...)} comme lors de la conversion. Seuls les éléments
     * criblables sont attendus : pas les entités {@code Sanction}, {@code Address}…
     *
     * @param ctx même contexte que {@link #parse}, avec le fichier source archivé de la dernière
     *            publication ({@code outputFile} vaut {@code null})
     * @return les éléments de la source, ou {@code null} si le plugin ne sait pas relire sa source
     *         (le test est alors impossible)
     */
    default List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        return null;
    }
}
