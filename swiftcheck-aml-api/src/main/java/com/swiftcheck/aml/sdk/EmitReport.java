package com.swiftcheck.aml.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bilan qualité d'une exécution de parser : entités ajoutées par schéma, doublons fusionnés,
 * valeurs rejetées par propriété (avec échantillon pour diagnostic).
 */
public final class EmitReport {

    private static final int MAX_SAMPLES = 25;

    private final Map<String, Integer> entitiesBySchema = new TreeMap<>();
    private final Map<String, Integer> rejectedByProperty = new TreeMap<>();
    private final List<String> rejectedSamples = new ArrayList<>();
    private int mergedDuplicates;
    private int conflicts;
    private int emptyEntities;

    synchronized void countEntity(String schema) {
        entitiesBySchema.merge(schema, 1, Integer::sum);
    }

    synchronized void countRejectedValue(String schema, String property, String value) {
        rejectedByProperty.merge(schema + "." + property, 1, Integer::sum);
        if (rejectedSamples.size() < MAX_SAMPLES) {
            rejectedSamples.add(schema + "." + property + " = \"" + value + "\"");
        }
    }

    synchronized void countMergedDuplicate() {
        mergedDuplicates++;
    }

    synchronized void countIdConflict(String detail) {
        conflicts++;
        if (rejectedSamples.size() < MAX_SAMPLES) {
            rejectedSamples.add("CONFLICT " + detail);
        }
    }

    synchronized void countEmptyEntity() {
        emptyEntities++;
    }

    public synchronized int getTotalEntities() {
        return entitiesBySchema.values().stream().mapToInt(Integer::intValue).sum();
    }

    public synchronized int getEntities(String schema) {
        return entitiesBySchema.getOrDefault(schema, 0);
    }

    public synchronized Map<String, Integer> getEntitiesBySchema() {
        return Collections.unmodifiableMap(new TreeMap<>(entitiesBySchema));
    }

    public synchronized Map<String, Integer> getRejectedByProperty() {
        return Collections.unmodifiableMap(new TreeMap<>(rejectedByProperty));
    }

    public synchronized int getTotalRejected() {
        return rejectedByProperty.values().stream().mapToInt(Integer::intValue).sum();
    }

    public synchronized List<String> getRejectedSamples() {
        return Collections.unmodifiableList(new ArrayList<>(rejectedSamples));
    }

    public synchronized int getMergedDuplicateCount() {
        return mergedDuplicates;
    }

    public synchronized int getIdConflictCount() {
        return conflicts;
    }

    public synchronized int getEmptyEntityCount() {
        return emptyEntities;
    }

    @Override
    public synchronized String toString() {
        return "EmitReport{entities=" + entitiesBySchema
                + ", mergedDuplicates=" + mergedDuplicates
                + ", conflicts=" + conflicts
                + ", emptyEntities=" + emptyEntities
                + ", rejected=" + rejectedByProperty + "}";
    }
}
