package com.swiftcheck.aml.plugins.lupep.chamberformer;

import static com.swiftcheck.aml.sdk.DateNormalizer.toIsoDateOrFail;
import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <b>lu_pep_chamber_former</b> – anciens députés de la Chambre des Députés du Luxembourg (depuis 1999).
 * Un PEP le reste au moins 12 mois après la fin de ses fonctions : les mandats terminés depuis moins de
 * {@code yearsAfterEnd} ans (5 par défaut) sont publiés avec le statut {@code ended}.
 *
 * <p>Source : jeu de données n° 106 « Liste des anciens Députés », publié par la Chambre sur data.public.lu
 * (licence CC BY, citer la source). Type {@code HTTP_FILE} : le core télécharge le fichier à l'adresse
 * configurée dans le parser ; ce plugin lit seulement {@code ctx.inputFile()} et écrit le NDJSON.
 *
 * <p>Un <b>mandat</b> par ligne, encodage UTF-8 :
 * <pre>
 * PERSON_TITLE,NAME,FIRSTNAME,START_DATE,END_DATE,POLITICAL_GROUP,BIRTH_DATE,POLITICAL_PARTY
 * Madame,Hansen,Martine,30/10/2018,24/10/2023,CSV,10/12/1965,CSV
 * Madame,Hansen,Martine,24/10/2023,17/11/2023,CSV,10/12/1965,CSV
 * </pre>
 * Une personne peut avoir plusieurs lignes : elles ont le même ID de personne, et le dataset les réunit en une
 * seule {@code Person} avec un {@code Occupancy} par mandat.
 *
 * <p>Paramètre du parser (JSON, facultatif) : {@code {"yearsAfterEnd": 5}}.
 */
@Extension
public class LuPepChamberFormerParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "lu-pep-chamber-former";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(sourceFile(ctx), oldestEndDate(ctx.parameters(), LocalDate.now()), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : chaque ancien député gardé, une fois, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);   // uniquement pour calculer les ID
        Map<String, SourceRecord> records = new LinkedHashMap<>(); // une personne peut avoir plusieurs lignes
        CsvRecordReader.readEach(sourceFile(ctx), row -> {
            if (endedBefore(row, oldestEndDate)) {
                return;                                             // écarté aussi par la conversion
            }
            String id = ids.buildEntityId(row.get("NAME"), row.get("FIRSTNAME"), birthDate(row));
            records.putIfAbsent(id, new SourceRecord(id, "Person",
                    joinNonBlank(" ", row.get("FIRSTNAME"), row.get("NAME")), "ligne " + row.lineNumber()));
        });
        return new ArrayList<>(records.values());
    }

    /** Lit le CSV ligne par ligne. Séparé de {@link #parse} pour être testé avec une date du jour fixe. */
    void convert(Path csv, LocalDate oldestEndDate, FtmDatasetBuilder dataset) throws Exception {
        CsvRecordReader.readEach(csv, row -> {
            if (!endedBefore(row, oldestEndDate)) {
                convertRow(row, dataset);
            }
        });
    }

    /** Un ancien mandat : la personne, la fonction de député et le mandat terminé. */
    private static void convertRow(CsvRow row, FtmDatasetBuilder dataset) {
        String where = "ligne " + row.lineNumber();
        String lastName = row.get("NAME");
        String firstName = row.get("FIRSTNAME");
        String birthDate = birthDate(row);

        // ID = nom + prénom + date de naissance : le fichier ne donne pas d'identifiant
        FtmEntity person = dataset.newEntity("Person", lastName, firstName, birthDate);
        person.addValue("name", joinNonBlank(" ", firstName, lastName));
        person.addValue("firstName", firstName);
        person.addValue("lastName", lastName);
        person.addValue("birthDate", birthDate);
        person.addValue("gender", toFtmGender(row.get("PERSON_TITLE")));  // « Madame » → female
        person.addValue("country", "lu");
        person.addValue("political", row.get("POLITICAL_PARTY"));
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "deputy");
        position.addValue("name", "Député à la Chambre des Députés du Luxembourg");
        position.addValue("country", "lu");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        String startDate = toIsoDateOrFail(row.get("START_DATE"), where);
        FtmEntity occupancy = dataset.newEntity("Occupancy", lastName, firstName, birthDate, "deputy", startDate);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", startDate);
        occupancy.addValue("endDate", endDate(row));
        occupancy.addValue("status", "ended");
        occupancy.addValue("politicalGroup", row.get("POLITICAL_GROUP"));
        dataset.addEntity(occupancy);
    }

    /** Vrai si le mandat s'est terminé avant la date limite : il n'est plus publié. */
    private static boolean endedBefore(CsvRow row, LocalDate oldestEndDate) {
        return LocalDate.parse(endDate(row)).isBefore(oldestEndDate);
    }

    /** Date de fin du mandat. Sans elle, le mandat passerait pour un mandat en cours : on s'arrête. */
    private static String endDate(CsvRow row) {
        String endDate = toIsoDateOrFail(row.get("END_DATE"), "ligne " + row.lineNumber());
        if (endDate == null) {
            throw new IllegalStateException("Ancien mandat sans date de fin (ligne " + row.lineNumber() + ")");
        }
        return endDate;
    }

    private static String birthDate(CsvRow row) {
        return toIsoDateOrFail(row.get("BIRTH_DATE"), "ligne " + row.lineNumber());
    }

    /** Date limite d'après le paramètre {@code yearsAfterEnd} (5 ans par défaut). */
    static LocalDate oldestEndDate(Map<String, String> parameters, LocalDate today) {
        String years = parameters.getOrDefault("yearsAfterEnd", "5");
        try {
            return today.minusYears(Integer.parseInt(years.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre yearsAfterEnd : nombre entier attendu, reçu « " + years + " »");
        }
    }
    /**
     * Fichier source téléchargé par le core. Il est absent quand le parser est configuré en « Géré par le plugin »
     * ({@code PLUGIN_MANAGED}) : ce plugin ne télécharge rien lui-même, on explique alors quoi corriger.
     */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "un fichier téléchargé par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse du fichier source (voir le README des plugins).");
        }
        return ctx.inputFile();
    }
}
