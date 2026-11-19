package com.swiftcheck.aml.plugins.sanction.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Une inscription sur une liste de sanctions, lue dans le fichier source, avant sa conversion en entités FtM.
 *
 * <p>Chaque parser de sanctions lit sa source et produit des {@code SanctionRecord} ; la conversion FtM est faite
 * une seule fois, de la même façon pour toutes les listes, par {@link SanctionDataset}. Une fiche donne :
 * <ul>
 * <li>le sujet criblable ({@code Person}, {@code Organization}, {@code Vessel}, {@code Airplane}…) avec les
 * propriétés ajoutées par {@link #add} ;</li>
 * <li>une entité {@code Sanction} reliée, avec les propriétés ajoutées par {@link #sanction} (sauf
 * {@link #withoutSanction()}).</li>
 * </ul>
 *
 * <p>Les noms de propriétés sont ceux de FollowTheMoney ({@code name}, {@code alias}, {@code birthDate},
 * {@code nationality}, {@code passportNumber}, {@code imoNumber}…). Une propriété inconnue pour le schéma est
 * refusée par le SDK et comptée dans le bilan qualité : le test unitaire de chaque parser vérifie qu'il n'y en a pas.
 */
public final class SanctionRecord {

    private final String schema;
    private final String[] keyParts;
    private final Map<String, Set<String>> subject = new LinkedHashMap<>();
    private final Map<String, Set<String>> sanction = new LinkedHashMap<>();
    private final List<String> sourceRefs = new ArrayList<>();
    private boolean withSanction = true;

    /**
     * @param schema   schéma FtM du sujet (Person, Organization, Company, LegalEntity, Vessel, Airplane…)
     * @param keyParts identifiant officiel et stable de la fiche dans la source (numéro, référence)
     */
    public SanctionRecord(String schema, String... keyParts) {
        this.schema = schema;
        this.keyParts = keyParts;
    }

    public String schema() {
        return schema;
    }

    public String[] keyParts() {
        return keyParts.clone();
    }

    /** Ajoute une valeur au sujet. Les valeurs vides sont ignorées ; les espaces sont normalisés. */
    public SanctionRecord add(String property, String value) {
        put(subject, property, value);
        return this;
    }

    /** Ajoute plusieurs valeurs au sujet. */
    public SanctionRecord addAll(String property, Collection<String> values) {
        if (values != null) {
            values.forEach(v -> add(property, v));
        }
        return this;
    }

    /** Ajoute une valeur à l'entité {@code Sanction} reliée (program, authority, listingDate, reason…). */
    public SanctionRecord sanction(String property, String value) {
        put(sanction, property, value);
        return this;
    }

    /** Pas d'entité {@code Sanction} (ex. une adresse ou un navire déjà décrits par la fiche principale). */
    public SanctionRecord withoutSanction() {
        this.withSanction = false;
        return this;
    }

    public boolean hasSanction() {
        return withSanction;
    }

    /** Référence lisible de la fiche dans la source (ex. « DataID=6908555 »), affichée dans le test du parser. */
    public SanctionRecord sourceRef(String ref) {
        if (ref != null && !ref.isBlank()) {
            sourceRefs.add(ref.trim());
        }
        return this;
    }

    public String sourceRef() {
        return sourceRefs.isEmpty() ? String.join("/", Arrays.asList(keyParts)) : String.join(", ", sourceRefs);
    }

    /**
     * Nom complet : le premier nom ajouté, sinon le premier alias.
     *
     * <p>Une personne sans nom complet mais avec ses prénoms et nom reçoit ici « prénom nom », pour que la
     * fiche reste criblable.
     */
    public String name() {
        String name = first("name");
        if (name == null) {
            String joined = joinNames(first("firstName"), first("middleName"), first("lastName"));
            if (!joined.isEmpty()) {
                return joined;
            }
            name = first("alias");
        }
        return name;
    }

    public Map<String, Set<String>> subjectProperties() {
        return subject;
    }

    public Map<String, Set<String>> sanctionProperties() {
        return sanction;
    }

    public Set<String> values(String property) {
        return subject.getOrDefault(property, Set.of());
    }

    public String first(String property) {
        Set<String> v = subject.get(property);
        return v == null || v.isEmpty() ? null : v.iterator().next();
    }

    public String firstSanction(String property) {
        Set<String> v = sanction.get(property);
        return v == null || v.isEmpty() ? null : v.iterator().next();
    }

    private static void put(Map<String, Set<String>> target, String property, String value) {
        if (value == null) {
            return;
        }
        String clean = value.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        if (clean.isEmpty() || clean.equals("-") || clean.equalsIgnoreCase("n/a") || clean.equalsIgnoreCase("null")) {
            return;
        }
        target.computeIfAbsent(property, k -> new LinkedHashSet<>()).add(clean);
    }

    private static String joinNames(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return schema + " " + Arrays.toString(keyParts) + " " + name();
    }
}
