package com.swiftcheck.aml.plugins.sanction.fr;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pf4j.Extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;

/**
 * Registre national des gels des avoirs de la Direction générale du Trésor (API publique, dernière publication au
 * format JSON). Il reprend les mesures de l'ONU et de l'UE applicables en France et les mesures nationales
 * (Code monétaire et financier, art. L. 562-2 et suivants).
 *
 * <p>Chaque {@code PublicationDetail} est une fiche ({@code IdRegistre} stable) ; ses informations sont une liste
 * de champs typés ({@code TypeChamp} : PRENOM, ALIAS, DATE_DE_NAISSANCE, NATIONALITE, FONDEMENT_JURIDIQUE…).
 */
@Extension
public class FrDgTresorSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "fr-dgt";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "Direction générale du Trésor (France)";
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        try (InputStream in = Files.newInputStream(input)) {
            return read(in);
        }
    }

    List<SanctionRecord> read(InputStream in) throws Exception {
        JsonNode root = JSON.readTree(in);
        List<SanctionRecord> records = new ArrayList<>();
        for (JsonNode fiche : root.path("Publications").path("PublicationDetail")) {
            String id = text(fiche, "IdRegistre");
            if (id != null) {
                records.add(convert(fiche, id));
            }
        }
        return records;
    }

    private static SanctionRecord convert(JsonNode fiche, String id) {
        String nature = text(fiche, "Nature");
        String schema = "Personne physique".equals(nature) ? "Person" : "Navire".equals(nature) ? "Vessel" : "Organization";
        boolean person = "Person".equals(schema);
        String nom = text(fiche, "Nom");
        SanctionRecord r = new SanctionRecord(schema, id).sourceRef("IdRegistre=" + id);
        String prenom = null;
        for (JsonNode champ : fiche.path("RegistreDetail")) {
            if ("PRENOM".equals(text(champ, "TypeChamp"))) {
                prenom = text(champ.path("Valeur").path(0), "Prenom");
            }
        }
        r.add("name", person ? joinNonBlank(" ", prenom, nom) : nom);
        if (person) {
            r.add("firstName", prenom);
            r.add("lastName", nom);
        }
        for (JsonNode champ : fiche.path("RegistreDetail")) {
            String type = text(champ, "TypeChamp");
            for (JsonNode v : champ.path("Valeur")) {
                field(r, schema, type, v);
            }
        }
        r.sanction("authorityId", id)
                .sanction("sourceUrl", "https://gels-avoirs.dgtresor.gouv.fr/Gels/RegistreDetail?idRegistre=" + id);
        return r;
    }

    private static void field(SanctionRecord r, String schema, String type, JsonNode v) {
        boolean person = "Person".equals(schema);
        boolean vessel = "Vessel".equals(schema);
        switch (type == null ? "" : type) {
            case "ALIAS" -> r.add("alias", text(v, "Alias"));
            case "SEXE" -> {
                if (person) {
                    r.add("gender", toFtmGender(text(v, "Sexe")));
                }
            }
            case "DATE_DE_NAISSANCE" -> {
                if (person) {
                    r.add("birthDate", date(text(v, "Annee"), text(v, "Mois"), text(v, "Jour")));
                }
            }
            case "LIEU_DE_NAISSANCE" -> {
                if (person) {
                    r.add("birthPlace", joinNonBlank(", ", text(v, "Lieu"), text(v, "Pays")));
                    r.add("birthCountry", text(v, "Pays"));
                }
            }
            case "NATIONALITE" -> r.add(person ? "nationality" : vessel ? "flag" : "jurisdiction", text(v, "Pays"));
            case "TITRE" -> r.add(person ? "position" : "notes", text(v, "Titre"));
            case "ADRESSE_PM", "ADRESSE_PP" -> {
                r.add("address", text(v, "Adresse"));
                r.add("country", text(v, "Pays"));
            }
            case "IDENTIFICATION" -> identification(r, schema, text(v, "Identification"), text(v, "Commentaire"));
            case "AUTRE_IDENTITE" -> identification(r, schema, text(v, "NumeroCarte"), text(v, "Commentaire"));
            case "PASSEPORT" -> {
                if (person) {
                    r.add("passportNumber", text(v, "NumeroPasseport"));
                }
                r.add("notes", joinNonBlank(" ", "Passeport :", text(v, "NumeroPasseport"), text(v, "Commentaire")));
            }
            case "SITE_INTERNET" -> r.add(vessel ? "notes" : "website", text(v, "SiteInternet"));
            case "TELEPHONE" -> r.add(vessel ? "notes" : "phone", text(v, "Telephone"));
            case "COURRIEL" -> r.add(vessel ? "notes" : "email", text(v, "Courriel"));
            case "NUMERO_OMI" -> {
                if (vessel || "Organization".equals(schema)) {
                    r.add("imoNumber", text(v, "NumeroOMI")); // Organization a aussi imoNumber (armateurs)
                }
            }
            case "MOTIFS" -> r.sanction("reason", text(v, "Motifs"));
            case "FONDEMENT_JURIDIQUE" -> {
                String label = text(v, "FondementJuridiqueLabel");
                r.sanction("provisions", label);
                r.sanction("program", lastParenthesis(label));
            }
            case "REFERENCE_UE" -> r.sanction("summary", "Référence UE : " + text(v, "ReferenceUe"));
            case "REFERENCE_ONU" -> r.sanction("unscId", text(v, "ReferenceOnu"));
            default -> {
            }
        }
    }

    /** Numéro d'immatriculation, fiscal ou d'identité selon le commentaire de la DG Trésor. */
    private static void identification(SanctionRecord r, String schema, String number, String comment) {
        if (number == null || number.equals("/")) {
            r.add("notes", comment);
            return;
        }
        String c = comment == null ? "" : comment.toLowerCase();
        if (!"Vessel".equals(schema)) {
            if (c.contains("fiscal") || c.contains("nif") || c.contains("inn") || c.contains("tax")) {
                r.add("taxNumber", number);
            } else if (!"Person".equals(schema) && (c.contains("enregistrement") || c.contains("ogrn") || c.contains("registre"))) {
                r.add("registrationNumber", number);
            } else {
                r.add("idNumber", number);
            }
        }
        r.add("notes", joinNonBlank(" : ", comment, number));
    }

    /**
     * Dernière parenthèse de premier niveau : « (UE) 2018/1929 du 10/12/2018 (UE Ukraine intégrité territoriale -
     * R (UE) 269/2014) » → « UE Ukraine intégrité territoriale - R (UE) 269/2014 ».
     */
    static String lastParenthesis(String label) {
        if (label == null || !label.trim().endsWith(")")) {
            return null;
        }
        String s = label.trim();
        int depth = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(' && --depth == 0) {
                return s.substring(i + 1, s.length() - 1).trim();
            }
        }
        return null;
    }

    private static String date(String year, String month, String day) {
        if (year == null) {
            return null;
        }
        if (month == null) {
            return year;
        }
        String ym = year + "-" + (month.length() == 1 ? "0" + month : month);
        return day == null ? ym : ym + "-" + (day.length() == 1 ? "0" + day : day);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
