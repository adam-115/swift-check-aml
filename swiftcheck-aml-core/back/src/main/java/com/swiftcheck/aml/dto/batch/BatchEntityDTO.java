package com.swiftcheck.aml.dto.batch;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** Une ligne du fichier CSV, une fois lue et normalisée : l'entité à vérifier. */
@Data
public class BatchEntityDTO {
    /** Numéro de la ligne dans le fichier (l'en-tête est la ligne 1). */
    private int lineNumber;
    /** Référence client facultative (colonne « reference », « id »...). */
    private String reference;
    /** Schéma FTM : Person (par défaut), Organization, Company, Vessel... */
    private String schema;
    private String firstName;
    private String lastName;
    /** Nom complet : « prénom nom », ou la colonne « nom complet » / « raison sociale ». */
    private String name;
    private String nationality;
    /** Date de naissance au format ISO (aaaa-mm-jj, aaaa-mm ou aaaa). */
    private String birthDate;
    private String birthPlace;
    private String country;
    /** Problèmes relevés à la lecture de la ligne (date illisible, nom absent...). */
    private List<String> warnings = new ArrayList<>();
    /** Faux quand la ligne ne peut pas être vérifiée (pas de nom). */
    private boolean valid = true;
}
