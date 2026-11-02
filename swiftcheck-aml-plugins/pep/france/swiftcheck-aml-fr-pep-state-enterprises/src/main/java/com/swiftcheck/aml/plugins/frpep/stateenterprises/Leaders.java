package com.swiftcheck.aml.plugins.frpep.stateenterprises;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Écrit une entreprise et ses dirigeants personnes physiques (fiche de l'API Recherche d'entreprises) : Company,
 * Person, Position (qualité dans l'entreprise), Occupancy et Directorship.
 *
 * <p>Le registre ne donne ni date de prise de fonction ni date de départ : chaque dirigeant inscrit est repris comme
 * en fonction. Les personnes morales (État, collectivités, commissaires aux comptes), les commissaires aux comptes et
 * les liquidateurs personnes physiques sont écartés : ce ne sont pas des membres des organes d'administration ou de
 * direction.
 */
final class Leaders {

    /** Qualité « Autre » du registre (fréquente pour les EPIC, ex. le conseil d'administration de la RATP). */
    static final String UNSPECIFIED_ROLE = "Dirigeant (qualité non précisée au registre)";

    static final String PEP_TOPIC = "role.pep";
    static final String SOE_TOPIC = "gov.soe";
    static final String ANNUAIRE = "https://annuaire-entreprises.data.gouv.fr/entreprise/";

    private Leaders() {
    }

    /**
     * @param label       nom de l'entreprise à afficher (libellé de la source officielle si elle en a un)
     * @param description contexte de l'entreprise, ex. « Portefeuille de l'APE – Énergie »
     * @return le nombre de dirigeants écrits
     */
    static int write(JsonNode company, String label, String description, String positionLabel, FtmDatasetBuilder dataset) {
        String siren = company.path("siren").asText();
        String name = label == null || label.isBlank() ? company.path("nom_complet").asText() : label;

        FtmEntity org = dataset.newEntity("Company", "company", siren);
        org.addValue("name", name);
        org.addValue("alias", company.path("nom_complet").asText(null));
        org.addValue("registrationNumber", siren);
        org.addValue("jurisdiction", "fr");
        org.addValue("description", description);
        org.addValue("topics", SOE_TOPIC);
        org.addValue("sourceUrl", ANNUAIRE + siren);
        dataset.addEntity(org);

        int written = 0;
        for (JsonNode d : company.path("dirigeants")) {
            String role = d.path("qualite").asText("").trim();
            String lower = role.toLowerCase(Locale.ROOT);
            if (!"personne physique".equals(d.path("type_dirigeant").asText())
                    || lower.contains("commissaire aux comptes") || lower.contains("liquidateur")) {
                continue;
            }
            writeLeader(d, role(role), siren, name, positionLabel, org, dataset);
            written++;
        }
        return written;
    }

    private static void writeLeader(JsonNode d, String role, String siren, String company, String positionLabel,
            FtmEntity org, FtmDatasetBuilder dataset) {
        // « PEDINI (BACONNET) » : nom d'usage, puis nom de naissance entre parenthèses ; idem pour les prénoms
        String rawLast = d.path("nom").asText("");
        String rawFirst = d.path("prenoms").asText("");
        String lastName = properCase(beforeParenthesis(rawLast));
        String birthName = properCase(inParenthesis(rawLast));
        String firstNames = properCase(beforeParenthesis(rawFirst));
        String firstName = firstNames.split(" ")[0];
        String birth = d.path("date_de_naissance").asText(d.path("annee_de_naissance").asText(null));
        String title = role + " – " + company;

        // ID = nom + prénoms + mois de naissance : le registre ne donne pas d'identifiant de personne. La même
        // personne au conseil de deux entreprises est une seule Person, avec deux fonctions.
        FtmEntity person = dataset.newEntity("Person", lastName, firstNames, birth);
        person.addValue("name", joinNonBlank(" ", firstName, lastName));
        person.addValue("alias", joinNonBlank(" ", firstNames, lastName));
        if (!birthName.isEmpty()) {
            person.addValue("alias", joinNonBlank(" ", firstName, birthName));
        }
        person.addValue("firstName", firstName);
        person.addValue("lastName", lastName);
        person.addValue("birthDate", birth);
        person.addValue("country", "fr");
        person.addValue("position", title);
        person.addValue("topics", PEP_TOPIC);
        person.addValue("sourceUrl", ANNUAIRE + siren);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", siren, role);
        position.addValue("name", title);
        position.addValue("organization", company);
        position.addValue("country", "fr");
        position.addValue("description", positionLabel);
        position.addValue("topics", SOE_TOPIC);
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", lastName, firstNames, birth, siren, role);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        dataset.addEntity(occupancy);

        FtmEntity directorship = dataset.newEntity("Directorship", lastName, firstNames, birth, siren, role, "board");
        directorship.addValue("director", person.getId());
        directorship.addValue("organization", org.getId());
        directorship.addValue("role", role);
        dataset.addEntity(directorship);
    }

    /**
     * Qualité affichée. Le registre cumule parfois plusieurs qualités (« Autre, Membre ») : « Autre » n'est gardé que
     * seul, et devient {@link #UNSPECIFIED_ROLE}.
     */
    static String role(String raw) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (!p.isEmpty() && !"autre".equalsIgnoreCase(p) && !parts.contains(p)) {
                parts.add(p);
            }
        }
        return parts.isEmpty() ? UNSPECIFIED_ROLE : String.join(", ", parts);
    }

    static String beforeParenthesis(String s) {
        int i = s.indexOf('(');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    static String inParenthesis(String s) {
        int i = s.indexOf('(');
        int j = s.indexOf(')', i + 1);
        return i < 0 || j < 0 ? "" : s.substring(i + 1, j).trim();
    }

    /** « DE ROMANET DE BEAUNE » → « De Romanet De Beaune », « JEAN-MARC » → « Jean-Marc », « D'ARC » → « D'Arc ». */
    static String properCase(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean start = true;
        for (char c : s.trim().replaceAll("\\s+", " ").toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = c == ' ' || c == '-' || c == '\'' || c == '’';
        }
        return out.toString();
    }
}
