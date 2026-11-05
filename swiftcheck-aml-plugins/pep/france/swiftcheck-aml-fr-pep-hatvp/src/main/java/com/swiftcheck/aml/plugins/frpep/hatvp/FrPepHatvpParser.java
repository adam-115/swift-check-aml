package com.swiftcheck.aml.plugins.frpep.hatvp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.CsvRecordReader;
import com.swiftcheck.aml.sdk.CsvRow;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_hatvp</b> – personnes assujetties à l'obligation de déclaration à la Haute Autorité pour la transparence de
 * la vie publique (HATVP), d'après la liste officielle des déclarations en open data
 * ({@code https://www.hatvp.fr/livraison/opendata/liste.csv}, séparateur {@code ;}, UTF-8, Licence ouverte).
 *
 * <p>Le fichier a une ligne par déclaration ; plusieurs lignes décrivent la même personne et la même fonction
 * (déclaration initiale, modification, fin de fonctions). Le plugin regroupe par personne ({@code classement}, clé
 * stable de la HATVP) puis par fonction ({@code type_mandat} + {@code qualite}). Il couvre ce que les autres parsers
 * français ne donnent pas : adjoints et vice-présidents des collectivités, conseillers départementaux et régionaux,
 * membres des collectivités à statut particulier, membres de la HATVP, en plus des parlementaires et du Gouvernement.
 * Une qualité commençant par « Ancien » est enregistrée comme fonction terminée (le cœur applique la durée de rétention).
 *
 * <p>Type {@code HTTP_FILE} : le cœur télécharge {@code liste.csv}. La liste ne donne ni la date de naissance ni la
 * date de début de fonction (elles sont dans les déclarations XML, non lues ici : une requête par déclaration).
 * Paramètre facultatif {@code types} : liste séparée par des virgules de {@code type_mandat} à garder
 * (ex. {@code "commune,epci,departement,region,ctsp,autre"}) ; tous par défaut.
 */
@Extension
public class FrPepHatvpParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-hatvp";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String SITE = "https://www.hatvp.fr";

    static final String PHOTO_LICENCE = "Photo officielle (Assemblée nationale, Sénat, Parlement européen) référencée par la HATVP";

    private static final Pattern FORMER = Pattern.compile("(?iu)^anciens?\\s+|^anciennes?\\s+");

    /** Thèmes FtM par {@code type_mandat}. */
    private static final Map<String, List<String>> TOPICS = new LinkedHashMap<>();
    static {
        TOPICS.put("gouvernement", List.of("gov.national", "gov.executive"));
        TOPICS.put("president", List.of("gov.head", "gov.executive"));
        TOPICS.put("depute", List.of("gov.national", "gov.legislative"));
        TOPICS.put("senateur", List.of("gov.national", "gov.legislative"));
        TOPICS.put("europe", List.of("gov.igo", "gov.legislative"));
        TOPICS.put("commune", List.of("gov.muni"));
        TOPICS.put("epci", List.of("gov.muni"));
        TOPICS.put("departement", List.of("gov.state"));
        TOPICS.put("region", List.of("gov.state"));
        TOPICS.put("ctsp", List.of("gov.state"));
        TOPICS.put("autre", List.of("gov.admin"));
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int lines, int persons, int functions, int former, Map<String, Integer> byType) {

        String message() {
            return String.format("%d lignes de déclarations → %d personnes, %d fonctions (dont %d terminées). Par type : %s.",
                    lines, persons, functions, former, byType);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(sourceFile(ctx), types(ctx.parameters()), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque personne, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(sourceFile(ctx), types(ctx.parameters()), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Lit {@code liste.csv} et écrit les personnes. Séparé de {@link #parse} pour les tests. */
    Report convert(Path csv, Set<String> types, FtmDatasetBuilder dataset) throws Exception {
        int[] n = new int[2];                       // lignes, fonctions terminées
        Map<String, Integer> byType = new LinkedHashMap<>();
        Set<String> seenFunction = new LinkedHashSet<>();
        Set<String> seenPerson = new LinkedHashSet<>();
        CsvRecordReader.readEach(csv, ';', row -> {
            n[0]++;
            String type = row.get("type_mandat");
            String key = row.get("classement");
            String quality = row.get("qualite");
            if (type == null || key == null || quality == null || quality.isBlank()
                    || (!types.isEmpty() && !types.contains(type))) {
                return;
            }
            boolean former = FORMER.matcher(quality.trim()).find();
            String function = capitalize(FORMER.matcher(quality.trim()).replaceFirst(""));
            seenPerson.add(key);
            if (seenFunction.add(key + "|" + type + "|" + quality.trim())) {
                byType.merge(type, 1, Integer::sum);
                if (former) {
                    n[1]++;
                }
            }
            write(row, type, key, function, former, dataset);
        });
        if (n[0] == 0) {
            throw new IllegalStateException("liste.csv vide : téléchargement incomplet ou format changé.");
        }
        if (seenFunction.isEmpty()) {
            throw new IllegalStateException("Aucune fonction lue dans liste.csv (colonnes « type_mandat », « classement », "
                    + "« qualite ») : le format du fichier a changé.");
        }
        return new Report(n[0], seenPerson.size(), seenFunction.size(), n[1], byType);
    }

    private static void write(CsvRow row, String type, String key, String function, boolean former,
            FtmDatasetBuilder dataset) {
        String first = row.get("prenom");
        String last = row.get("nom");
        String page = row.get("url_dossier") == null ? null : SITE + row.get("url_dossier");
        String name = (first == null ? "" : first) + (first == null || last == null ? "" : " ") + (last == null ? "" : last);

        // ID = clé « classement » de la HATVP, stable d'une déclaration à l'autre
        FtmEntity person = dataset.newEntity("Person", key);
        person.addValue("name", name);
        person.addValue("firstName", first);
        person.addValue("lastName", last);
        person.addValue("gender", gender(row.get("civilite")));
        person.addValue("country", "fr");
        person.addValue("position", function);
        person.addValue("sourceUrl", page);
        dataset.addEntity(person);
        String photo = row.get("url_photo");
        if (photo != null && photo.startsWith("https://")) {
            dataset.addMedia(person.getId(), photo, page == null ? SITE : page, PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", type, function);
        position.addValue("name", function);
        position.addValue("country", "fr");
        for (String topic : TOPICS.getOrDefault(type, List.of())) {
            position.addValue("topics", topic);
        }
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", key, type, function);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", former ? "ended" : "current");
        occupancy.addValue("sourceUrl", page);
        dataset.addEntity(occupancy);
    }

    private static String gender(String civility) {
        if (civility == null) {
            return null;
        }
        return switch (civility.trim().toLowerCase()) {
            case "m.", "m" -> "male";
            case "mme", "mlle" -> "female";
            default -> null;
        };
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static Set<String> types(Map<String, String> parameters) {
        String value = parameters == null ? null : parameters.get("types");
        Set<String> out = new LinkedHashSet<>();
        if (value != null) {
            for (String t : value.split(",")) {
                if (!t.isBlank()) {
                    out.add(t.trim());
                }
            }
        }
        return out;
    }

    /** Fichier téléchargé par le cœur ; absent si le parser n'est pas de type « Fichier HTTP ». */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null || !Files.exists(ctx.inputFile())) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "liste.csv téléchargé par le core. Choisissez le type « Fichier HTTP » (HTTP_FILE) avec l'adresse "
                    + "https://www.hatvp.fr/livraison/opendata/liste.csv.");
        }
        return ctx.inputFile();
    }
}
