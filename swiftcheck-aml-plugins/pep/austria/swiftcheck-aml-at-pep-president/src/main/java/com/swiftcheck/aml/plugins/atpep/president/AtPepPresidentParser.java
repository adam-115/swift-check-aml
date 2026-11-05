package com.swiftcheck.aml.plugins.atpep.president;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>at_pep_president</b> – Président fédéral autrichien (catégorie AT-a ; FM-GwG § 2 Z 6 lit. a ; liste notifiée à
 * la Commission, JO C/2023/724).
 *
 * <p>Source : le récit de vie officiel du Président sur le site de la Présidence fédérale
 * ({@code https://www.bundespraesident.at/aktuelles/detail/lebenslauf-alexander-van-der-bellen}). Type
 * {@code HTTP_FILE} : le core télécharge la page à l'adresse configurée dans le parser ; ce plugin lit seulement
 * {@code ctx.inputFile()}. La page contient une phrase de la forme :
 * <pre>
 * Am 18. Jänner 1944 wird Alexander Van der Bellen in Wien geboren. …
 * </pre>
 * et elle est vérifiée par la présence du mot « Bundespräsident ». La page ne donne pas une date de prise de
 * fonctions exploitable : le mandat est publié sans date de début. Aucune donnée familiale n'est reprise.
 *
 * <p>À chaque nouveau Président, la page est remplacée par la biographie du nouveau titulaire : le Président sortant
 * disparaît alors de la source et le core le garde publié pendant la durée de rétention du parser (12 mois).
 */
@Extension
public class AtPepPresidentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "at-pep-president";
    static final String TOPIC = "role.pep";
    static final String POSITION = "Président fédéral autrichien";
    static final String DEFAULT_PAGE_URL = "https://www.bundespraesident.at/aktuelles/detail/lebenslauf-alexander-van-der-bellen";

    /** « Am 18. Jänner 1944 wird Alexander Van der Bellen in Wien geboren. » (récit de vie sur le site officiel). */
    private static final Pattern BIRTH = Pattern.compile(
            "(?u)Am (\\d{1,2})\\. (\\p{L}+) (\\d{4}) wird ([\\p{Lu}][\\p{L}'’ -]+?) in ([\\p{Lu}][\\p{L}'’ -]+?) geboren");
    private static final Pattern TITLE = Pattern.compile("(?iu)Bundespr[aä]sident");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janner", 1), Map.entry("januar", 1), Map.entry("februar", 2), Map.entry("feber", 2),
            Map.entry("marz", 3), Map.entry("april", 4), Map.entry("mai", 5), Map.entry("juni", 6), Map.entry("juli", 7),
            Map.entry("august", 8), Map.entry("september", 9), Map.entry("oktober", 10), Map.entry("november", 11),
            Map.entry("dezember", 12));

    /** Le Président, tel que lu sur la page. */
    record President(String name, String firstName, String lastName, String birthDate, String birthPlace,
            String pageUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(read(sourceFile(ctx), pageUrl(ctx)), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : le Président, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        President p = read(sourceFile(ctx), pageUrl(ctx));
        String id = new FtmDatasetBuilder(ID_PREFIX).buildEntityId(p.name());
        return List.of(new SourceRecord(id, "Person", p.name(), p.pageUrl()));
    }

    /** La personne, la fonction de Président et son mandat en cours. Séparé de {@link #parse} pour les tests. */
    void convert(President p, FtmDatasetBuilder dataset) {
        // ID = nom : la page ne donne pas d'identifiant, et il n'y a qu'un Président à la fois
        FtmEntity person = dataset.newEntity("Person", p.name());
        person.addValue("name", p.name());
        person.addValue("firstName", p.firstName());
        person.addValue("lastName", p.lastName());
        person.addValue("birthDate", p.birthDate());
        person.addValue("birthPlace", p.birthPlace());
        person.addValue("country", "at");
        person.addValue("nationality", "at");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "president");
        position.addValue("name", POSITION);
        position.addValue("country", "at");
        position.addValue("topics", "gov.head");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", p.name(), "president");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(occupancy);
    }

    static President read(Path htmlFile, String pageUrl) throws Exception {
        return read(Files.readString(htmlFile, StandardCharsets.UTF_8), pageUrl);
    }

    /**
     * Lit la biographie officielle.
     *
     * @throws IllegalStateException si la page ne décrit pas le Président de la République (page changée,
     *                               mauvaise adresse, page d'erreur)
     */
    static President read(String html, String pageUrl) {
        String text = Html.text(html.replaceAll("(?si)<(script|style)[^>]*>.*?</\\1>", " "));
        if (!TITLE.matcher(text).find()) {
            throw new IllegalStateException("La page ne mentionne pas le « Bundespräsident » : vérifiez "
                    + "l'adresse du récit de vie officiel sur bundespraesident.at dans la configuration du parser.");
        }
        Matcher birth = BIRTH.matcher(text);
        if (!birth.find()) {
            throw new IllegalStateException("Nom et naissance du Président introuvables (« Am … wird … in … geboren ») : "
                    + "la page a changé.");
        }
        Integer month = MONTHS.get(normalize(birth.group(2)));
        if (month == null) {
            throw new IllegalStateException("Mois de naissance inconnu : « " + birth.group(2) + " ».");
        }
        String name = birth.group(4).trim();
        String[] words = name.split("\\s+");
        String birthDate = birth.group(3) + "-" + String.format("%02d", month) + "-"
                + String.format("%02d", Integer.parseInt(birth.group(1)));
        return new President(name, words[0], words.length > 1 ? name.substring(name.indexOf(' ') + 1) : null,
                birthDate, birth.group(5).trim(), pageUrl);
    }

    /** « março » → « marco ». */
    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Adresse de la page : le paramètre {@code pageUrl} du parser, sinon l'adresse officielle. */
    private static String pageUrl(ParserContext ctx) {
        String url = ctx.parameters() == null ? null : ctx.parameters().get("pageUrl");
        return url == null || url.isBlank() ? DEFAULT_PAGE_URL : url.trim();
    }

    /**
     * Fichier source téléchargé par le core. Il est absent quand le parser est configuré en « Géré par le plugin »
     * ({@code PLUGIN_MANAGED}) : ce plugin ne télécharge rien lui-même, on explique alors quoi corriger.
     */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "la page téléchargée par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la biographie du Président sur bundespraesident.at.");
        }
        return ctx.inputFile();
    }
}
