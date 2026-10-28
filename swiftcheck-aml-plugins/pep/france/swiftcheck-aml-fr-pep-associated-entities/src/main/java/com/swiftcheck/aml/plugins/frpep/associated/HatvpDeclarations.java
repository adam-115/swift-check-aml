package com.swiftcheck.aml.plugins.frpep.associated;

import static com.swiftcheck.aml.sdk.DateNormalizer.toIsoDate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.swiftcheck.aml.sdk.XmlElement;
import com.swiftcheck.aml.sdk.XmlRecordReader;

/**
 * Lecture du fichier des déclarations publiées par la HATVP ({@code declarations.xml}, ≈ 80 Mo, Licence ouverte),
 * fiche par fiche pour ne pas le charger en mémoire :
 * <pre>
 * &lt;declaration&gt;&lt;dateDepot&gt;27/05/2025 09:12:28&lt;/dateDepot&gt; …
 *   &lt;participationFinanciereDto&gt;&lt;items&gt;&lt;items&gt;&lt;nomSociete&gt;REXEL&lt;/nomSociete&gt;&lt;evaluation&gt;426&lt;/evaluation&gt;
 *       &lt;capitalDetenu/&gt;&lt;nombreParts&gt;20&lt;/nombreParts&gt;…&lt;/items&gt;&lt;/items&gt;&lt;/participationFinanciereDto&gt;
 *   &lt;participationDirigeantDto&gt;&lt;items&gt;&lt;items&gt;&lt;nomSociete&gt;MAIF&lt;/nomSociete&gt;&lt;activite&gt;élue des sociétaires&lt;/activite&gt;
 *       &lt;dateDebut&gt;03/2025&lt;/dateDebut&gt;&lt;dateFin/&gt;…&lt;/items&gt;&lt;/items&gt;&lt;/participationDirigeantDto&gt;
 *   &lt;general&gt;&lt;typeDeclaration&gt;&lt;id&gt;DIA&lt;/id&gt;…&lt;qualiteMandat&gt;&lt;codTypeMandatFichier&gt;depute&lt;/codTypeMandatFichier&gt;
 *     &lt;labelTypeMandat&gt;Député&lt;/labelTypeMandat&gt;… &lt;declarant&gt;&lt;civilite&gt;Mme&lt;/civilite&gt;&lt;nom&gt;…&lt;/nom&gt;&lt;prenom&gt;…&lt;/prenom&gt;
 *     &lt;dateNaissance&gt;16/11/1974&lt;/dateNaissance&gt;…
 * </pre>
 * Seules les déclarations d'intérêts ({@code DI}, {@code DIA}) des mandats demandés sont lues, et pour chaque
 * déclarant la plus récente.
 */
final class HatvpDeclarations {

    /** Valeur mise par la HATVP à la place d'une donnée non publiée. */
    private static final String NOT_PUBLISHED = "[Données non publiées]";

    /** Organe dirigeant dont la PEP est ou a été membre. Dates {@code AAAA-MM}, fin {@code null} = en cours. */
    record Directorship(String organization, String role, String start, String end) {
    }

    /** Participation financière. {@code capitalPercent} et {@code value} peuvent être {@code null}. */
    record Holding(String organization, Double capitalPercent, Double value, String shares) {
    }

    /** La déclaration retenue pour un déclarant. */
    record Declaration(String title, String lastName, String firstName, String birthDate, String mandate,
            String depositDate, String type, List<Directorship> directorships, List<Holding> holdings) {

        String key() {
            return lastName + "|" + firstName + "|" + birthDate;
        }
    }

    private HatvpDeclarations() {
    }

    /** Dernière déclaration d'intérêts de chaque déclarant, pour les mandats {@code mandateTypes} (« depute »…). */
    static List<Declaration> read(Path xml, Set<String> mandateTypes) throws Exception {
        Map<String, Declaration> latest = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(xml)) {
            XmlRecordReader.readEach(in, "declaration", d -> {
                XmlElement general = d.child("general");
                String type = general.child("typeDeclaration").childText("id");
                if (!mandateTypes.contains(general.child("qualiteMandat").childText("codTypeMandatFichier"))
                        || !("DI".equals(type) || "DIA".equals(type))) {
                    return;
                }
                Declaration decl = declaration(d, general, type);
                Declaration known = latest.get(decl.key());
                if (known == null || decl.depositDate().compareTo(known.depositDate()) > 0) {
                    latest.put(decl.key(), decl);
                }
            });
        }
        return new ArrayList<>(latest.values());
    }

    private static Declaration declaration(XmlElement d, XmlElement general, String type) {
        XmlElement declarant = general.child("declarant");
        String mandate = general.child("qualiteMandat").childText("labelTypeMandat");
        if (mandate == null) {
            mandate = general.child("mandat").childText("label");
        }
        List<Directorship> directorships = new ArrayList<>();
        for (XmlElement item : items(d, "participationDirigeantDto")) {
            String name = published(item.childText("nomSociete"));
            if (name != null) {
                directorships.add(new Directorship(name, published(item.childText("activite")),
                        toIsoDate(item.childText("dateDebut")), toIsoDate(item.childText("dateFin"))));
            }
        }
        List<Holding> holdings = new ArrayList<>();
        for (XmlElement item : items(d, "participationFinanciereDto")) {
            String name = published(item.childText("nomSociete"));
            if (name != null) {
                holdings.add(new Holding(name, number(item.childText("capitalDetenu")),
                        number(item.childText("evaluation")), item.childText("nombreParts")));
            }
        }
        return new Declaration(declarant.childText("civilite"), declarant.childText("nom"), declarant.childText("prenom"),
                toIsoDate(declarant.childText("dateNaissance")), mandate, sortableDate(d.childText("dateDepot")), type,
                directorships, holdings);
    }

    private static List<XmlElement> items(XmlElement d, String section) {
        return d.child(section).child("items").children("items");
    }

    /** Nom réellement publié, espaces normalisés ; {@code null} s'il est vide ou masqué (« SCI [Données non publiées] »). */
    static String published(String value) {
        if (value == null || value.contains(NOT_PUBLISHED)) {
            return null;
        }
        String s = value.replaceAll("\\s+", " ").trim();
        return s.isEmpty() || s.equalsIgnoreCase("néant") ? null : s;
    }

    /** « 1 985,50 », « 50 % » → nombre ; {@code null} si illisible. */
    static Double number(String value) {
        if (value == null) {
            return null;
        }
        String s = value.replaceAll("[\\s %€]", "").replace(',', '.');
        try {
            return s.isEmpty() ? null : Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** « 27/05/2025 09:12:28 » → « 2025-05-27 09:12:28 », comparable en texte. */
    private static String sortableDate(String value) {
        if (value == null || !value.matches("\\d{2}/\\d{2}/\\d{4}.*")) {
            return "";
        }
        return value.substring(6, 10) + "-" + value.substring(3, 5) + "-" + value.substring(0, 2) + value.substring(10);
    }
}
