package com.swiftcheck.aml.plugins.sanction.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.CountryNormalizer;
import com.swiftcheck.aml.sdk.DateNormalizer;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import com.swiftcheck.aml.sdk.FtmSchemaModel;

/**
 * Conversion commune {@link SanctionRecord} → entités FtM, identique pour toutes les listes de sanctions.
 *
 * <p>Règles :
 * <ul>
 * <li>sujet : ID = {@code <préfixe>-<clé>}, propriétés recopiées ; le nom complet est déduit des prénoms et
 * nom s'il manque ;</li>
 * <li>sanction : ID = {@code <préfixe>-<clé>-sanction}, reliée au sujet ({@code entity}), autorité par défaut
 * celle de la liste ;</li>
 * <li>un pays ou une date que le SDK ne sait pas normaliser (« Gaza », « vers 1960 »…) est gardé en note
 * plutôt que rejeté : l'information n'est pas perdue et la publication n'est pas bloquée pour une valeur
 * exotique. Une propriété inconnue du schéma reste rejetée (c'est une erreur du parser).</li>
 * </ul>
 */
public final class SanctionDataset {

    private SanctionDataset() {
    }

    /**
     * Convertit les fiches dans un nouveau dataset.
     *
     * @param idPrefix  préfixe d'ID stable de la liste (ne jamais le changer une fois publiée)
     * @param authority autorité qui prononce la sanction (ex. « United Nations Security Council »)
     * @param topics    catégories de risque des sujets (ex. {@code sanction}, {@code crime.terror})
     */
    public static FtmDatasetBuilder convert(List<SanctionRecord> records, String idPrefix, String authority,
            String... topics) {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, topics);
        for (SanctionRecord record : records) {
            String name = record.name();
            if (name == null) {
                continue; // ni nom, ni prénom/nom, ni alias : rien de criblable
            }
            FtmEntity subject = dataset.newEntity(record.schema(), record.keyParts());
            if (record.first("name") == null) {
                subject.addValue("name", name);
            }
            copy(record.subjectProperties(), subject);
            dataset.addEntity(subject);

            if (record.hasSanction()) {
                FtmEntity sanction = dataset.newEntity("Sanction", append(record.keyParts(), "sanction"));
                sanction.addValue("entity", subject.getId());
                if (record.firstSanction("authority") == null) {
                    sanction.addValue("authority", authority);
                }
                copy(record.sanctionProperties(), sanction);
                dataset.addEntity(sanction);
            }
        }
        return dataset;
    }

    /**
     * Éléments criblables attendus dans Yente (test du parser) : un par fiche nommée, avec l'ID que
     * {@link #convert} lui donne. Ne construit aucune entité FtM.
     */
    public static List<SourceRecord> sourceRecords(List<SanctionRecord> records, String idPrefix) {
        FtmDatasetBuilder ids = new FtmDatasetBuilder(idPrefix);
        List<SourceRecord> out = new ArrayList<>();
        for (SanctionRecord record : records) {
            String name = record.name();
            if (name != null) {
                out.add(new SourceRecord(ids.buildEntityId(record.keyParts()), record.schema(), name,
                        record.sourceRef()));
            }
        }
        return out;
    }

    private static void copy(Map<String, Set<String>> properties, FtmEntity entity) {
        String schema = entity.getSchema();
        for (Map.Entry<String, Set<String>> e : properties.entrySet()) {
            String property = e.getKey();
            String type = FtmSchemaModel.propertyType(schema, property);
            for (String value : e.getValue()) {
                if ("country".equals(type) && !CountryNormalizer.isUnknownValue(value)
                        && CountryNormalizer.toIsoCode(value) == null) {
                    entity.addValue(notesProperty(schema), property + " : " + value);
                } else if ("date".equals(type) && DateNormalizer.toIsoDate(value) == null) {
                    entity.addValue(notesProperty(schema), property + " : " + value);
                } else {
                    entity.addValue(property, value);
                }
            }
        }
    }

    /** {@code Sanction} n'a pas de « notes » : la valeur va dans « summary ». */
    private static String notesProperty(String schema) {
        return FtmSchemaModel.propertyType(schema, "notes") != null ? "notes" : "summary";
    }

    private static String[] append(String[] parts, String last) {
        String[] out = Arrays.copyOf(parts, parts.length + 1);
        out[parts.length] = last;
        return out;
    }
}
