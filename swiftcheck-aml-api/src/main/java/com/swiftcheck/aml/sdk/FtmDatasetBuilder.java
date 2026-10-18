package com.swiftcheck.aml.sdk;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.swiftcheck.aml.plugin.ParserResult;

/**
 * Construit le dataset FtM d'une liste : collecte les entités produites par un parser et les écrit dans
 * un fichier NDJSON lisible par Yente.
 *
 * <h2>À quoi sert cette classe ?</h2>
 * Un parser (ex. {@code EuSanctionsParser}) lit un fichier officiel et crée des entités FollowTheMoney
 * (personnes, organisations, sanctions…). Il ne sait pas où ni comment elles seront stockées : il les ajoute
 * à un {@code FtmDatasetBuilder}, qui :
 * <ol>
 *   <li><b>fabrique des identifiants stables</b> : la même personne de la même liste reçoit toujours le même ID
 *       (ex. {@code eu-fsd-13}), d'une exécution à l'autre. C'est indispensable pour le différentiel
 *       (ajouts / retraits) et pour que les alertes déjà traitées restent rattachées à la bonne entité ;</li>
 *   <li><b>contrôle le schéma</b> : seuls les schémas FtM listés dans {@link #SUPPORTED_SCHEMAS} sont acceptés ;</li>
 *   <li><b>ajoute les catégories de risque</b> (« topics ») de la source, par exemple {@code sanction} ou
 *       {@code role.pep}, aux entités criblables (personnes, sociétés, navires…) ;</li>
 *   <li><b>fusionne les doublons</b> : si le fichier source décrit deux fois la même entité (même ID),
 *       les valeurs sont réunies en une seule entité au lieu de créer deux lignes ;</li>
 *   <li><b>tient le bilan qualité</b> ({@link EmitReport}) : nombre d'entités par schéma, valeurs rejetées,
 *       doublons fusionnés, conflits. {@code QualityGate} s'en sert pour bloquer une liste défectueuse ;</li>
 *   <li><b>écrit le fichier final</b> au format NDJSON (une entité JSON par ligne), sans jamais laisser
 *       un fichier à moitié écrit (écriture dans un fichier temporaire puis déplacement atomique).</li>
 * </ol>
 *
 * <h2>Cycle de vie, dans un plugin ({@code Iparser.parse})</h2>
 * <pre>{@code
 * FtmDatasetBuilder dataset = new FtmDatasetBuilder("eu-fsd", "sanction"); // 1. un dataset par exécution
 * ... lecture de ctx.inputFile() : dataset.newEntity(...), puis dataset.addEntity(...) // 2. conversion
 * dataset.writeNdjsonFile(ctx.outputFile());                                    // 3. écriture du fichier
 * return dataset.toConversionResult();                                          // 4. bilan rendu au core
 * }</pre>
 * Le core contrôle ensuite le bilan et le fichier ({@code QualityGate}) avant de publier dans Yente.
 *
 * <h2>Exemple de ligne produite (fichier de test eu_sample.xml)</h2>
 * <pre>{@code
 * {"id":"eu-fsd-13","schema":"Person","properties":{"name":["Ivan PETROV"],"nationality":["ru"],"birthDate":["1965-03-15"],"topics":["sanction"]}}
 * }</pre>
 *
 * <h2>Mémoire et threads</h2>
 * Toutes les entités sont gardées en mémoire jusqu'à l'écriture, ce qui permet de fusionner les doublons.
 * Pour les listes de sanctions (quelques dizaines de milliers d'entités), cela représente quelques dizaines
 * de Mo. Les méthodes qui lisent ou modifient la collection sont {@code synchronized} par précaution ;
 * en pratique, chaque parser appelle le builder depuis un seul thread.
 */
public class FtmDatasetBuilder {

    /**
     * Schémas FollowTheMoney acceptés par {@link #newEntity}. Un autre nom (faute de frappe comme « Persn »,
     * schéma inexistant) provoque une exception immédiate, pour que l'erreur soit vue pendant les tests
     * et non découverte plus tard dans Yente.
     */
    public static final Set<String> SUPPORTED_SCHEMAS = Set.of(
            "Thing", "LegalEntity", "Person", "Organization", "Company", "PublicBody", "Vessel", "Airplane",
            "Sanction", "Position", "Occupancy", "Address", "Identification", "Passport", "Family", "Associate",
            "Ownership", "Directorship", "Membership", "Employment", "UnknownLink", "Representation", "Note");

    /**
     * Schémas « criblables » : ce sont les entités que l'on peut cribler (une personne, une société, un navire…).
     * Seules elles reçoivent les catégories de risque. Une entité {@code Sanction} ou {@code Address} décrit
     * une entité criblable mais n'est pas elle-même criblée : elle ne reçoit donc pas de catégorie de risque.
     */
    public static final Set<String> SCREENABLE_SCHEMAS = Set.of(
            "Thing", "LegalEntity", "Person", "Organization", "Company", "PublicBody", "Vessel", "Airplane");

    /** Longueur maximale d'un ID lisible ; au-delà, la clé est remplacée par son empreinte SHA-1 (voir {@link #buildEntityId}). */
    private static final int MAX_ID_LENGTH = 100;

    /** Préfixe propre à la source, ajouté devant chaque ID (ex. {@code eu-fsd}, {@code un-data}). */
    private final String idPrefix;

    /** Catégories de risque (topics FtM) ajoutées aux entités criblables (ex. {@code sanction}). Peut être vide. */
    private final List<String> riskTopics;

    /**
     * Les entités ajoutées, rangées par ID. {@link LinkedHashMap} conserve l'ordre d'arrivée : le fichier
     * produit suit l'ordre du fichier source, ce qui facilite la comparaison de deux versions.
     */
    private final Map<String, FtmEntity> entities = new LinkedHashMap<>();

    /** Bilan qualité, partagé avec chaque {@link FtmEntity} créée pour qu'elle y signale ses valeurs rejetées. */
    private final EmitReport report = new EmitReport();

    /**
     * Crée un builder pour une exécution d'une source. On en crée un nouveau à chaque ingestion : il ne doit
     * pas être réutilisé, sinon les entités de l'exécution précédente seraient réécrites.
     *
     * @param idPrefix      préfixe d'ID propre à la source (ex. {@code un-data}, {@code lu-pep}).
     *                      Il est nettoyé par {@link #toIdPart} : accents et espaces sont retirés.
     * @param riskTopics catégories de risque (topics FtM) ajoutées aux entités criblables (ex. {@code sanction}, {@code role.pep}) ;
     *                      on peut n'en passer aucun.
     * @throws IllegalArgumentException si le préfixe est vide (les IDs ne seraient plus uniques entre sources)
     */
    public FtmDatasetBuilder(String idPrefix, String... riskTopics) {
        // Sans préfixe, deux sources différentes pourraient produire le même ID (ex. "13") : on refuse.
        if (idPrefix == null || idPrefix.isBlank()) {
            throw new IllegalArgumentException("idPrefix obligatoire");
        }
        // Nettoyer le préfixe une fois pour toutes ("EU FSD" → "EU-FSD") : il est recopié dans chaque ID.
        this.idPrefix = toIdPart(idPrefix);
        // Copie non modifiable de la liste des topics : personne ne peut la changer après la création.
        this.riskTopics = List.of(riskTopics);
    }

    /**
     * Construit un identifiant stable à partir d'un ou plusieurs morceaux de clé.
     *
     * <p>Chaque morceau est nettoyé ({@link #toIdPart}), les morceaux vides ou {@code null} sont ignorés, puis tout
     * est assemblé avec des tirets derrière le préfixe :
     * <pre>{@code
     * new FtmDatasetBuilder("lu-pep").buildEntityId("Q42", "lu pos député")  →  "lu-pep-Q42-lu-pos-depute"
     * new FtmDatasetBuilder("eu-fsd").buildEntityId("13", "sanction")        →  "eu-fsd-13-sanction"
     * }</pre>
     *
     * <p>Règle importante : la clé doit venir d'un identifiant <b>officiel et stable</b> de la source
     * (numéro de fiche, référence ONU…), jamais d'un compteur ou d'un nom qui peut changer d'orthographe.
     *
     * <p>Si l'ID dépasse 100 caractères ({@code MAX_ID_LENGTH}) (adresse complète utilisée comme clé, par exemple),
     * la clé est remplacée par son empreinte SHA-1 : l'ID reste stable et unique, mais n'est plus lisible.
     *
     * @throws IllegalArgumentException si tous les morceaux sont vides : deux entités différentes
     *                                  recevraient sinon le même ID
     */
    public String buildEntityId(String... keyParts) {
        // La clé est construite morceau par morceau, ex. "13" puis "sanction" → "13-sanction".
        StringBuilder key = new StringBuilder();
        for (String part : keyParts) {
            // Nettoyer le morceau (accents, espaces, ponctuation) ; un morceau null devient vide.
            String s = part == null ? "" : toIdPart(part);
            if (s.isEmpty()) {
                continue;                       // morceau absent : on l'ignore plutôt que de produire "--"
            }
            // Un tiret entre deux morceaux, mais pas avant le premier.
            if (key.length() > 0) {
                key.append('-');
            }
            key.append(s);
        }
        // Aucun morceau utilisable : toutes les entités concernées auraient le même ID → erreur immédiate.
        if (key.length() == 0) {
            throw new IllegalArgumentException("Clé d'ID vide pour le préfixe " + idPrefix);
        }
        // ID final = préfixe de la source + clé, ex. "eu-fsd-13-sanction".
        String id = idPrefix + "-" + key;
        // ID trop long (ex. une adresse complète utilisée comme clé) : on le remplace par l'empreinte SHA-1
        // de la clé, toujours la même pour la même clé, donc l'ID reste stable d'une exécution à l'autre.
        return id.length() <= MAX_ID_LENGTH ? id : idPrefix + "-" + sha1Hex(key.toString());
    }

    /**
     * Crée une entité vide, prête à être remplie par le parser avec {@code add(...)}.
     *
     * <p>L'entité n'est <b>pas</b> encore enregistrée : il faut appeler {@link #addEntity} une fois qu'elle est remplie.
     * Exemple dans un parser :
     * <pre>{@code
     * FtmEntity person = out.newEntity("Person", fiche.attribute("logicalId"));
     * person.addValue("name", "Ivan PETROV");
     * person.addValue("nationality", "Russian Federation");   // normalisé en "ru" par FtmEntity
     * out.addEntity(person);
     * }</pre>
     *
     * @param schema   schéma FtM (doit figurer dans {@link #SUPPORTED_SCHEMAS})
     * @param keyParts morceaux de clé passés à {@link #buildEntityId}
     * @throws IllegalArgumentException si le schéma n'est pas supporté ou si la clé est vide
     */
    public FtmEntity newEntity(String schema, String... keyParts) {
        // Une faute de frappe dans le schéma ("Persn") est une erreur de programmation : on s'arrête
        // tout de suite, pendant les tests, plutôt que de produire des entités que Yente rejettera.
        if (!SUPPORTED_SCHEMAS.contains(schema)) {
            throw new IllegalArgumentException("Schéma FtM non supporté : " + schema);
        }
        // Créer l'entité vide avec son ID stable. Elle n'est pas encore enregistrée : il faudra appeler addEntity().
        // L'entité reçoit le bilan qualité : chaque valeur rejetée (pays ou date illisible…) y sera comptée.
        return new FtmEntity(buildEntityId(keyParts), schema, report);
    }

    /**
     * Enregistre une entité remplie par le parser. Quatre cas possibles :
     * <ol>
     *   <li><b>entité vide</b> (aucune propriété valide) : ignorée et comptée comme « vide » dans le bilan ;</li>
     *   <li><b>nouvel ID</b> : l'entité est ajoutée et comptée dans son schéma ;</li>
     *   <li><b>ID déjà vu, même schéma</b> : les valeurs sont ajoutées à l'entité existante (fusion),
     *       sans doublon de valeur ; comptée comme « fusionnée » ;</li>
     *   <li><b>ID déjà vu, schéma différent</b> (ex. Person puis Organization) : c'est une erreur dans la
     *       source ou dans le parser. La seconde entité est écartée et un « conflit » est noté ;
     *       {@code QualityGate} bloque la publication s'il y en a.</li>
     * </ol>
     * Avant ces contrôles, les catégories de risque sont ajoutées si l'entité est criblable ({@link #SCREENABLE_SCHEMAS}).
     */
    public synchronized void addEntity(FtmEntity entity) {
        // Cas 1 : rien d'utilisable (toutes les valeurs ont été rejetées ou aucune n'a été ajoutée).
        if (entity.isEmpty()) {
            report.countEmptyEntity();     // compté dans le bilan : beaucoup d'entités vides signale un problème de lecture
            return;             // rien à enregistrer
        }
        // Catégories de risque de la source : une personne de la liste UE devient "sanction".
        // Seules les entités criblables (personne, société, navire…) reçoivent le topic ; une Sanction
        // ou une Address ne sont pas criblées. Un topic déjà présent n'est pas dupliqué.
        if (SCREENABLE_SCHEMAS.contains(entity.getSchema())) {
            for (String topic : riskTopics) {
                entity.addValue("topics", topic);
            }
        }
        // Chercher si une entité avec le même ID a déjà été ajoutée (même fiche présente deux fois dans la source).
        FtmEntity existing = entities.get(entity.getId());
        // Cas 2 : première fois qu'on voit cet ID.
        if (existing == null) {
            entities.put(entity.getId(), entity);       // ranger l'entité sous son ID
            report.countEntity(entity.getSchema());     // compter une entité de plus pour ce schéma
            return;
        }
        // Cas 4 : même ID mais nature différente → on ne mélange pas une personne et une société.
        if (!existing.getSchema().equals(entity.getSchema())) {
            // Noter le conflit avec les deux schémas, pour que le message de blocage soit compréhensible.
            report.countIdConflict(entity.getId() + " : " + existing.getSchema() + " vs " + entity.getSchema());
            return;             // la seconde entité est écartée ; le core bloquera la publication
        }
        // Cas 3 : même entité décrite deux fois → on réunit ses valeurs (alias, adresses, documents…).
        existing.mergeValuesFrom(entity);     // ajoute les valeurs nouvelles, ignore celles déjà présentes
        report.countMergedDuplicate();                // compter un doublon fusionné dans le bilan
    }

    /**
     * Bilan qualité de l'exécution en cours : entités par schéma, valeurs rejetées, entités vides,
     * doublons fusionnés, conflits. Rendu au core via {@link #toConversionResult()}, où il sert aux contrôles qualité.
     */
    public EmitReport qualityReport() {
        // Le même objet que celui rempli pendant la conversion : il est à jour à tout moment.
        return report;
    }

    /**
     * Bilan à rendre au core à la fin de {@code Iparser.parse(...)} :
     * <pre>{@code
     * dataset.writeNdjsonFile(ctx.outputFile());         
     * return dataset.toConversionResult();
     * }</pre>
     * Le message résume les entités par schéma et les doublons fusionnés, pour l'historique des exécutions.
     */
    public ParserResult toConversionResult() {
        // Résumé lisible, affiché dans l'historique des exécutions, ex.
        // "Entités par schéma : {Person=8000, Sanction=12000} ; doublons fusionnés : 3 ; entités vides ignorées : 0".
        String message = "Entités par schéma : " + report.getEntitiesBySchema()
                + " ; doublons fusionnés : " + report.getMergedDuplicateCount()
                + " ; entités vides ignorées : " + report.getEmptyEntityCount();
        // Les chiffres utilisés par les contrôles qualité du core : entités, rejets, conflits, détail des rejets.
        return new ParserResult(report.getTotalEntities(), report.getTotalRejected(), report.getIdConflictCount(),
                report.getRejectedByProperty(), message);
    }

    /**
     * Toutes les entités enregistrées, dans l'ordre d'arrivée. La collection est en lecture seule :
     * on ne peut ni ajouter ni retirer d'entité par ce biais (il faut passer par {@link #addEntity}).
     * Utilisée par les tests pour vérifier le résultat d'un parser.
     */
    public synchronized Collection<FtmEntity> entities() {
        // Vue en lecture seule : on peut parcourir les entités mais pas en ajouter ni en retirer ici.
        return Collections.unmodifiableCollection(entities.values());
    }

    /**
     * Écrit toutes les entités dans le fichier {@code target}, sans risque de fichier incomplet.
     *
     * <p>Yente peut lire le fichier à tout moment. Si on écrivait directement dedans, il pourrait tomber sur
     * un fichier à moitié écrit (ou vide si le programme plante). On procède donc en deux temps :
     * <ol>
     *   <li>écriture complète dans {@code <target>.tmp} ;</li>
     *   <li>remplacement de {@code target} par le fichier temporaire en une seule opération (« déplacement
     *       atomique ») : un lecteur voit soit l'ancien fichier entier, soit le nouveau entier.</li>
     * </ol>
     * Certains systèmes de fichiers (partages réseau, certains volumes Docker) ne savent pas faire de
     * déplacement atomique : on se rabat alors sur un déplacement simple.
     *
     * @param target fichier de destination ; ses dossiers parents sont créés si besoin
     * @throws IOException si l'écriture ou le déplacement échoue (le fichier existant n'est alors pas modifié)
     */
    public synchronized void writeNdjsonFile(Path target) throws IOException {
        // Chemin complet du fichier final.
        Path dest = target.toAbsolutePath();
        // Créer le dossier de destination s'il n'existe pas encore.
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        // Fichier temporaire dans le même dossier, ex. "eu_sanctions.ndjson.tmp".
        // Même dossier = même disque, condition pour pouvoir le renommer en une seule opération.
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        // Écrire tout le contenu dans le fichier temporaire, en UTF-8 (accents, cyrillique, arabe…).
        // Le try (...) ferme le fichier à la fin, même en cas d'erreur.
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            writeNdjson(w);
        }
        try {
            // Remplacer le fichier final par le temporaire en une seule opération : un lecteur voit
            // soit l'ancien fichier complet, soit le nouveau complet, jamais un fichier à moitié écrit.
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Certains disques (partage réseau, volume Docker) ne savent pas le faire : remplacement simple.
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Écrit les entités au format NDJSON (« newline-delimited JSON ») : un objet JSON complet par ligne.
     * Ce format se lit ligne par ligne, sans charger tout le fichier en mémoire, c'est celui qu'attend Yente.
     * Utile aussi dans les tests avec un {@link java.io.StringWriter}.
     */
    public synchronized void writeNdjson(Writer w) throws IOException {
        // Une entité par ligne, dans l'ordre d'arrivée.
        for (FtmEntity e : entities.values()) {
            w.write(toJsonLine(e));     // l'entité sur une seule ligne JSON
            w.write('\n');          // fin de ligne : c'est elle qui sépare deux entités en NDJSON
        }
    }

    /**
     * Convertit une entité en une ligne JSON au format FollowTheMoney :
     * <pre>{@code {"id":"…","schema":"Person","properties":{"name":["…"],"alias":["…","…"]}}}</pre>
     * Chaque propriété est toujours une <b>liste</b> de valeurs, même s'il n'y en a qu'une : c'est la règle FtM.
     *
     * <p>Le JSON est construit à la main (sans Jackson) car la structure est fixe et simple : cela garde le
     * module SDK sans dépendance et rapide sur des fichiers de plusieurs dizaines de milliers de lignes.
     */
    static String toJsonLine(FtmEntity e) {
        // StringBuilder : assemble le texte morceau par morceau sans recréer une chaîne à chaque ajout.
        // 256 = taille de départ, suffisante pour la plupart des entités.
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"id\":");                 // {"id":
        appendJsonString(sb, e.getId());                   //        "eu-fsd-13"
        sb.append(",\"schema\":");              // ,"schema":
        appendJsonString(sb, e.getSchema());               //           "Person"
        sb.append(",\"properties\":{");         // ,"properties":{
        // Vrai tant qu'aucune propriété n'a été écrite (pour savoir s'il faut une virgule).
        boolean firstProp = true;
        // Pour chaque propriété : son nom et l'ensemble de ses valeurs (sans doublon, dans l'ordre d'ajout).
        for (Map.Entry<String, LinkedHashSet<String>> p : e.getProperties().entrySet()) {
            if (!firstProp) {
                sb.append(',');                 // virgule entre deux propriétés, pas avant la première
            }
            firstProp = false;
            appendJsonString(sb, p.getKey());              // "name"
            sb.append(":[");                    //       :[   — toujours une liste, même pour une seule valeur
            boolean firstVal = true;
            for (String v : p.getValue()) {
                if (!firstVal) {
                    sb.append(',');             // virgule entre deux valeurs d'une même propriété
                }
                firstVal = false;
                appendJsonString(sb, v);                   // "Ivan PETROV"
            }
            sb.append(']');                     // fin de la liste de valeurs
        }
        sb.append("}}");                        // fin de "properties" puis fin de l'entité
        return sb.toString();
    }

    /**
     * Ajoute {@code s} entre guillemets en échappant les caractères interdits en JSON.
     * Exemple : le nom {@code Ali "Le Chimiste"} devient {@code "Ali \"Le Chimiste\""}.
     * Un retour à la ligne doit aussi être échappé ({@code \n}), sinon il casserait le format « une entité par ligne ».
     * Les autres caractères (accents, cyrillique, arabe…) sont écrits tels quels, le fichier étant en UTF-8.
     */
    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');                         // guillemet ouvrant
        // Parcourir le texte caractère par caractère pour échapper ceux qui casseraient le JSON.
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;      // un guillemet fermerait la chaîne trop tôt
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;      // un retour à la ligne couperait l'entité en deux lignes
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        // autres caractères de contrôle invisibles : notation \\uXXXX
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);           // caractère normal (lettres, accents, chiffres…) : copié tel quel
                    }
            }
        }
        sb.append('"');                         // guillemet fermant
    }

    /**
     * Transforme un texte libre en morceau d'ID sûr : uniquement lettres sans accent, chiffres, point,
     * tiret bas et tiret.
     * <pre>{@code
     * "lu pos député"  →  "lu-pos-depute"
     * " QDi.001 "      →  "QDi.001"
     * "A / B"          →  "A-B"
     * }</pre>
     * Étapes : suppression des espaces aux extrémités, décomposition des lettres accentuées (é → e + accent)
     * puis suppression des accents, remplacement de tout autre caractère par un tiret, fusion des tirets
     * multiples, suppression des tirets en début et en fin.
     */
    static String toIdPart(String raw) {
        // Exemple suivi ligne par ligne avec "  lu pos député " :
        return java.text.Normalizer.normalize(raw.trim(), java.text.Normalizer.Form.NFKD) // espaces retirés, "é" décomposé en "e" + accent
                .replaceAll("\\p{M}", "")                 // retirer les accents             → "lu pos depute"
                .replaceAll("[^A-Za-z0-9._]+", "-")       // autre caractère → tiret         → "lu-pos-depute"
                .replaceAll("-{2,}", "-")                 // plusieurs tirets → un seul
                .replaceAll("^-|-$", "");                 // pas de tiret au début ni à la fin
    }

    /**
     * Empreinte SHA-1 d'un texte, en hexadécimal (40 caractères). Le même texte donne toujours la même
     * empreinte : l'ID reste donc stable d'une exécution à l'autre. Utilisée uniquement pour raccourcir
     * les clés trop longues ; ce n'est pas un usage de sécurité.
     */
    private static String sha1Hex(String s) {
        try {
            // Calculer l'empreinte (20 octets) du texte encodé en UTF-8.
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            // Écrire chaque octet en 2 chiffres hexadécimaux → 40 caractères utilisables dans un ID.
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 est fourni par toutes les JVM : ce cas ne peut pas arriver en pratique.
            throw new IllegalStateException(e);
        }
    }
}
