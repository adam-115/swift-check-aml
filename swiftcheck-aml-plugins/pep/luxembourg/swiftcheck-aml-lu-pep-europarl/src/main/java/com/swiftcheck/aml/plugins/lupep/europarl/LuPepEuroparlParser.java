package com.swiftcheck.aml.plugins.lupep.europarl;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import com.swiftcheck.aml.sdk.XmlElement;
import com.swiftcheck.aml.sdk.XmlRecordReader;

/**
 * <b>lu_pep_europarl</b> – députés européens élus au Luxembourg, en fonction (catégorie b, « membres d'un organe
 * législatif »).
 *
 * <p>Source : liste officielle des députés en fonction, publiée par le Parlement européen au format XML
 * (europarl.europa.eu/meps/en/full-list/xml). Type {@code HTTP_FILE} : le core télécharge le fichier à l'adresse
 * configurée dans le parser ; ce plugin lit seulement {@code ctx.inputFile()} et écrit le NDJSON.
 *
 * <p>Une fiche {@code <mep>} par député, tous pays confondus ; seuls ceux élus au Luxembourg sont gardés.
 * Le nom de famille est écrit en majuscules :
 * <pre>
 * &lt;mep&gt;
 *   &lt;fullName&gt;Marc ANGEL&lt;/fullName&gt;
 *   &lt;country&gt;Luxembourg&lt;/country&gt;
 *   &lt;politicalGroup&gt;Group of the Progressive Alliance of Socialists and Democrats ...&lt;/politicalGroup&gt;
 *   &lt;id&gt;202073&lt;/id&gt;
 *   &lt;nationalPoliticalGroup&gt;Parti ouvrier socialiste luxembourgeois&lt;/nationalPoliticalGroup&gt;
 * &lt;/mep&gt;
 * </pre>
 * Le fichier ne donne ni la date de naissance ni le genre.
 */
@Extension
public class LuPepEuroparlParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "lu-pep-europarl";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    /** Pays de représentation des députés gardés, tel qu'écrit dans le fichier. */
    private static final String COUNTRY = "Luxembourg";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        try (InputStream source = Files.newInputStream(sourceFile(ctx))) {
            convert(source, dataset);
        }
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : chaque député élu au Luxembourg, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);   // uniquement pour calculer les ID
        List<SourceRecord> records = new ArrayList<>();
        try (InputStream source = Files.newInputStream(sourceFile(ctx))) {
            XmlRecordReader.readEach(source, "mep", mep -> {
                if (COUNTRY.equals(mep.childText("country"))) {
                    String id = mep.childText("id");
                    String[] name = splitName(mep.childText("fullName"));
                    records.add(new SourceRecord(ids.buildEntityId(id), "Person",
                            joinNonBlank(" ", name[0], name[1]), "id=" + id));
                }
            });
        }
        return records;
    }

    /** Lit le XML fiche par fiche. Séparé de {@link #parse} pour être testé directement. */
    void convert(InputStream source, FtmDatasetBuilder dataset) throws Exception {
        XmlRecordReader.readEach(source, "mep", mep -> {
            if (COUNTRY.equals(mep.childText("country"))) {
                convertMep(mep, dataset);
            }
        });
    }

    /** Un député : la personne, la fonction de député européen et son mandat en cours. */
    private static void convertMep(XmlElement mep, FtmDatasetBuilder dataset) {
        String id = mep.childText("id");
        String[] name = splitName(mep.childText("fullName"));

        // ID = identifiant officiel du Parlement européen (ex. 202073)
        FtmEntity person = dataset.newEntity("Person", id);
        person.addValue("name", joinNonBlank(" ", name[0], name[1]));
        person.addValue("firstName", name[0]);
        person.addValue("lastName", name[1]);
        person.addValue("country", "lu");
        person.addValue("political", mep.childText("nationalPoliticalGroup"));
        person.addValue("sourceUrl", "https://www.europarl.europa.eu/meps/fr/" + id);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "member");
        position.addValue("name", "Membre du Parlement européen élu au Luxembourg");
        position.addValue("country", "lu");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", id, "member");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");                          // liste des députés en fonction
        occupancy.addValue("politicalGroup", mep.childText("politicalGroup"));
        dataset.addEntity(occupancy);
    }

    /**
     * Sépare « Isabel WISELER-LIMA » en prénom « Isabel » et nom « WISELER-LIMA » : les mots tout en majuscules
     * forment le nom.
     *
     * @return {prénom, nom} ; le prénom vaut {@code null} si tous les mots sont en majuscules
     */
    static String[] splitName(String fullName) {
        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();
        for (String word : fullName.trim().split("\\s+")) {
            StringBuilder target = word.equals(word.toUpperCase()) ? last : first;
            if (target.length() > 0) {
                target.append(' ');
            }
            target.append(word);
        }
        return new String[] { first.length() == 0 ? null : first.toString(), last.toString() };
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
