package com.swiftcheck.aml.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Entité FollowTheMoney en cours de construction. Créée uniquement via
 * {@link FtmDatasetBuilder#newEntity}.
 * Chaque valeur ajoutée est nettoyée et normalisée selon le type de la
 * propriété (pays, date) ;
 * les valeurs invalides sont rejetées et comptabilisées dans le
 * {@link EmitReport}.
 */
public final class FtmEntity {

    private final String id;
    private final String schema;
    private final EmitReport report;
    private final Map<String, LinkedHashSet<String>> properties = new LinkedHashMap<>();

    FtmEntity(String id, String schema, EmitReport report) {
        this.id = id;
        this.schema = schema;
        this.report = report;
    }

    public String getId() {
        return id;
    }

    public String getSchema() {
        return schema;
    }

    /**
     * Ajoute une valeur après nettoyage et normalisation, selon le type de la
     * propriété dans le modèle FtM :
     * <ul>
     * <li>{@code country} : converti en code ISO ("Russian Federation" → "ru")
     * ;</li>
     * <li>{@code date} : converti au format ISO ("15/03/1965" → "1965-03-15")
     * ;</li>
     * <li>{@code entity} : identifiant d'une autre entité, conservé tel quel ;</li>
     * <li>autres types : espaces superflus supprimés.</li>
     * </ul>
     * Les valeurs vides sont ignorées sans bruit. Les valeurs invalides et les
     * propriétés qui n'existent pas
     * pour ce schéma sont refusées et comptées dans le {@link EmitReport}.
     *
     * @return l'entité elle-même, pour enchaîner les appels
     */
    public FtmEntity addValue(String property, String value) {
        if (value == null) {
            return this;
        }
        String type = FtmSchemaModel.propertyType(schema, property);
        if (type == null) {
            report.countRejectedValue(schema, property, "(propriété inconnue pour ce schéma) " + value);
            return this;
        }
        String clean = "entity".equals(type) ? value.trim() : value.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            return this;
        }
        if ("country".equals(type)) {
            if (CountryNormalizer.isUnknownValue(clean)) {
                return this;
            }
            String iso = CountryNormalizer.toIsoCode(clean);
            if (iso == null) {
                report.countRejectedValue(schema, property, clean);
                return this;
            }
            clean = iso;
        } else if ("date".equals(type)) {
            String iso = DateNormalizer.toIsoDate(clean);
            if (iso == null) {
                report.countRejectedValue(schema, property, clean);
                return this;
            }
            clean = iso;
        }
        properties.computeIfAbsent(property, k -> new LinkedHashSet<>()).add(clean);
        return this;
    }

    /** Ajoute plusieurs valeurs pour une même propriété. */
    public FtmEntity addAll(String property, Iterable<String> values) {
        if (values != null) {
            for (String v : values) {
                addValue(property, v);
            }
        }
        return this;
    }

    public boolean has(String property) {
        LinkedHashSet<String> v = properties.get(property);
        return v != null && !v.isEmpty();
    }

    public Set<String> getValues(String property) {
        LinkedHashSet<String> v = properties.get(property);
        return v == null ? Collections.emptySet() : Collections.unmodifiableSet(v);
    }

    public boolean isEmpty() {
        return properties.isEmpty();
    }

    Map<String, LinkedHashSet<String>> getProperties() {
        return properties;
    }

    void mergeValuesFrom(FtmEntity other) {
        other.properties.forEach((k, v) -> properties.computeIfAbsent(k, x -> new LinkedHashSet<>()).addAll(v));
    }
}
