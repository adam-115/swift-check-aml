package com.swiftcheck.aml.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.DataSourceType;
import com.swiftcheck.aml.entities.ExecutionTriggerEnum;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.SourceFile;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.service.AlertService;
import com.swiftcheck.aml.service.ApplicationConfigService;
import com.swiftcheck.aml.service.PluginManagementService;

/**
 * Chef d'orchestre d'une exécution de parser. C'est la classe à lire en premier
 * pour comprendre l'ingestion.
 *
 * <pre>
 * run(parserId)
 *  1. Récupérer les données     SourceFileService   (HTTP_FILE / LOCAL_FILE : télécharger, signer, archiver)
 *     └─ même empreinte que la dernière publication ?  → UNCHANGED, fin
 *  2. Convertir                 plugin PF4J          (Iparser.parse → fichier NDJSON FtM)
 *     └─ PLUGIN_MANAGED : signer et archiver les réponses brutes déposées par le plugin
 *  3. Contrôler                 QualityGate          → problème ?  BLOCKED, fin (l'ancienne liste reste publiée)
 *  4. Garder les disparus       RetentionService     (entités retirées de la source, publiées encore N mois)
 *  5. Comparer                  DatasetDiffService   (ajouts / retraits / modifications, enregistrés en base)
 *  6. Publier                   DatasetPublisher     (dossier Yente, manifest, rechargement)
 *  7. Tracer                    ParserExecution      → SUCCESS
 *  En cas d'exception à n'importe quelle étape : FAILED, rien n'est publié.
 *  À la fin, AlertService ouvre une alerte (FAILED, BLOCKED) ou résout celles du parser (SUCCESS, UNCHANGED).
 * </pre>
 *
 * <p>
 * Tout ce qui concerne une exécution est dans les variables locales et dans la
 * ligne {@link ParserExecution}. Chaque exécution a son propre dossier de
 * travail, supprimé à la fin. Seul état partagé : les exécutions en cours, pour
 * pouvoir les arrêter ({@link #stop}).
 */
@Service
public class IngestionService {

    // Journal de l'application : chaque exécution y laisse une ligne (succès ou
    // erreur détaillée).
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    // Les dépendances sont injectées par Spring et ne changent jamais ensuite
    // (final) :
    // le service ne garde aucune donnée d'une exécution à l'autre.
    private final ParserRepository parsersRepository; // lire la configuration du parser
    private final ParserExecutionRepository executionsRepository; // écrire et relire l'historique des exécutions
    private final ApplicationConfigService configService; // dossiers de travail, d'archive et de Yente
    private final PluginManagementService pluginsManagementService; // trouver le plugin PF4J chargé
    private final SourceFileService sourceFilesService; // étape 1 : récupérer, signer, archiver
    private final QualityGate qualityGate; // étape 3 : contrôles qualité
    private final RetentionService retentionService; // étape 4 : entités disparues gardées N mois
    private final DatasetDiffService diffService; // étape 5 : comparaison avec la version publiée
    private final DatasetPublisher publisher; // étape 6 : publication dans Yente
    private final AlertService alertService; // alertes ouvertes ou résolues à la fin

    /** Message d'une exécution arrêtée depuis l'écran Parsers. */
    static final String STOPPED_MESSAGE = "Exécution arrêtée par l'utilisateur : rien n'a été publié.";

    /** Délai laissé au thread de l'exécution pour s'arrêter avant de libérer le parser de force. */
    private static final long STOP_WAIT_MILLIS = 10_000;

    /**
     * Exécution en cours dans ce serveur : son thread, pour l'interrompre, et la demande d'arrêt.
     * {@code active} passe à faux à la fin de l'exécution, sous le verrou de l'objet : un arrêt demandé
     * à ce moment n'interrompt donc jamais le thread une fois rendu au pool de Tomcat.
     */
    private static final class ActiveRun {
        final long executionId;
        final Thread thread = Thread.currentThread();
        volatile boolean stopRequested;
        boolean active = true;

        ActiveRun(long executionId) {
            this.executionId = executionId;
        }
    }

    /** Exécutions en cours, par identifiant de parser. */
    private final ConcurrentHashMap<Long, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    // Injection par constructeur : Spring fournit chaque service au démarrage de
    // l'application.
    public IngestionService(ParserRepository parsers, ParserExecutionRepository executions,
            ApplicationConfigService configService, PluginManagementService plugins,
            SourceFileService sourceFiles, QualityGate qualityGate,
            DatasetDiffService diffService, DatasetPublisher publisher, RetentionService retentionService,
            AlertService alertService) {
        this.parsersRepository = parsers;
        this.executionsRepository = executions;
        this.configService = configService;
        this.pluginsManagementService = plugins;
        this.sourceFilesService = sourceFiles;
        this.qualityGate = qualityGate;
        this.diffService = diffService;
        this.publisher = publisher;
        this.retentionService = retentionService;
        this.alertService = alertService;
    }

    /**
     * Exécute un parser de bout en bout. Ne lève pas d'exception pour une erreur
     * d'exécution :
     * le résultat (SUCCESS, UNCHANGED, BLOCKED ou FAILED) et son explication sont
     * dans l'objet renvoyé.
     *
     * @param force si {@code true}, convertit et publie même si les données sources
     *              n'ont pas changé.
     *              Utile après le déploiement d'une nouvelle version du plugin :
     *              sans cela, l'exécution
     *              s'arrêterait en UNCHANGED et la correction ne serait jamais
     *              appliquée.
     * @throws IllegalArgumentException si le parser n'existe pas
     * @throws IllegalStateException    si ce parser est déjà en cours d'exécution
     */
    public ParserExecution run(long parserId, ExecutionTriggerEnum executionTriggerEnum, boolean force) {
        // Charger la configuration du parser (plugin, type de source, URL...).
        // Identifiant inconnu → IllegalArgumentException, transformée en HTTP 400 par
        // ApiExceptionHandler.
        Parser parser = parsersRepository.findById(parserId)
                .orElseThrow(() -> new IllegalArgumentException("Parser introuvable : " + parserId));

        // Refuser une deuxième exécution simultanée du même parser : deux exécutions
        // écriraient
        // le même fichier publié en même temps. → IllegalStateException, HTTP 409.
        if (executionsRepository.existsByParserIdAndStatus(parserId, ParserExecutionStatus.RUNNING)) {
            throw new IllegalStateException("Le parser " + parser.getDatasetName() + " est déjà en cours d'exécution.");
        }

        // Créer la ligne d'historique de cette exécution, au statut RUNNING.
        ParserExecution execution = new ParserExecution();
        execution.setParser(parser); // quel parser
        execution.setTriggerType(executionTriggerEnum); // lancé par un utilisateur (MANUAL) ou le planificateur
        execution.setStatus(ParserExecutionStatus.RUNNING); // « en cours » : bloque une exécution en parallèle
        execution.setStartedAt(Instant.now()); // heure de début, affichée dans l'historique

        // Enregistrer tout de suite : l'exécution apparaît dans l'historique pendant
        // qu'elle tourne,
        // et la base lui attribue un identifiant, utilisé pour le dossier de travail et
        // les traces.
        execution = executionsRepository.save(execution); // visible tout de suite dans l'historique, avec son id

        // Enregistrer le thread de l'exécution : le bouton « Arrêter » pourra l'interrompre.
        ActiveRun activeRun = new ActiveRun(execution.getId());
        activeRuns.put(parserId, activeRun);

        // Relire la configuration à chaque exécution : une modification faite depuis
        // l'écran
        // « Configuration » est prise en compte sans redémarrer l'application.
        ApplicationConfigEntity config = configService.current();

        // Dossier de travail propre à cette exécution (ex. ./data/work/exec-152) :
        // fichier téléchargé,
        // réponses brutes, fichier produit par le plugin. Deux exécutions ne se
        // marchent jamais dessus.
        Path workDir = Path.of(config.getWorkFolder(), "exec-" + execution.getId());
        try {
            // Créer le dossier de travail (et ses parents s'ils n'existent pas encore).
            Files.createDirectories(workDir);

            // Dérouler les étapes 1 à 6. Le statut final (SUCCESS, UNCHANGED, BLOCKED) est
            // écrit
            // dans « execution » par la méthode elle-même.
            execute(parser, execution, workDir, config, force, activeRun);
        } catch (LinkageError e) {
            // Plugin compilé pour une ancienne version de l'API (NoSuchMethodError, NoClassDefFoundError…) :
            // c'est une Error, pas une Exception. Sans ce bloc, l'exécution resterait RUNNING pour toujours.
            log.error("Exécution {} du parser {} : plugin incompatible", execution.getId(), parser.getDatasetName(), e);
            finish(execution, ParserExecutionStatus.FAILED, "Plugin « " + parser.getPluginId()
                    + " » incompatible avec cette version du core (compilé pour une ancienne API) : recompilez-le, "
                    + "déposez le JAR et rechargez-le. Détail : " + e.getClass().getSimpleName() + " : " + e.getMessage());
        } catch (Exception | Error e) {
            if (activeRun.stopRequested) {
                // Arrêt demandé : l'exception (interruption, CancellationException…) n'est pas une erreur.
                log.warn("Exécution {} du parser {} arrêtée par l'utilisateur", execution.getId(),
                        parser.getDatasetName());
                finish(execution, ParserExecutionStatus.FAILED, STOPPED_MESSAGE);
            } else {
            // Toute erreur imprévue (réseau, fichier introuvable, plugin en erreur, mémoire...)
            // termine l'exécution
            // en FAILED. Rien n'a été publié : Yente continue d'utiliser la version
            // précédente.
            log.error("Exécution {} du parser {} en échec", execution.getId(), parser.getDatasetName(), e);

            // Message lisible gardé dans l'historique, ex. « IOException : Fichier source
            // introuvable... ».
            finish(execution, ParserExecutionStatus.FAILED, e.getClass().getSimpleName() + " : " + e.getMessage());
            }
        } finally {
            // Exécuté dans tous les cas, succès ou erreur :
            // 0) ne plus accepter d'arrêt, et effacer une interruption reçue : le thread
            // de la requête retourne au pool de Tomcat et la sauvegarde ci-dessous ne doit
            // pas être interrompue ;
            synchronized (activeRun) {
                activeRun.active = false;
                Thread.interrupted();
            }
            activeRuns.remove(parserId, activeRun);
            // 1) supprimer les fichiers temporaires (les fichiers sources restent dans
            // l'archive) ;
            deleteQuietly(workDir);
            // 2) enregistrer le statut final, les compteurs et le message.
            executionsRepository.save(execution);
        }

        // Ouvrir une alerte en cas d'échec ou de blocage, résoudre celles du parser
        // après une publication.
        // Une erreur ici ne doit pas changer le résultat de l'exécution, déjà
        // enregistré.
        // Pas d'alerte pour un arrêt voulu par l'utilisateur.
        try {
            if (!STOPPED_MESSAGE.equals(execution.getMessage())) {
                alertService.afterExecution(execution);
            }
        } catch (RuntimeException e) {
            log.error("Alerte non enregistrée pour l'exécution {}", execution.getId(), e);
        }

        // Une ligne de bilan dans le journal, puis l'exécution est renvoyée à
        // l'appelant
        // (le contrôleur la renvoie en JSON à l'écran).
        log.info("Parser {} : {} – {}", parser.getDatasetName(), execution.getStatus(), execution.getMessage());
        return execution;
    }

    /**
     * Les étapes 1 à 7. Chaque {@code return} anticipé correspond à un arrêt normal
     * (UNCHANGED ou BLOCKED).
     */
    private void execute(Parser parser, ParserExecution execution, Path workDir, ApplicationConfigEntity config,
            boolean force, ActiveRun activeRun) throws Exception {
        // Trouver le plugin PF4J dont l'identifiant (Plugin-Id du JAR) est configuré
        // dans le parser.
        // S'il n'est pas chargé, il est chargé depuis le chemin du JAR indiqué dans le
        // parser (pluginUrl).
        // Sans plugin, inutile d'aller plus loin : l'exécution échoue avec un message
        // qui dit quoi faire.
        Iparser plugin = pluginsManagementService.findParser(parser)
                .orElseThrow(() -> new IllegalStateException("Plugin « " + parser.getPluginId()
                        + " » non chargé : indiquez le chemin de son JAR dans le parser et cliquez sur « Vérifier »."));

        // Dernière publication réussie de ce parser (vide s'il n'a jamais été publié).
        // Elle sert à :
        // - savoir si la source a changé (comparaison des empreintes SHA-256) ;
        // - contrôler une chute anormale du nombre d'entités (étape 3).
        Optional<ParserExecution> lastSuccess = executionsRepository
                .findFirstByParserIdAndStatusOrderByStartedAtDesc(parser.getId(), ParserExecutionStatus.SUCCESS);

        // Vrai si le plugin récupère lui-même ses données (SPARQL, API, SQL...) : dans
        // ce cas le core
        // ne télécharge rien à l'étape 1 et archive les réponses brutes après l'étape
        // 2.
        boolean pluginManaged = parser.getDataSourceType() == DataSourceType.PLUGIN_MANAGED;

        // 1. Récupérer les données (sauf si le plugin s'en charge lui-même)
        // Fichier à donner au plugin ; reste null pour une source PLUGIN_MANAGED.
        Path inputFile = null;
        if (!pluginManaged) {
            // Télécharger (HTTP_FILE) ou copier (LOCAL_FILE) le fichier, calculer son
            // SHA-256,
            // le ranger dans l'archive et enregistrer une ligne source_file (preuve des
            // données utilisées).
            SourceFile source = sourceFilesService.fetch(parser, execution.getId(), workDir, config);

            // Le plugin lira la copie archivée : c'est exactement le fichier dont
            // l'empreinte est enregistrée.
            inputFile = Path.of(source.getArchivePath());

            // Garder l'empreinte dans l'exécution, pour la comparer lors des prochaines
            // exécutions.
            execution.setInputSha256(source.getSha256());

            // Même fichier que lors de la dernière publication, et pas d'option « forcer »
            // :
            // inutile de convertir et republier la même liste → UNCHANGED, fin de
            // l'exécution.
            if (!force && sameAsLastPublication(parser, execution, lastSuccess)) {
                finishUnchanged(execution, lastSuccess.get());
                return;
            }
        }

        // 2. Convertir avec le plugin
        // Dossier vide où un plugin PLUGIN_MANAGED doit déposer chaque réponse brute
        // reçue.
        Path rawDir = Files.createDirectories(workDir.resolve("raw"));

        // Fichier où le plugin doit écrire les entités FtM (une ligne JSON par entité).
        // C'est un fichier de travail : il ne sera publié qu'après les contrôles
        // qualité.
        Path outputFile = workDir.resolve(parser.getDatasetName() + ".ndjson");

        // Tout ce dont le plugin a besoin pour cette exécution, et rien d'autre : il
        // n'a accès
        // ni à la base, ni au dossier de Yente. Les paramètres JSON du parser sont
        // convertis en table nom → valeur.
        ParserContext context = new ParserContext(parser.getDatasetName(), inputFile, rawDir, outputFile,
                ParserParameters.parse(parser.getParameters()));

        // Appel du plugin : il lit la source et écrit outputFile. Une exception du
        // plugin remonte
        // jusqu'à run(), qui termine l'exécution en FAILED.
        checkNotStopped(activeRun);
        ParserResult result = plugin.parse(context);
        // Un plugin peut ignorer l'interruption (calcul, lecture de fichier) : on vérifie en sortant.
        checkNotStopped(activeRun);

        // Un plugin qui ne renvoie pas de bilan ou n'écrit pas de fichier est
        // défectueux : on s'arrête (FAILED).
        if (result == null || !Files.exists(outputFile)) {
            throw new IllegalStateException("Le plugin n'a pas produit de fichier ou n'a pas renvoyé de bilan.");
        }

        // Source gérée par le plugin : on signe et on archive les réponses brutes qu'il
        // a déposées
        if (pluginManaged) {
            // Signer (SHA-256) et archiver chaque fichier de rawDir, avec une ligne
            // source_file par fichier.
            List<SourceFile> rawFiles = sourceFilesService.archiveRawFiles(parser, execution.getId(), rawDir, config);

            // Aucune réponse brute : on ne pourrait pas prouver quelles données ont été
            // utilisées
            // → BLOCKED, rien n'est publié.
            if (rawFiles.isEmpty()) {
                finish(execution, ParserExecutionStatus.BLOCKED, "Aucune donnée brute enregistrée par le plugin "
                        + "dans rawDir : impossible de prouver les données utilisées.");
                return;
            }

            // Une seule empreinte pour l'ensemble des réponses (empreinte des empreintes),
            // comparée ensuite à celle de la dernière publication.
            execution.setInputSha256(SourceFileService.combinedSha256(rawFiles));

            // Réponses identiques à la dernière publication → UNCHANGED (sauf option «
            // forcer »).
            if (!force && sameAsLastPublication(parser, execution, lastSuccess)) {
                finishUnchanged(execution, lastSuccess.get());
                return;
            }
        }

        // 3. Contrôler le fichier produit
        // Relire le fichier écrit par le plugin, ligne par ligne : vérifie que chaque
        // ligne est un JSON
        // valide avec un id et un schema, sans identifiant en double. Le core recompte
        // lui-même les entités.
        DatasetFile produced = DatasetFile.read(outputFile);

        // Garder les compteurs dans l'historique, même si la liste est bloquée plus
        // bas.
        execution.setEntityCount(produced.size());
        execution.setPersonCount(countPersons(produced));
        execution.setRejectedCount(result.rejectedCount());

        // Nombre d'entités de la dernière publication (0 si jamais publiée), pour
        // détecter une chute anormale.
        int previousCount = lastSuccess.map(ParserExecution::getEntityCount).orElse(0);

        // Contrôles qualité : fichier vide, baisse de plus de 20 %, plus de 1 % de
        // valeurs rejetées,
        // conflits d'identifiants, lignes invalides. Renvoie la liste des problèmes
        // trouvés.
        List<String> problems = qualityGate.check(produced, result, previousCount);

        // Au moins un problème → BLOCKED : la nouvelle version n'est pas publiée et
        // Yente garde la précédente.
        // Les problèmes sont réunis dans le message, séparés par « | ».
        if (!problems.isEmpty()) {
            finish(execution, ParserExecutionStatus.BLOCKED, String.join(" | ", problems));
            return;
        }

        // Lire le fichier actuellement utilisé par Yente (vide s'il n'a jamais été
        // publié).
        DatasetFile published = DatasetFile.readIfExists(publisher.publishedFile(parser, config));

        // 4. Garder les entités disparues de la source (rétention du parser ; 0 =
        // aucune)
        // Elles sont ajoutées à la fin de outputFile, relu ensuite pour être comparé et
        // publié en entier.
        RetentionService.Result retention = retentionService.apply(parser, published, produced, outputFile,
                Instant.now());
        execution.setRetainedCount(retention.retainedCount());
        DatasetFile toPublish = retention.retainedCount() > 0 ? DatasetFile.read(outputFile) : produced;

        // 5. Comparer avec la version publiée actuellement
        // Entité par entité, chaque ajout, retrait ou modification est enregistré
        // (table dataset_change).
        // Une entité gardée par la rétention reste publiée : ce n'est pas un retrait.
        DatasetDiffService.Summary diff = diffService.compareAndRecord(published, toPublish, execution.getId());

        // Garder les totaux dans l'historique, ex. +3 / -1 / ~2.
        execution.setAddedCount(diff.added());
        execution.setRemovedCount(diff.removed());
        execution.setChangedCount(diff.changed());

        // 6. Publier dans Yente
        // Remplacer le fichier publié (en une seule opération), mettre à jour le
        // manifest et demander
        // à Yente de recharger ses données. À partir d'ici, le criblage utilise la
        // nouvelle version.
        // Dernier point d'arrêt : après un arrêt, rien n'est jamais publié.
        checkNotStopped(activeRun);
        publisher.publish(parser, outputFile, config);

        // 7. Tracer
        // Statut SUCCESS et message de bilan, ex. « 12468 entités publiées (+3 / -1 /
        // ~2), 0 valeurs rejetées. »
        // suivi du message libre du plugin (entités par schéma...). trim() retire
        // l'espace final s'il n'y en a pas.
        // Les entités gardées par la rétention sont signalées à part, ex. « 2 gardées,
        // 0 oubliées (rétention 12 mois). »
        String retentionNote = retention.retainedCount() == 0 && retention.expiredCount() == 0 ? ""
                : String.format(" Disparues de la source : %d gardées, %d oubliées (rétention %d mois).",
                        retention.retainedCount(), retention.expiredCount(), parser.getRetentionMonths());
        finish(execution, ParserExecutionStatus.SUCCESS, String.format(
                "%d entités publiées (+%d / -%d / ~%d), %d valeurs rejetées.%s %s",
                toPublish.size(), diff.added(), diff.removed(), diff.changed(), result.rejectedCount(),
                retentionNote, result.message() == null ? "" : result.message()).trim());
    }

    /**
     * Vrai si les données sources ont la même empreinte que lors de la dernière
     * publication réussie,
     * et qu'aucune entité gardée par la rétention n'a atteint sa date de fin (sinon
     * il faut republier
     * pour la retirer, même si la source n'a pas changé).
     */
    private boolean sameAsLastPublication(Parser parser, ParserExecution execution,
            Optional<ParserExecution> lastSuccess) {
        // Faux s'il n'y a jamais eu de publication ; sinon, comparaison des deux
        // empreintes SHA-256.
        return lastSuccess.isPresent() && execution.getInputSha256().equals(lastSuccess.get().getInputSha256())
                && !retentionService.hasExpired(parser, Instant.now());
    }

    /**
     * Nombre de personnes (schéma Person) dans le fichier produit par le plugin.
     */
    private static int countPersons(DatasetFile file) {
        return (int) file.entities().values().stream().filter(e -> "Person".equals(e.schema())).count();
    }

    /**
     * Termine une exécution dont les données n'ont pas changé depuis la dernière
     * publication.
     */
    private static void finishUnchanged(ParserExecution execution, ParserExecution lastSuccess) {
        // Reprendre le nombre d'entités de la version publiée, toujours valable, pour
        // l'affichage.
        execution.setEntityCount(lastSuccess.getEntityCount());
        execution.setPersonCount(lastSuccess.getPersonCount());
        execution.setRetainedCount(lastSuccess.getRetainedCount());

        // Statut UNCHANGED, avec la date de la publication encore en service.
        finish(execution, ParserExecutionStatus.UNCHANGED,
                "Données identiques à la publication du " + lastSuccess.getStartedAt() + " : rien à faire.");
    }

    /**
     * Arrête de force l'exécution en cours d'un parser, pour pouvoir le relancer :
     * <ol>
     *   <li>si elle tourne dans ce serveur, son thread est interrompu (téléchargements, appels HTTP et attentes
     *       s'arrêtent tout de suite) et elle se termine en FAILED avant toute publication ;</li>
     *   <li>si elle ne s'est pas arrêtée après {@value #STOP_WAIT_MILLIS} ms (plugin occupé dans un calcul), ou si
     *       aucune exécution ne tourne dans ce serveur (ligne RUNNING orpheline), les lignes RUNNING du parser sont
     *       passées en FAILED : le parser peut être relancé. Le thread bloqué, lui, ne publiera rien.</li>
     * </ol>
     *
     * @return message pour l'écran
     * @throws IllegalArgumentException si le parser n'existe pas
     * @throws IllegalStateException    si le parser n'a aucune exécution en cours
     */
    public String stop(long parserId) {
        Parser parser = parsersRepository.findById(parserId)
                .orElseThrow(() -> new IllegalArgumentException("Parser introuvable : " + parserId));
        ActiveRun run = activeRuns.get(parserId);
        if (run != null) {
            synchronized (run) {
                run.stopRequested = true;
                if (run.active) {
                    run.thread.interrupt();
                }
            }
            log.warn("Arrêt demandé pour l'exécution {} du parser {}", run.executionId, parser.getDatasetName());
            long deadline = System.currentTimeMillis() + STOP_WAIT_MILLIS;
            while (activeRuns.get(parserId) == run && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (activeRuns.get(parserId) != run) {
                // L'exécution a pu se terminer d'elle-même juste avant l'arrêt : on dit ce qui s'est passé.
                ParserExecution done = executionsRepository.findById(run.executionId).orElse(null);
                if (done != null && !STOPPED_MESSAGE.equals(done.getMessage())) {
                    return "L'exécution n° " + run.executionId + " s'est terminée avant l'arrêt (" + done.getStatus()
                            + ").";
                }
                return "Exécution n° " + run.executionId + " arrêtée : rien n'a été publié. Le parser peut être relancé.";
            }
        }
        // Thread bloqué, ou exécution qui ne tourne plus dans ce serveur : libérer le parser en base.
        List<ParserExecution> stuck = executionsRepository.findByParserIdAndStatus(parserId, ParserExecutionStatus.RUNNING);
        if (run == null && stuck.isEmpty()) {
            throw new IllegalStateException("Le parser " + parser.getDatasetName() + " n'a aucune exécution en cours.");
        }
        for (ParserExecution execution : stuck) {
            finish(execution, ParserExecutionStatus.FAILED, run != null
                    ? "Exécution arrêtée de force : le plugin ne répondait pas à l'arrêt. Rien n'a été publié."
                    : "Exécution arrêtée de force : elle ne tournait plus dans le serveur. Rien n'a été publié.");
            executionsRepository.save(execution);
            log.warn("Exécution {} du parser {} arrêtée de force", execution.getId(), parser.getDatasetName());
        }
        return run != null
                ? "Le plugin ne répondait pas : exécution marquée arrêtée, le parser peut être relancé. "
                        + "Le traitement bloqué ne publiera rien."
                : "Exécution orpheline marquée arrêtée : le parser peut être relancé.";
    }

    /** Arrêt demandé : on sort par une exception, que run() transforme en FAILED « arrêtée par l'utilisateur ». */
    private static void checkNotStopped(ActiveRun activeRun) {
        if (activeRun.stopRequested) {
            throw new CancellationException(STOPPED_MESSAGE);
        }
    }

    /**
     * Écrit le résultat final d'une exécution : statut, message et heure de fin.
     */
    private static void finish(ParserExecution execution, ParserExecutionStatus status, String message) {
        execution.setStatus(status); // SUCCESS, UNCHANGED, BLOCKED ou FAILED
        execution.setMessage(message); // explication affichée dans l'historique
        execution.setFinishedAt(Instant.now()); // heure de fin (durée = finishedAt - startedAt)
    }

    /**
     * Supprime le dossier de travail de l'exécution ; un échec n'est que
     * journalisé.
     */
    private static void deleteQuietly(Path dir) {
        // Le dossier peut ne pas exister si l'erreur est survenue avant sa création.
        if (!Files.exists(dir)) {
            return;
        }
        // Parcourir le dossier et tout son contenu ; le try (...) ferme automatiquement
        // le parcours.
        try (Stream<Path> files = Files.walk(dir)) {
            // Trier du plus profond au moins profond : un dossier ne peut être supprimé
            // qu'une fois vide,
            // donc les fichiers d'abord, puis les sous-dossiers, puis le dossier lui-même.
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            // Un fichier verrouillé (Windows) ne doit pas faire échouer une exécution déjà
            // terminée :
            // on le signale seulement dans le journal.
            log.warn("Dossier de travail non supprimé : {} ({})", dir, e.getMessage());
        }
    }
}
