package com.swiftcheck.aml.plugin;

/**
 * Un élément lu directement dans le fichier source (une fiche UE, une ligne CSV…), utilisé par le test du
 * parser pour vérifier sa présence dans Yente.
 *
 * @param entityId  ID FtM attendu, calculé comme lors de la conversion (ex. {@code eu-fsd-13})
 * @param schema    schéma FtM attendu : {@code Person}, {@code Organization}, {@code Vessel}…
 * @param name      nom principal tel qu'écrit dans la source ; {@code null} si la source n'en donne pas
 *                  (l'élément n'est alors cherché que par son ID)
 * @param sourceRef référence lisible dans la source, affichée en cas d'échec (ex. {@code logicalId=13})
 */
public record SourceRecord(String entityId, String schema, String name, String sourceRef) {
}
