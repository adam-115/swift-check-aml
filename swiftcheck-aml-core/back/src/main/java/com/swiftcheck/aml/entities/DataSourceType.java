package com.swiftcheck.aml.entities;

/**
 * D'où viennent les données d'un parser. Détermine ce que le core fait <b>avant</b> d'appeler le plugin.
 */
public enum DataSourceType {

    /** Fichier à télécharger (XML, CSV, JSON…) : {@code Parser.sourceLocation} contient l'URL. */
    HTTP_FILE,

    /** Fichier déjà présent sur le serveur : {@code Parser.sourceLocation} contient son chemin. */
    LOCAL_FILE,

    /**
     * Le plugin récupère lui-même ses données (requête SPARQL, API paginée, base SQL, service maison…).
     * Le core ne télécharge rien ; le plugin doit déposer ses réponses brutes dans {@code ParserContext.rawDir()}.
     */
    PLUGIN_MANAGED
}
