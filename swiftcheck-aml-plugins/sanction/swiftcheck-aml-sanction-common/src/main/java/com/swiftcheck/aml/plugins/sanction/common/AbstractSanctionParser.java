package com.swiftcheck.aml.plugins.sanction.common;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/**
 * Base des parsers de sanctions : le parser lit sa source en {@link SanctionRecord}, la classe fait le reste
 * (conversion FtM commune, écriture du NDJSON, liste des éléments pour le test du parser).
 *
 * <p>Deux modes, selon le type de source configuré dans le parser :
 * <ul>
 * <li>{@code HTTP_FILE} / {@code LOCAL_FILE} : le core a téléchargé le fichier, le parser implémente
 * {@link #read(Path)} ;</li>
 * <li>{@code PLUGIN_MANAGED} : le parser surcharge {@link #download} (récupère ses pages et les enregistre dans
 * {@code rawDir}) et {@link #readRaw} (relit {@code rawDir}).</li>
 * </ul>
 */
public abstract class AbstractSanctionParser implements Iparser {

    /** Préfixe des ID FtM : ne jamais le changer une fois la liste publiée (les alertes y sont rattachées). */
    protected abstract String idPrefix();

    /**
     * Préfixe d'ID pour cette exécution : paramètre {@code idPrefix} du parser s'il est renseigné (un même plugin
     * sert alors plusieurs listes, ex. OFAC SDN et OFAC consolidée), sinon {@link #idPrefix()}.
     */
    protected String idPrefix(Map<String, String> parameters) {
        String fromParameters = parameters.get("idPrefix");
        return fromParameters == null || fromParameters.isBlank() ? idPrefix() : fromParameters.trim();
    }

    /** Autorité qui prononce les sanctions, recopiée dans chaque entité {@code Sanction}. */
    protected abstract String authority();

    /** Catégories de risque des sujets ; {@code sanction} par défaut. */
    protected String[] topics() {
        return new String[] { "sanction" };
    }

    /** Lecture du fichier reçu du core (sources {@code HTTP_FILE} / {@code LOCAL_FILE}). */
    protected List<SanctionRecord> read(Path input) throws Exception {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " attend une source PLUGIN_MANAGED (le plugin télécharge lui-même ses données)");
    }

    /** Source {@code PLUGIN_MANAGED} : télécharge les données et enregistre chaque réponse brute dans rawDir. */
    protected void download(Map<String, String> parameters, Path rawDir) throws Exception {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " attend un fichier téléchargé par le core (source HTTP_FILE)");
    }

    /** Source {@code PLUGIN_MANAGED} : relit les fichiers bruts enregistrés par {@link #download}. */
    protected List<SanctionRecord> readRaw(Map<String, String> parameters, Path rawDir) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        List<SanctionRecord> records = records(ctx, true);
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, idPrefix(parameters(ctx)), authority(), topics());
        dataset.writeNdjsonFile(ctx.outputFile());
        ParserResult result = dataset.toConversionResult();
        return result.withMessage(records.size() + " fiches lues ; " + result.message());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        return SanctionDataset.sourceRecords(records(ctx, false), idPrefix(parameters(ctx)));
    }

    private static Map<String, String> parameters(ParserContext ctx) {
        return ctx.parameters() == null ? Map.of() : ctx.parameters();
    }

    /** Fichier reçu du core ; les parsers qui ont besoin des paramètres surchargent cette méthode. */
    protected List<SanctionRecord> read(Path input, Map<String, String> parameters) throws Exception {
        return read(input);
    }

    /** Les fiches de la source ; au moins une, sinon la source a changé de format ou est vide. */
    private List<SanctionRecord> records(ParserContext ctx, boolean fetch) throws Exception {
        Map<String, String> parameters = parameters(ctx);
        List<SanctionRecord> records;
        if (ctx.inputFile() != null) {
            records = read(ctx.inputFile(), parameters);
        } else {
            if (fetch || isEmptyDir(ctx.rawDir())) {
                download(parameters, ctx.rawDir());
            }
            records = readRaw(parameters, ctx.rawDir());
        }
        if (records.isEmpty()) {
            throw new IllegalStateException("Aucune fiche lue dans la source de " + ctx.datasetName()
                    + " : fichier vide ou format changé");
        }
        return records;
    }

    private static boolean isEmptyDir(Path dir) throws Exception {
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            return true;
        }
        try (var s = java.nio.file.Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }
}
