package com.swiftcheck.aml.plugins.frpep.nationalassembly;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lecture de l'archive AMO30 « tous acteurs, tous mandats, tous organes » (JSON zippé) de l'Assemblée nationale.
 *
 * <p>Un fichier par acteur ({@code json/acteur/PA721908.json}) et par organe ({@code json/organe/PO845419.json}).
 * Seuls les acteurs qui ont eu un mandat de député sont gardés, réduits à ce qui sert au criblage, pour ne pas
 * garder les 3 000 fichiers en mémoire :
 * <pre>
 * {"acteur": {"uid": {"#text": "PA721908"},
 *   "etatCivil": {"ident": {"civ": "Mme", "prenom": "Yaël", "nom": "Braun-Pivet"},
 *                 "infoNaissance": {"dateNais": "1970-12-07", "villeNais": "Nancy"}},
 *   "mandats": {"mandat": [
 *     {"uid": "PM…", "typeOrgane": "ASSEMBLEE", "dateDebut": "2024-07-07", "dateFin": null,
 *      "election": {"lieu": {"departement": "Yvelines", "numCirco": "5"}}, "organes": {"organeRef": "PO838901"}},
 *     {"typeOrgane": "BUREAU", "infosQualite": {"codeQualite": "Président"}, …},
 *     {"typeOrgane": "GP", "organes": {"organeRef": "PO845401"}, …}]}}}
 * </pre>
 * Une valeur absente est écrite {@code null} ou {@code {"@xsi:nil": "true"}}.
 */
final class AmoArchive {

    /** Un mandat : député (ASSEMBLEE), présidence (BUREAU) ou groupe politique (GP). Dates ISO, fin nulle = en cours. */
    record Mandate(String uid, String start, String end, String organ, String constituency) {

        boolean current() {
            return end == null;
        }
    }

    /** Un acteur qui a été député. */
    record Deputy(String uid, String title, String firstName, String lastName, String birthDate, String birthPlace,
            List<Mandate> seats, List<Mandate> presidencies, List<Mandate> groups) {
    }

    /** Contenu utile de l'archive : les députés et le nom abrégé de chaque groupe politique. */
    record Content(List<Deputy> deputies, Map<String, String> groups) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);   // le flux zip reste ouvert entre deux fichiers

    private AmoArchive() {
    }

    static Content read(Path zip) throws IOException {
        List<Deputy> deputies = new ArrayList<>();
        Map<String, String> groups = new HashMap<>();
        boolean any = false;
        try (InputStream in = Files.newInputStream(zip); ZipInputStream entries = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".json")) {
                    continue;
                }
                if (name.contains("/acteur/")) {
                    any = true;
                    Deputy d = deputy(JSON.readTree(entries).path("acteur"));
                    if (d != null) {
                        deputies.add(d);
                    }
                } else if (name.contains("/organe/")) {
                    JsonNode organ = JSON.readTree(entries).path("organe");
                    if ("GP".equals(text(organ.path("codeType")))) {
                        String abbrev = text(organ.path("libelleAbrev"));
                        groups.put(text(organ.path("uid")), abbrev != null ? abbrev : text(organ.path("libelle")));
                    }
                }
            }
        }
        if (!any) {
            throw new IOException("Aucun fichier json/acteur/*.json dans " + zip.getFileName()
                    + " : ce n'est pas l'archive AMO30 au format JSON de l'Assemblée nationale.");
        }
        return new Content(deputies, groups);
    }

    /** L'acteur réduit à l'essentiel, ou {@code null} s'il n'a jamais été député. */
    private static Deputy deputy(JsonNode actor) {
        List<Mandate> seats = new ArrayList<>();
        List<Mandate> presidencies = new ArrayList<>();
        List<Mandate> groups = new ArrayList<>();
        for (JsonNode m : list(actor.path("mandats").path("mandat"))) {
            String type = text(m.path("typeOrgane"));
            if ("ASSEMBLEE".equals(type)) {
                seats.add(mandate(m, constituency(m.path("election").path("lieu"))));
            } else if ("BUREAU".equals(type) && "Président".equals(text(m.path("infosQualite").path("codeQualite")))) {
                presidencies.add(mandate(m, null));
            } else if ("GP".equals(type)) {
                groups.add(mandate(m, null));
            }
        }
        if (seats.isEmpty()) {
            return null;
        }
        JsonNode civil = actor.path("etatCivil");
        JsonNode uid = actor.path("uid");
        return new Deputy(uid.isObject() ? text(uid.path("#text")) : text(uid),
                text(civil.path("ident").path("civ")), text(civil.path("ident").path("prenom")),
                text(civil.path("ident").path("nom")), text(civil.path("infoNaissance").path("dateNais")),
                text(civil.path("infoNaissance").path("villeNais")), seats, presidencies, groups);
    }

    private static Mandate mandate(JsonNode m, String constituency) {
        JsonNode organ = m.path("organes").path("organeRef");
        return new Mandate(text(m.path("uid")), text(m.path("dateDebut")), text(m.path("dateFin")),
                organ.isArray() ? text(organ.get(0)) : text(organ), constituency);
    }

    /** « Gironde, 4e circonscription ». */
    private static String constituency(JsonNode place) {
        String department = text(place.path("departement"));
        String number = text(place.path("numCirco"));
        if (department == null) {
            return null;
        }
        return number == null ? department : department + ", " + number + ("1".equals(number) ? "re" : "e") + " circonscription";
    }

    /** Un mandat seul est écrit comme un objet, plusieurs comme une liste. */
    private static List<JsonNode> list(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(out::add);
        } else if (node.isObject()) {
            out.add(node);
        }
        return out;
    }

    /** Texte de la valeur, ou {@code null} si elle est absente, nulle ou {@code {"@xsi:nil": "true"}}. */
    static String text(JsonNode node) {
        if (node == null || !node.isValueNode() || node.isNull()) {
            return null;
        }
        String s = node.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
