package com.swiftcheck.aml.ingestion;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contenu d'un fichier NDJSON FollowTheMoney (une entité JSON par ligne), lu et vérifié ligne par ligne.
 *
 * <p>Sert à deux choses :
 * <ul>
 *   <li><b>contrôler</b> le fichier produit par un plugin avant de le publier : JSON valide, champs
 *       {@code id} et {@code schema} présents, pas deux fois le même identifiant ({@link #errors()}) ;</li>
 *   <li><b>comparer</b> deux versions d'une liste ({@link DatasetDiffService}).</li>
 * </ul>
 * Chaque entité est gardée sous forme de texte (sa ligne JSON) pour limiter la mémoire utilisée.
 *
 * <p>Objet de données : il est créé pour une exécution puis abandonné, il ne change plus après la lecture.
 */
public final class DatasetFile {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERRORS = 20;

    /** Une entité du fichier : son schéma, son nom lisible et sa ligne JSON complète. */
    public record Entity(String schema, String caption, String json) {
    }

    private final Map<String, Entity> entities;
    private final List<String> errors;

    private DatasetFile(Map<String, Entity> entities, List<String> errors) {
        this.entities = Collections.unmodifiableMap(entities);
        this.errors = Collections.unmodifiableList(errors);
    }

    /** Fichier vide : utilisé quand aucune version n'a encore été publiée. */
    public static DatasetFile empty() {
        return new DatasetFile(new LinkedHashMap<>(), new ArrayList<>());
    }

    /** Lit le fichier s'il existe, sinon renvoie un fichier vide. */
    public static DatasetFile readIfExists(Path file) throws IOException {
        return Files.exists(file) ? read(file) : empty();
    }

    /**
     * Lit et vérifie un fichier NDJSON. Une ligne invalide n'arrête pas la lecture : elle est notée dans
     * {@link #errors()} (au plus 20 messages) et la lecture continue, pour donner un bilan complet.
     */
    public static DatasetFile read(Path file) throws IOException {
        Map<String, Entity> entities = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int lineNumber = 0;
        int errorCount = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String problem = null;
                try {
                    JsonNode node = JSON.readTree(line);
                    String id = node.path("id").asText("");
                    String schema = node.path("schema").asText("");
                    if (id.isEmpty() || schema.isEmpty()) {
                        problem = "champ id ou schema manquant";
                    } else if (entities.containsKey(id)) {
                        problem = "identifiant en double : " + id;
                    } else {
                        entities.put(id, new Entity(schema, captionOf(node), line));
                    }
                } catch (IOException e) {
                    problem = "JSON invalide";
                }
                if (problem != null) {
                    errorCount++;
                    if (errors.size() < MAX_ERRORS) {
                        errors.add("ligne " + lineNumber + " : " + problem);
                    }
                }
            }
        }
        if (errorCount > errors.size()) {
            errors.add("… et " + (errorCount - errors.size()) + " autre(s) erreur(s)");
        }
        return new DatasetFile(entities, errors);
    }

    /** Entités par identifiant, dans l'ordre du fichier. */
    public Map<String, Entity> entities() {
        return entities;
    }

    public int size() {
        return entities.size();
    }

    /** Problèmes de format trouvés à la lecture ; liste vide si le fichier est correct. */
    public List<String> errors() {
        return errors;
    }

    /** Propriétés d'une entité, lues depuis sa ligne JSON : nom de propriété → valeurs. */
    public static JsonNode propertiesOf(Entity entity) {
        try {
            return JSON.readTree(entity.json()).path("properties");
        } catch (IOException e) {
            throw new IllegalStateException(e);       // impossible : la ligne a été validée à la lecture
        }
    }

    /** Premier nom de l'entité, ou le nom du navire / de l'avion ; vide si elle n'en a pas (ex. une Sanction). */
    private static String captionOf(JsonNode node) {
        JsonNode names = node.path("properties").path("name");
        return names.isArray() && !names.isEmpty() ? names.get(0).asText() : "";
    }
}
