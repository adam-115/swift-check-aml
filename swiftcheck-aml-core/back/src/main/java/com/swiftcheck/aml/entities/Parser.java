package com.swiftcheck.aml.entities;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import lombok.AccessLevel;
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

    private String name;

    private String description;

    /**
     * Nom du dataset dans Yente, unique, en minuscules avec des tirets bas (ex.
     * {@code eu_sanctions}).
     * Sert aussi de nom de dossier pour la publication et l'archive.
     */
    @Column(unique = true, nullable = false)
    private String datasetName;

    /** PEP, AML (sanctions), MEDIA ou CRIMINAL. Un parser de sanctions ne couvre aucune fonction PEP. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ListType listType = ListType.PEP;

    /** Site de l'organisme qui publie la liste, ex. {@code https://www.chd.lu}. */
    @Column(length = 1024)
    private String websiteUrl;

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

    /**
     * Type de source (T1 à T7) : fichier open data, API, SPARQL, journal officiel,
     * page web, PDF, registre.
     */
    @Enumerated(EnumType.STRING)
    private ParserKind parserKind;

    /**
     * Mois pendant lesquels une entité disparue de la source reste publiée (voir
     * {@link RetainedEntity}).
     * 0 pour les sanctions : une radiation s'applique tout de suite. Au moins 12
     * pour les PEP.
     */
    private int retentionMonths;

    /** Licence de la source, ex. « CC BY 4.0 ». */
    private String licence;

    /**
     * Mention à citer quand les données sont réutilisées, ex. « © Union européenne
     * ».
     */
    @Column(length = 1000)
    private String attribution;

    /** Remarques sur la source : limites, choix d'extraction, pistes. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Description technique en Markdown : ce que fait le parser exactement (source, règles, paramètres, entités
     * produites, limites). Reprend en général le README du plugin, complété depuis l'écran « Parsers ».
     * Modifiée seulement par {@code PUT /api/parsers/{id}/technical-description}, pas par le formulaire du parser.
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String technicalDescription;

    /**
     * Fonctions PEP couvertes par ce parser, avec leur complétude : base de la page « Couverture PEP ».
     * Vide pour une liste de sanctions. Modifiée par {@link #replaceCoverage}.
     */
    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "parser", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<PepFunctionParser> coverage = new ArrayList<>();

    /**
     * Un parser désactivé n'est plus exécuté et son dataset est retiré de Yente.
     */
    private boolean enabled = true;

    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    /** Fonctions couvertes, sans leur complétude. */
    public Set<PepFunction> getCoveredFunctions() {
        Set<PepFunction> functions = new LinkedHashSet<>();
        coverage.forEach(link -> functions.add(link.getFunction()));
        return functions;
    }

    /**
     * Remplace les fonctions couvertes. Les liens existants sont modifiés sur place plutôt que supprimés puis
     * recréés : Hibernate insère avant de supprimer, ce qui violerait la clé (fonction, parser).
     */
    public void replaceCoverage(List<PepFunctionParser> wanted) {
        Map<Long, PepFunctionParser> byFunction = new LinkedHashMap<>();
        for (PepFunctionParser link : wanted) {
            byFunction.putIfAbsent(link.getFunction().getId(), link);
        }
        coverage.removeIf(link -> !byFunction.containsKey(link.getFunction().getId()));
        for (PepFunctionParser link : coverage) {
            PepFunctionParser change = byFunction.remove(link.getFunction().getId());
            link.setCompleteness(change.getCompleteness());
            link.setIncludesFormer(change.isIncludesFormer());
        }
        for (PepFunctionParser added : byFunction.values()) {
            added.setParser(this);
            added.setId(new PepFunctionParser.Key(added.getFunction().getId(), id == 0 ? null : id));
            coverage.add(added);
        }
    }
}
