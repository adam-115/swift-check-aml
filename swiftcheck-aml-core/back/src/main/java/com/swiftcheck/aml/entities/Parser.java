package com.swiftcheck.aml.entities;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration d'une liste à ingérer : quel plugin utiliser, où trouver les
 * données, à quelle fréquence.
 *
 * <p>
 * Cette classe ne contient <b>que de la configuration</b>. Ce qui s'est passé
 * lors des exécutions
 * (statut, erreurs, nombre d'entités…) est enregistré dans
 * {@link ParserExecution}.
 *
 * <p>
 * Changer {@link #sourceLocation} (nouvelle URL) ne demande ni recompilation ni
 * redéploiement :
 * la nouvelle valeur est utilisée à l'exécution suivante.
 */
@Getter
@Setter
@Entity
public class Parser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Organisme qui publie la liste. */
    @ManyToOne
    private ListProvider listProvider;

    private String name;

    private String description;

    /**
     * Nom du dataset dans Yente, unique, en minuscules avec des tirets bas (ex.
     * {@code eu_sanctions}).
     * Sert aussi de nom de dossier pour la publication et l'archive.
     */
    @Column(unique = true, nullable = false)
    private String datasetName;

    /**
     * Identifiant du plugin PF4J (champ {@code Plugin-Id} du MANIFEST du JAR), ex.
     * {@code eu-parser}.
     */
    private String pluginId;

    /**
     * Chemin complet du JAR du plugin sur le serveur, ex.
     * {@code /opt/docker-stacks/application/plugins/swiftcheck-aml-eu-0.0.1.jar}.
     * Indique où le plugin est déployé. S'il n'est pas chargé au moment de
     * l'exécution, le core le charge
     * depuis ce chemin. Facultatif : sans chemin, le plugin doit être dans le
     * dossier des plugins.
     */
    @Column(length = 1024)
    private String pluginUrl;

    @Enumerated(EnumType.STRING)
    private DataSourceType dataSourceType;

    /**
     * URL (HTTP_FILE) ou chemin sur le serveur (LOCAL_FILE). Vide pour
     * PLUGIN_MANAGED.
     */
    @Column(length = 2048)
    private String sourceLocation;

    /**
     * Paramètres propres à la source, au format JSON objet, transmis au plugin.
     * Ex.
     * {@code {"endpoint": "https://query.wikidata.org/sparql", "query": "SELECT ..."}}.
     */
    @Column(columnDefinition = "TEXT")
    private String parameters;

    /**
     * Fréquence de mise à jour automatique, en heures. 0 = uniquement à la demande.
     */
    private int refreshHours;

    /** Type de source (T1 à T7) : fichier open data, API, SPARQL, journal officiel, page web, PDF, registre. */
    @Enumerated(EnumType.STRING)
    private ParserKind parserKind;

    /**
     * Mois pendant lesquels une entité disparue de la source reste publiée (voir {@link RetainedEntity}).
     * 0 pour les sanctions : une radiation s'applique tout de suite. Au moins 12 pour les PEP.
     */
    private int retentionMonths;

    /** Licence de la source, ex. « CC BY 4.0 ». */
    private String licence;

    /** Mention à citer quand les données sont réutilisées, ex. « © Union européenne ». */
    @Column(length = 1000)
    private String attribution;

    /** Source PEP lue par ce parser (vide pour une liste de sanctions). Un parser lit une seule source. */
    @ManyToOne
    private PepSource source;

    /** Fonctions PEP couvertes par ce parser : base de la page « Couverture PEP ». */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "parser_coverage",
            joinColumns = @JoinColumn(name = "parser_id"),
            inverseJoinColumns = @JoinColumn(name = "function_id"))
    private Set<PepFunction> coveredFunctions = new HashSet<>();

    /**
     * Un parser désactivé n'est plus exécuté et son dataset est retiré de Yente.
     */
    private boolean enabled = true;

    private Instant createdAt = Instant.now();

    private Instant updatedAt;
}
