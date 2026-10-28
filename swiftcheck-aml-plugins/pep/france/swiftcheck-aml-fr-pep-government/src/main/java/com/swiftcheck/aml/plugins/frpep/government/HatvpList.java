package com.swiftcheck.aml.plugins.frpep.government;

import static com.swiftcheck.aml.sdk.DateNormalizer.toIsoDate;
import static com.swiftcheck.aml.sdk.DateNormalizer.toIsoDateOrFail;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.swiftcheck.aml.sdk.CsvRecordReader;
import com.swiftcheck.aml.sdk.XmlRecordReader;

/**
 * Lecture de l'open data de la Haute Autorité pour la transparence de la vie publique (HATVP).
 *
 * <p><b>Liste des déclarations</b> ({@code liste.csv}, séparateur {@code ;}, UTF-8), une ligne par déclaration :
 * <pre>
 * civilite;prenom;nom;classement;type_mandat;qualite;type_document;departement;date_publication;date_depot;nom_fichier;url_dossier;open_data;…
 * M.;Sébastien;LECORNU;lecornuaaasebastien5183;gouvernement;Premier ministre;di;;2026-04-20;2025-11-13;…;/pages_nominatives/lecornu-sebastien;lecornu-sebastien-di34789-gouvernement.xml;…
 * M.;Michel;FOURNIER;…;gouvernement;Ancien Ministre délégué …, chargé de la ruralité;dspfm;;;2026-10-20;;/pages_nominatives/fournier-michel-18032;;…
 * </pre>
 * Les lignes {@code type_mandat = gouvernement} donnent les membres du Gouvernement en fonction et ceux qui l'ont
 * quitté récemment : qualité « Ancien … », et souvent une déclaration de fin de fonctions ({@code dspfm}).
 * {@code open_data} est vide tant que la déclaration n'est pas publiée.
 *
 * <p><b>Déclaration</b> (XML, {@code /livraison/dossiers/<open_data>}) : la partie {@code <general>} donne la date
 * de naissance et les dates de la fonction.
 * <pre>
 * &lt;general&gt;… &lt;dateDebutMandat&gt;09/09/2025&lt;/dateDebutMandat&gt;&lt;dateFinMandat/&gt; …
 *   &lt;declarant&gt;&lt;civilite&gt;M.&lt;/civilite&gt;&lt;nom&gt;LECORNU&lt;/nom&gt; … &lt;dateNaissance&gt;11/06/1986&lt;/dateNaissance&gt;
 * </pre>
 */
final class HatvpList {

    static final String GOVERNMENT = "gouvernement";

    /** Une déclaration publiée ou à venir. {@code xmlFile} est {@code null} tant qu'elle n'est pas publiée. */
    record Declaration(String type, String depositDate, String xmlFile) {
    }

    /**
     * Un membre ou ancien membre du Gouvernement. {@code id} = dernier segment de sa page HATVP
     * ({@code lecornu-sebastien}), stable d'une déclaration à l'autre.
     */
    record Member(String id, String title, String firstName, String lastName, String function, boolean former,
            String pageUrl, List<Declaration> declarations) {

        /**
         * Déclaration à lire pour les dates : celle de fin de fonctions si elle est publiée (elle donne la date de
         * fin), sinon la plus récente. Vide si aucune n'est encore publiée.
         */
        Optional<Declaration> bestDeclaration() {
            List<Declaration> published = declarations.stream().filter(d -> d.xmlFile() != null).toList();
            return published.stream().filter(d -> "dspfm".equals(d.type())).findFirst()
                    .or(() -> published.stream().max(Comparator.comparing(d -> d.depositDate() == null ? "" : d.depositDate())));
        }
    }

    /** Dates lues dans une déclaration (ISO, {@code null} si absentes). */
    record Facts(String birthDate, String startDate, String endDate) {
    }

    private HatvpList() {
    }

    /** Membres du Gouvernement de la liste, dans l'ordre du fichier, une entrée par personne. */
    static List<Member> members(Path csv) throws Exception {
        Map<String, Member> members = new LinkedHashMap<>();
        CsvRecordReader.readEach(csv, ';', row -> {
            if (!GOVERNMENT.equals(row.get("type_mandat"))) {
                return;
            }
            String page = row.get("url_dossier");
            String id = page == null ? row.get("classement") : page.substring(page.lastIndexOf('/') + 1);
            String qualite = row.get("qualite");
            Declaration declaration = new Declaration(row.get("type_document"), row.get("date_depot"), row.get("open_data"));
            Member known = members.get(id);
            if (known == null) {
                boolean former = qualite != null && qualite.matches("(?iu)^ancienn?e? .*");
                members.put(id, new Member(id, row.get("civilite"), row.get("prenom"), row.get("nom"), function(qualite),
                        former, page == null ? null : "https://www.hatvp.fr" + page, new ArrayList<>(List.of(declaration))));
            } else {
                known.declarations().add(declaration);
            }
        });
        // Une déclaration de fin de fonctions vaut « ancien membre », même si la qualité n'a pas encore changé
        members.replaceAll((id, m) -> m.former() || m.declarations().stream().noneMatch(d -> "dspfm".equals(d.type())) ? m
                : new Member(m.id(), m.title(), m.firstName(), m.lastName(), m.function(), true, m.pageUrl(), m.declarations()));
        return new ArrayList<>(members.values());
    }

    /** « Ancien Ministre délégué … » → « Ministre délégué … » ; « ministre de … » → « Ministre de … ». */
    static String function(String qualite) {
        if (qualite == null || qualite.isBlank()) {
            return "Membre du Gouvernement";
        }
        String f = qualite.trim().replaceFirst("(?iu)^ancienn?e?\\s+", "");
        return Character.toUpperCase(f.charAt(0)) + f.substring(1);
    }

    /**
     * Dates de la partie {@code <general>} d'une déclaration.
     *
     * @throws IllegalStateException si une date de fin est présente mais illisible : la perdre ferait passer un
     *                               ancien membre pour un membre en fonction
     */
    static Facts facts(Path xml) throws Exception {
        Facts[] facts = { new Facts(null, null, null) };
        try (InputStream in = Files.newInputStream(xml)) {
            XmlRecordReader.readEach(in, "general", g -> facts[0] = new Facts(
                    toIsoDate(g.child("declarant").childText("dateNaissance")),
                    toIsoDate(g.childText("dateDebutMandat")),
                    toIsoDateOrFail(g.childText("dateFinMandat"), xml.getFileName() + ", dateFinMandat")));
        }
        return facts[0];
    }
}
