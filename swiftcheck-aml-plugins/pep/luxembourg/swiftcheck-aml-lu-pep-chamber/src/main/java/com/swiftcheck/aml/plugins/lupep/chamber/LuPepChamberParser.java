package com.swiftcheck.aml.plugins.lupep.chamber;

import static com.swiftcheck.aml.sdk.DateNormalizer.toIsoDateOrFail;
import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <b>lu_pep_chamber</b> – députés en fonction à la Chambre des Députés du Luxembourg
 * (catégorie b de la liste des fonctions PEP du Luxembourg).
 *
 * <p>Source : jeu de données n° 105 « Liste des Députés actifs », publié par la Chambre sur data.public.lu
 * (licence CC BY, citer la source). Type {@code HTTP_FILE} : le core télécharge le fichier à l'adresse
 * configurée dans le parser ; ce plugin lit seulement {@code ctx.inputFile()} et écrit le NDJSON.
 *
 * <p>Un député par ligne, encodage Windows-1252 :
 * <pre>
 * per_titre,pph_nom,pph_prenom,date_debut_depute,rattachement_abrv,rattachement_type,pph_date_naissance,address,...
 * "Madame","Adehm","Diane","2023-10-24 00:00:00","CSV","Groupe politique","1970-09-13","...",...
 * </pre>
 * Chaque ligne produit trois entités : la {@code Person}, la fonction ({@code Position}, commune à tous les
 * députés) et le mandat ({@code Occupancy}) qui les relie. Les adresses, téléphones et e-mails ne sont pas
 * repris : ils sont inutiles au criblage.
 */
@Extension
public class LuPepChamberParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "lu-pep-chamber";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    /** Page des portraits ; vide = pas de portraits. Par défaut {@value ChamberPhotos#DEFAULT_URL}. */
    static final String PARAM_PHOTOS_URL = "photosUrl";
    static final String PHOTOS_FILE = "chd-deputies.html";
    static final String PHOTO_LICENCE = "© Chambre des Députés du Grand-Duché de Luxembourg";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String photoNote = "";
        Map<String, String> photos = Map.of();
        String photosUrl = ctx.parameters() == null ? null : ctx.parameters().get(PARAM_PHOTOS_URL);
        if (photosUrl == null) {
            photosUrl = ChamberPhotos.DEFAULT_URL;
        }
        if (!photosUrl.isBlank()) {
            // Les portraits sont un complément : une page injoignable ne bloque pas la publication des députés
            try {
                String html = ChamberPhotos.download(photosUrl);
                if (ctx.rawDir() != null) {
                    Files.createDirectories(ctx.rawDir());
                    Files.writeString(ctx.rawDir().resolve(PHOTOS_FILE), html);   // archivée avec la source
                }
                photos = ChamberPhotos.read(html);
            } catch (IOException e) {
                photoNote = "Portraits non lus (" + e.getMessage() + ").";
            }
        }
        convert(sourceFile(ctx), photos, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(String.format("%d portraits sur %d députés. %s",
                dataset.media().size(), dataset.qualityReport().getEntities("Person"), photoNote).trim());
    }

    /** Test du parser : chaque député du fichier, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);   // uniquement pour calculer les ID
        List<SourceRecord> records = new ArrayList<>();
        CsvRecordReader.readEach(sourceFile(ctx), row -> records.add(new SourceRecord(
                ids.buildEntityId(row.get("pph_nom"), row.get("pph_prenom"), birthDate(row)),
                "Person",
                joinNonBlank(" ", row.get("pph_prenom"), row.get("pph_nom")),
                "ligne " + row.lineNumber())));
        return records;
    }

    /** Lit le CSV ligne par ligne. Séparé de {@link #parse} pour être testé directement. */
    void convert(Path csv, FtmDatasetBuilder dataset) throws Exception {
        convert(csv, Map.of(), dataset);
    }

    /** Idem, avec les portraits de chd.lu par clé de nom ({@link ChamberPhotos#key}). */
    void convert(Path csv, Map<String, String> photos, FtmDatasetBuilder dataset) throws Exception {
        CsvRecordReader.readEach(csv, row -> convertRow(row, photos, dataset));
    }

    /** Un député : la personne, la fonction de député et son mandat en cours. */
    private static void convertRow(CsvRow row, Map<String, String> photos, FtmDatasetBuilder dataset) {
        String lastName = row.get("pph_nom");
        String firstName = row.get("pph_prenom");
        String birthDate = birthDate(row);

        // ID = nom + prénom + date de naissance : le fichier ne donne pas d'identifiant
        FtmEntity person = dataset.newEntity("Person", lastName, firstName, birthDate);
        person.addValue("name", joinNonBlank(" ", firstName, lastName));
        person.addValue("firstName", firstName);
        person.addValue("lastName", lastName);
        person.addValue("birthDate", birthDate);
        person.addValue("gender", toFtmGender(row.get("per_titre")));     // « Madame » → female
        person.addValue("country", "lu");
        person.addValue("political", row.get("rattachement_abrv"));
        dataset.addEntity(person);
        String photo = photos.get(ChamberPhotos.key(joinNonBlank(" ", firstName, lastName)));
        if (photo != null) {
            dataset.addMedia(person.getId(), photo, ChamberPhotos.DEFAULT_URL, PHOTO_LICENCE);   // téléchargée par le core
        }

        // Une seule fonction pour tous les députés : le dataset la fusionne (même ID)
        FtmEntity position = dataset.newEntity("Position", "deputy");
        position.addValue("name", "Député à la Chambre des Députés du Luxembourg");
        position.addValue("country", "lu");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        String startDate = toIsoDateOrFail(row.get("date_debut_depute"), "ligne " + row.lineNumber());
        FtmEntity occupancy = dataset.newEntity("Occupancy", lastName, firstName, birthDate, "deputy", startDate);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", startDate);
        occupancy.addValue("status", "current");                          // député actif
        occupancy.addValue("politicalGroup", row.get("rattachement_abrv"));
        dataset.addEntity(occupancy);
    }

    private static String birthDate(CsvRow row) {
        return toIsoDateOrFail(row.get("pph_date_naissance"), "ligne " + row.lineNumber());
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
