package com.swiftcheck.aml.ingestion;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.swiftcheck.aml.entities.ChangeType;
import com.swiftcheck.aml.entities.DatasetChange;
import com.swiftcheck.aml.repositories.DatasetChangeRepository;

/**
 * Compare la nouvelle version d'une liste avec celle publiée actuellement, entité par entité,
 * et enregistre chaque changement ({@link DatasetChange}) : ajout, retrait ou modification.
 *
 * <p>La comparaison se fait sur les entités FtM et non sur le fichier source : dans un XML, une balise
 * déplacée ou un espace en plus ferait croire à un changement ; ici, seul un vrai changement de données compte
 * (l'ordre des valeurs d'une propriété est ignoré).
 *
 * <p>Lors de la toute première publication, il n'y a rien à comparer : seuls les totaux sont calculés,
 * sans enregistrer des milliers de lignes « ajoutée ».
 *
 * <p>Service sans état.
 */
@Service
public class DatasetDiffService {

    /** Totaux d'une comparaison. */
    public record Summary(int added, int removed, int changed) {
    }

    private final DatasetChangeRepository changes;

    public DatasetDiffService(DatasetChangeRepository changes) {
        this.changes = changes;
    }

    /**
     * @param previous    version actuellement publiée (vide si aucune)
     * @param next        nouvelle version
     * @param executionId exécution à laquelle rattacher les changements
     */
    public Summary compareAndRecord(DatasetFile previous, DatasetFile next, long executionId) {
        boolean firstPublication = previous.size() == 0;
        List<DatasetChange> found = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int changed = 0;

        for (Map.Entry<String, DatasetFile.Entity> entry : next.entities().entrySet()) {
            DatasetFile.Entity before = previous.entities().get(entry.getKey());
            if (before == null) {
                added++;
                if (!firstPublication) {
                    found.add(change(executionId, entry.getKey(), entry.getValue(), ChangeType.ADDED, null));
                }
            } else if (!before.json().equals(entry.getValue().json())) {
                Set<String> properties = changedProperties(before, entry.getValue());
                if (!properties.isEmpty()) {             // texte différent mais mêmes valeurs : pas un changement
                    changed++;
                    found.add(change(executionId, entry.getKey(), entry.getValue(), ChangeType.MODIFIED,
                            String.join(", ", properties)));
                }
            }
        }
        for (Map.Entry<String, DatasetFile.Entity> entry : previous.entities().entrySet()) {
            if (!next.entities().containsKey(entry.getKey())) {
                removed++;
                found.add(change(executionId, entry.getKey(), entry.getValue(), ChangeType.REMOVED, null));
            }
        }

        changes.saveAll(found);
        return new Summary(added, removed, changed);
    }

    /** Noms des propriétés dont l'ensemble des valeurs diffère entre les deux versions, triés. */
    static Set<String> changedProperties(DatasetFile.Entity before, DatasetFile.Entity after) {
        JsonNode oldProps = DatasetFile.propertiesOf(before);
        JsonNode newProps = DatasetFile.propertiesOf(after);
        Set<String> names = new TreeSet<>();
        oldProps.fieldNames().forEachRemaining(names::add);
        newProps.fieldNames().forEachRemaining(names::add);
        names.removeIf(name -> valuesOf(oldProps.path(name)).equals(valuesOf(newProps.path(name))));
        return names;
    }

    private static Set<String> valuesOf(JsonNode array) {
        Set<String> values = new TreeSet<>();
        for (Iterator<JsonNode> it = array.elements(); it.hasNext(); ) {
            values.add(it.next().asText());
        }
        return values;
    }

    private static DatasetChange change(long executionId, String entityId, DatasetFile.Entity entity,
            ChangeType type, String properties) {
        DatasetChange change = new DatasetChange();
        change.setExecutionId(executionId);
        change.setEntityId(entityId);
        change.setEntitySchema(entity.schema());
        change.setCaption(entity.caption());
        change.setChangeType(type);
        change.setChangedProperties(properties);
        return change;
    }
}
