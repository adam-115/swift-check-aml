package com.swiftcheck.aml.plugins.lupep.municipal;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <b>lu_pep_municipal</b> – bourgmestres et échevins des communes du Luxembourg (collèges échevinaux).
 *
 * <p>Ces fonctions ne figurent pas dans la liste officielle des fonctions PEP du Luxembourg : elles sont reprises
 * selon l'approche par les risques, comme le font les fournisseurs du marché. Le règlement européen AMLR
 * (applicable en juillet 2027) y ajoutera les dirigeants des collectivités de 50 000 habitants ou plus.
 *
 * <p>Source : jeu de données « Collège des bourgmestre et échevins depuis les élections communales 2023 » du
 * ministère des Affaires intérieures sur data.public.lu, mis à jour à chaque changement dans un collège.
 * Type {@code HTTP_FILE} : le core télécharge le fichier à l'adresse configurée dans le parser ; ce plugin lit
 * seulement {@code ctx.inputFile()} et écrit le NDJSON.
 *
 * <p>Un mandat par ligne, encodage Windows-1252, dates au format {@code 27-Oct-23} :
 * <pre>
 * COM_CODE,COM_LABEL,MAN_LABEL,ELU_LAST_NAME,ELU_FIRST_NAME,ELU_SEX,COAC_START_DATE,COAC_END_DATE
 * C003,Beaufort,Bourgmestre,Nosbusch,Jean-Luc,M,27-Oct-23,
 * C066,Luxembourg,Échevin,Wilmes,Serge,M,06-Jul-23,16-Nov-23
 * </pre>
 * Un élu qui change de fonction dans sa commune (échevin puis bourgmestre) garde le même ID de personne : le
 * dataset réunit ses lignes en une seule {@code Person}.
 *
 * <p>Paramètre du parser (JSON, facultatif) : {@code {"yearsAfterEnd": 5}}.
 */
@Extension
public class LuPepMunicipalParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "lu-pep-municipal";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    /** Format des dates du fichier, ex. {@code 27-Oct-23}. */
    private static final DateTimeFormatter DATE_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d-MMM-yy")
            .toFormatter(Locale.ENGLISH);

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(sourceFile(ctx), oldestEndDate(ctx.parameters(), LocalDate.now()), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : chaque élu gardé, une fois, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);   // uniquement pour calculer les ID
        Map<String, SourceRecord> records = new LinkedHashMap<>(); // un élu peut avoir plusieurs lignes
        CsvRecordReader.readEach(sourceFile(ctx), row -> {
            if (endedBefore(row, oldestEndDate)) {
                return;                                             // écarté aussi par la conversion
            }
            String id = ids.buildEntityId(row.get("ELU_LAST_NAME"), row.get("ELU_FIRST_NAME"), row.get("COM_CODE"));
            records.putIfAbsent(id, new SourceRecord(id, "Person",
                    joinNonBlank(" ", row.get("ELU_FIRST_NAME"), row.get("ELU_LAST_NAME")),
                    "ligne " + row.lineNumber()));
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

    /** Un mandat communal : l'élu, la fonction dans sa commune et le mandat. */
    private static void convertRow(CsvRow row, FtmDatasetBuilder dataset) {
        String lastName = row.get("ELU_LAST_NAME");
        String firstName = row.get("ELU_FIRST_NAME");
        String communeCode = row.get("COM_CODE");
        String commune = row.get("COM_LABEL");

        // ID = nom + prénom + commune : le fichier ne donne ni identifiant ni date de naissance
        FtmEntity person = dataset.newEntity("Person", lastName, firstName, communeCode);
        person.addValue("name", joinNonBlank(" ", firstName, lastName));
        person.addValue("firstName", firstName);
        person.addValue("lastName", lastName);
        person.addValue("gender", toFtmGender(row.get("ELU_SEX")));
        person.addValue("country", "lu");
        dataset.addEntity(person);

        // Une fonction par commune : « Bourgmestre de Beaufort », « Échevin de Beaufort »
        String function = functionKey(row);
        FtmEntity position = dataset.newEntity("Position", function, communeCode);
        position.addValue("name", row.get("MAN_LABEL") + " de " + commune);
        position.addValue("country", "lu");
        position.addValue("subnationalArea", commune);
        position.addValue("topics", "gov.muni");
        dataset.addEntity(position);

        String startDate = date(row, "COAC_START_DATE");
        String endDate = date(row, "COAC_END_DATE");
        FtmEntity occupancy = dataset.newEntity("Occupancy", lastName, firstName, communeCode, function, startDate);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", startDate);
        occupancy.addValue("endDate", endDate);
        occupancy.addValue("status", endDate == null ? "current" : "ended");
        dataset.addEntity(occupancy);
    }

    /** « Bourgmestre » → mayor, « Échevin » → alderman ; toute autre fonction arrête la conversion. */
    private static String functionKey(CsvRow row) {
        String function = row.get("MAN_LABEL");
        if ("Bourgmestre".equals(function)) {
            return "mayor";
        }
        if ("Échevin".equals(function)) {
            return "alderman";
        }
        throw new IllegalStateException("Fonction communale inconnue « " + function + " » (ligne "
                + row.lineNumber() + ")");
    }

    /** Vrai si le mandat s'est terminé avant la date limite : il n'est plus publié. */
    private static boolean endedBefore(CsvRow row, LocalDate oldestEndDate) {
        String endDate = date(row, "COAC_END_DATE");
        return endDate != null && LocalDate.parse(endDate).isBefore(oldestEndDate);
    }

    /**
     * {@code 27-Oct-23} → {@code 2023-10-27} ; {@code null} si la cellule est vide. Une date illisible arrête
     * la conversion : une date de fin perdue transformerait un ancien mandat en mandat en cours.
     */
    static String date(CsvRow row, String column) {
        String value = row.get(column);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE_FORMAT).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Date illisible « " + value + " » (ligne " + row.lineNumber()
                    + ", colonne " + column + ")");
        }
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
