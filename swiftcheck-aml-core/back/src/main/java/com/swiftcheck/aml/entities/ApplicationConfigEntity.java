package com.swiftcheck.aml.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration globale de l'application (une seule ligne en base, modifiable depuis l'écran « Configuration »).
 * Une ligne avec des valeurs par défaut est créée par Liquibase (fichier 004-seed-data.xml).
 *
 * <p>Tous les chemins sont des chemins <b>sur le serveur</b> où tourne le core. Les dossiers sont créés
 * automatiquement s'ils n'existent pas.
 */
@Getter
@Setter
@Entity
@Table(name = "application_config")
public class ApplicationConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Adresse de l'API Yente, ex. {@code http://localhost:8000}. */
    private String yenteUrl;

    /** Jeton exigé par Yente pour recharger ses données ({@code POST /updatez?token=...}), variable YENTE_UPDATE_TOKEN côté Yente. */
    private String yenteUpdateToken;

    /** Dossier où sont publiés les fichiers NDJSON lus par Yente, ex. {@code D:/yente/data}. */
    private String yenteDataFolder;

    /**
     * Le même dossier vu depuis le conteneur Docker de Yente, ex. {@code /data}. Sert à écrire les chemins
     * dans le manifest, car c'est Yente qui le lit.
     */
    private String yenteContainerDataPath;

    /** Chemin du fichier manifest.yml de Yente, ex. {@code D:/yente/data/manifest.yml}. */
    private String yenteManifestPath;

    /** Dossier d'archive des fichiers sources signés (preuve des données utilisées à chaque exécution). */
    private String archiveFolder;

    /** Dossier de travail temporaire : un sous-dossier par exécution, supprimé à la fin. */
    private String workFolder;
}
