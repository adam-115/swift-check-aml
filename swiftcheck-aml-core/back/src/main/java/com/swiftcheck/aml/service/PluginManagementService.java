package com.swiftcheck.aml.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.pf4j.PluginRuntimeException;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.pf4j.spring.SpringPluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;

/**
 * Gestion des plugins PF4J (un JAR par liste) : lister, charger, recharger, décharger, trouver.
 *
 * <p>Au démarrage, PF4J charge tous les JAR du dossier {@code pf4j.plugins.dir} (voir {@code Pf4jConfig}).
 *
 * <h2>Remplacer un plugin sans redémarrer (« à chaud »)</h2>
 * <ol>
 *   <li>déposer le nouveau JAR dans le dossier des plugins, sous un nouveau nom (ex. {@code eu-parser-0.0.2.jar}) ;</li>
 *   <li>appeler {@link #load} avec son chemin : l'ancienne version (même Plugin-Id) est déchargée puis
 *       la nouvelle est chargée ;</li>
 *   <li>lancer le parser avec l'option « forcer » pour reconvertir la source avec la nouvelle version.</li>
 * </ol>
 * Sous Windows, un JAR chargé est verrouillé : on ne peut pas l'écraser, d'où le nouveau nom.
 * Le rechargement est refusé pendant qu'une exécution est en cours.
 *
 * <p>Service sans état : l'état des plugins est tenu par PF4J lui-même.
 */
@Service
public class PluginManagementService {

    private static final Logger log = LoggerFactory.getLogger(PluginManagementService.class);

    /** Description d'un plugin chargé, renvoyée par l'API. */
    public record PluginInfo(String pluginId, String version, String state, String path) {
    }

    /** Résultat du bouton « Vérifier » : le plugin trouvé dans le JAR et la classe qui convertit la liste. */
    public record PluginCheck(String pluginId, String version, String parserClass, String path) {
    }

    /**
     * Un JAR de plugin connu du serveur : trouvé dans le dossier des plugins ou indiqué par un parser.
     *
     * @param loaded     un plugin de même Plugin-Id est chargé depuis ce même fichier
     * @param loadedPath fichier d'où le plugin de même Plugin-Id est chargé s'il diffère de celui-ci, sinon {@code null}
     * @param error      raison pour laquelle le JAR n'a pas pu être lu (ce n'est pas un plugin), sinon {@code null}
     */
    public record AvailablePlugin(String pluginId, String version, String path, boolean inFolder, int parsers,
            boolean loaded, String loadedPath, String error) {
    }

    private final SpringPluginManager pluginManager;
    private final ParserExecutionRepository executions;
    private final ParserRepository parserRepository;
    private final String pluginsDir;

    public PluginManagementService(SpringPluginManager pluginManager, ParserExecutionRepository executions) {
        this(pluginManager, executions, null, null);
    }

    @Autowired
    public PluginManagementService(SpringPluginManager pluginManager, ParserExecutionRepository executions,
            ParserRepository parserRepository, @Value("${pf4j.plugins.dir:}") String pluginsDir) {
        this.pluginManager = pluginManager;
        this.executions = executions;
        this.parserRepository = parserRepository;
        this.pluginsDir = pluginsDir;
    }

    /**
     * Les JAR que le serveur connaît : ceux du dossier des plugins et ceux que les parsers indiquent
     * ({@link Parser#getPluginUrl()}), chargés ou non. Sert à l'écran « Plugins » pour montrer ce qui est chargé, ce
     * qui ne l'est pas, et ce qu'un parser attend sans que son JAR soit chargé. Un JAR illisible est renvoyé avec son
     * erreur au lieu de faire échouer la liste.
     */
    public List<AvailablePlugin> listAvailable() {
        java.util.Map<String, Integer> parsersByJar = new java.util.HashMap<>();
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        java.util.Set<String> folderPaths = new java.util.HashSet<>();
        if (pluginsDir != null && !pluginsDir.isBlank() && Files.isDirectory(Path.of(pluginsDir))) {
            try (var stream = Files.list(Path.of(pluginsDir))) {
                stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar")).sorted().forEach(p -> {
                    paths.add(normalize(p));
                    folderPaths.add(normalize(p));
                });
            } catch (IOException e) {
                log.warn("Dossier des plugins illisible : {}", e.getMessage());
            }
        }
        if (parserRepository != null) {
            for (Parser parser : parserRepository.findAll()) {
                if (parser.getPluginUrl() != null && !parser.getPluginUrl().isBlank()) {
                    String key = normalize(Path.of(parser.getPluginUrl().trim()));
                    paths.add(key);
                    parsersByJar.merge(key, 1, Integer::sum);
                }
            }
        }
        List<AvailablePlugin> out = new java.util.ArrayList<>();
        for (String path : paths) {
            Path jar = Path.of(path);
            boolean inFolder = folderPaths.contains(path);
            int parsers = parsersByJar.getOrDefault(path, 0);
            try {
                if (!Files.isRegularFile(jar)) {
                    throw new IllegalArgumentException("Fichier introuvable sur le serveur : " + path);
                }
                String id = readPluginId(jar);
                PluginWrapper wrapper = pluginManager.getPlugin(id);
                boolean sameFile = wrapper != null && normalize(wrapper.getPluginPath()).equals(path);
                out.add(new AvailablePlugin(id, readVersion(jar), path, inFolder, parsers, sameFile,
                        wrapper != null && !sameFile ? wrapper.getPluginPath().toString() : null, null));
            } catch (IllegalArgumentException e) {
                out.add(new AvailablePlugin(null, null, path, inFolder, parsers, false, null, e.getMessage()));
            }
        }
        return out;
    }

    private static String normalize(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String readVersion(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue("Plugin-Version");
        } catch (IOException e) {
            return null;
        }
    }

    public List<PluginInfo> listPlugins() {
        return pluginManager.getPlugins().stream()
                .map(p -> new PluginInfo(p.getPluginId(), p.getDescriptor().getVersion(),
                        p.getPluginState().toString(), p.getPluginPath().toString()))
                .toList();
    }

    /**
     * Le parser fourni par un plugin, démarré si besoin. Vide si aucun plugin chargé ne porte cet identifiant
     * ou s'il ne contient pas de classe {@code @Extension} implémentant {@link Iparser}.
     */
    public Optional<Iparser> findParser(String pluginId) {
        PluginWrapper plugin = pluginManager.getPlugin(pluginId);
        if (plugin == null) {
            return Optional.empty();
        }
        if (plugin.getPluginState() != PluginState.STARTED) {
            pluginManager.startPlugin(pluginId);
        }
        return pluginManager.getExtensions(Iparser.class, pluginId).stream().findFirst();
    }

    /**
     * Le parser à utiliser pour une exécution. Si le plugin n'est pas chargé mais que son JAR est indiqué
     * dans {@link Parser#getPluginUrl()}, il est chargé depuis ce chemin (après un redémarrage du core,
     * par exemple, si le JAR n'est pas dans le dossier des plugins).
     */
    public Optional<Iparser> findParser(Parser parser) {
        Optional<Iparser> loaded = findParser(parser.getPluginId());
        if (loaded.isPresent() || parser.getPluginUrl() == null) {
            return loaded;
        }
        String jarPluginId = readPluginId(parser.getPluginUrl());
        if (!jarPluginId.equals(parser.getPluginId())) {
            throw new IllegalStateException("Le JAR " + parser.getPluginUrl() + " contient le plugin « "
                    + jarPluginId + " » et non « " + parser.getPluginId() + " ».");
        }
        log.info("Plugin {} non chargé : chargement depuis {}", parser.getPluginId(), parser.getPluginUrl());
        load(parser.getPluginUrl());
        return findParser(parser.getPluginId());
    }

    /**
     * Bouton « Vérifier » du formulaire : charge le JAR avec PF4J (il remplace la version déjà chargée
     * de même Plugin-Id) et contrôle qu'il fournit bien un parser ({@code @Extension} implémentant
     * {@link Iparser}). Le plugin reste chargé : le parser peut être lancé tout de suite.
     *
     * @throws IllegalArgumentException si le fichier n'est pas un plugin valide ; le message est affiché tel quel
     */
    public PluginCheck verify(String jarPath) {
        String pluginId;
        try {
            pluginId = load(jarPath);
        } catch (PluginRuntimeException e) {
            // Erreur PF4J (dépendance manquante, classe introuvable…) : message renvoyé au formulaire.
            throw new IllegalArgumentException("PF4J n'arrive pas à charger ce JAR : " + e.getMessage());
        }
        Iparser parser = findParser(pluginId).orElse(null);
        if (parser == null) {
            unloadQuietly(pluginId);
            throw new IllegalArgumentException("Le plugin « " + pluginId + " » se charge, mais ne contient aucune"
                    + " classe @Extension implémentant Iparser (vérifiez extensions.idx dans le JAR).");
        }
        PluginWrapper plugin = pluginManager.getPlugin(pluginId);
        return new PluginCheck(pluginId, plugin.getDescriptor().getVersion(), parser.getClass().getName(),
                plugin.getPluginPath().toString());
    }

    /**
     * Charge et démarre un JAR. Si un plugin de même identifiant est déjà chargé, il est d'abord déchargé
     * (mise à jour à chaud).
     *
     * @return l'identifiant du plugin chargé
     */
    public String load(String jarPath) {
        String newId = readPluginId(jarPath);
        Path jar = Path.of(jarPath.trim());
        if (pluginManager.getPlugin(newId) != null) {
            // Remplacer une version chargée la décharge : impossible pendant qu'une exécution l'utilise.
            refuseWhileRunning();
            log.info("Plugin {} déjà chargé : remplacement par {}", newId, jar);
            unloadQuietly(newId);
        }
        String pluginId = pluginManager.loadPlugin(jar);
        pluginManager.startPlugin(pluginId);
        log.info("Plugin {} chargé depuis {}", pluginId, jar);
        return pluginId;
    }

    /** Recharge un plugin depuis son JAR actuel (après correction du fichier, plugin déchargé au préalable). */
    public String reload(String pluginId) {
        PluginWrapper plugin = pluginManager.getPlugin(pluginId);
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin non chargé : " + pluginId);
        }
        return load(plugin.getPluginPath().toString());   // load() remplace la version chargée
    }

    /** Arrête et décharge un plugin. Ses parsers échoueront (FAILED) tant qu'il n'est pas rechargé. */
    public void unload(String pluginId) {
        refuseWhileRunning();
        if (pluginManager.getPlugin(pluginId) == null) {
            throw new IllegalArgumentException("Plugin non chargé : " + pluginId);
        }
        unloadQuietly(pluginId);
    }

    /**
     * Lit le {@code Plugin-Id} déclaré dans le MANIFEST du JAR, sans le charger.
     *
     * @throws IllegalArgumentException si le fichier n'existe pas, n'est pas un JAR ou n'est pas un plugin
     */
    public static String readPluginId(String jarPath) {
        if (jarPath == null || jarPath.isBlank()) {
            throw new IllegalArgumentException("Chemin du JAR obligatoire.");
        }
        Path jar = Path.of(jarPath.trim());
        if (!jarPath.trim().toLowerCase().endsWith(".jar") || !Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("Fichier JAR introuvable sur le serveur : " + jarPath);
        }
        return readPluginId(jar);
    }

    static String readPluginId(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            String id = manifest == null ? null : manifest.getMainAttributes().getValue("Plugin-Id");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Ce JAR n'est pas un plugin : Plugin-Id absent du MANIFEST (" + jar + ")");
            }
            return id.trim();
        } catch (IOException e) {
            throw new IllegalArgumentException("JAR illisible : " + jar + " (" + e.getMessage() + ")");
        }
    }

    private void unloadQuietly(String pluginId) {
        pluginManager.stopPlugin(pluginId);
        pluginManager.unloadPlugin(pluginId);
    }

    /** Décharger un plugin pendant qu'il convertit une liste ferait échouer l'exécution en cours. */
    private void refuseWhileRunning() {
        if (!executions.findByStatus(ParserExecutionStatus.RUNNING).isEmpty()) {
            throw new IllegalStateException("Une exécution est en cours : réessayez une fois terminée.");
        }
    }
}
