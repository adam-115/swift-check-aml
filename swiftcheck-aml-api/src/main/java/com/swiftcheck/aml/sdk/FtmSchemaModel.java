package com.swiftcheck.aml.sdk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Liste des propriétés autorisées pour chaque schéma FollowTheMoney, avec leur type
 * (country, date, entity, name, string…). Chargée depuis le fichier {@code ftm-model.tsv}.
 *
 * <p>Sert à détecter les fautes de frappe : {@code add("nationalty", ...)} est refusé et signalé
 * dans le rapport qualité, au lieu d'être ignoré silencieusement par Yente.
 */
public final class FtmSchemaModel {

    private static final Map<String, String> TYPES = load();

    private FtmSchemaModel() {
    }

    /**
     * @return le type FtM de la propriété (ex. "country", "date"), ou {@code null} si la propriété
     *         n'existe pas pour ce schéma
     */
    public static String propertyType(String schema, String property) {
        return TYPES.get(schema + "." + property);
    }

    private static Map<String, String> load() {
        Map<String, String> types = new HashMap<>();
        try (InputStream in = FtmSchemaModel.class.getResourceAsStream("ftm-model.tsv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                types.put(parts[0] + "." + parts[1], parts[2]);
            }
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("Impossible de charger ftm-model.tsv", e);
        }
        return types;
    }
}
