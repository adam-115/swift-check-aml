package com.swiftcheck.aml.plugin;

import java.nio.file.Path;
import java.util.Map;

/**
 * Ce que le core donne au plugin pour <b>une</b> exécution. Créé par le core juste avant l'appel
 * à {@link Iparser#parse}, puis abandonné : il n'est jamais réutilisé.
 *
 * @param datasetName nom du dataset dans Yente (ex. {@code eu_sanctions}), pour les messages et les noms de fichiers
 * @param inputFile   fichier source déjà récupéré, signé (SHA-256) et archivé par le core.
 *                    {@code null} pour une source de type PLUGIN_MANAGED : le plugin va alors chercher
 *                    ses données lui-même (requête SPARQL, API, base SQL…).
 * @param rawDir      dossier vide réservé à cette exécution. Un plugin PLUGIN_MANAGED doit y enregistrer
 *                    chaque réponse brute reçue (ex. {@code page1.json}) : le core les signe et les archive
 *                    après l'appel, ce qui garde la preuve des données utilisées. Si ce dossier reste vide,
 *                    l'exécution est bloquée.
 * @param outputFile  fichier où le plugin doit écrire le résultat, au format NDJSON FtM (une entité par ligne).
 *                    C'est un fichier de travail : le core le contrôle avant de le publier.
 * @param parameters  paramètres propres à la source, saisis dans la configuration du parser
 *                    (ex. {@code query} pour une requête SPARQL). Jamais {@code null}, éventuellement vide.
 */
public record ParserContext(
        String datasetName,
        Path inputFile,
        Path rawDir,
        Path outputFile,
        Map<String, String> parameters) {

    /** Paramètre obligatoire : lève une exception claire s'il manque dans la configuration du parser. */
    public String requireParameter(String name) {
        String value = parameters.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Paramètre « " + name + " » manquant dans la configuration du parser "
                    + datasetName);
        }
        return value;
    }
}
