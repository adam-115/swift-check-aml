package com.swiftcheck.aml.plugins.itpep.president;

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
 * <b>it_pep_president</b> – Président de la République italienne (catégorie IT-1.1 ; D.Lgs. 231/2007 art. 1 c.2 lett. dd) point 1.1 ; liste notifiée à
 * la Commission, JO C/2023/724).
 *
 * <p>Source : le biographie officielle du Président sur le site de la Présidence de la République
 * ({@code https://www.quirinale.it/page/biografia}). Type
 * {@code HTTP_FILE} : le core télécharge la page à l'adresse configurée dans le parser ; ce plugin lit seulement
 * {@code ctx.inputFile()}. La page contient une phrase de la forme :
 * <pre>
 * E' nato a Palermo il 23 luglio 1941. …
 * </pre>
 * et elle est vérifiée par la présence du mot « Presidente della Repubblica ». La page ne donne pas une date de prise de
 * fonctions exploitable : le mandat est publié sans date de début. Aucune donnée familiale n'est reprise.
 *
 * <p>À chaque nouveau Président, la page est remplacée par la biographie du nouveau titulaire : le Président sortant
 * disparaît alors de la source et le core le garde publié pendant la durée de rétention du parser (12 mois).
 */
@Extension
public class ItPepPresidentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "it-pep-president";
    static final String TOPIC = "role.pep";
    static final String POSITION = "Président de la République italienne";
    static final String DEFAULT_PAGE_URL = "https://www.quirinale.it/page/biografia";

    /** « E' nato a Palermo il 23 luglio 1941. » (biographie sur le site du Quirinale). */
    private static final Pattern BIRTH = Pattern.compile(
            "(?u)\\bnato a ([\\p{Lu}][\\p{L}'’ -]+?) il (\\d{1,2})(?:º|°|\\u00ba)? (\\p{L}+) (\\d{4})");
    /** Le titre de la page : « La biografia del Presidente Sergio Mattarella ». */
    private static final Pattern NAME = Pattern.compile(
            "(?iu)<title>\\s*La biografia del Presidente ([^<]+?)\\s*(?:\\||-|<)");
    private static final Pattern TITLE = Pattern.compile("(?iu)Presidente della Repubblica|biografia del Presidente");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("gennaio", 1), Map.entry("febbraio", 2), Map.entry("marzo", 3), Map.entry("aprile", 4),
            Map.entry("maggio", 5), Map.entry("giugno", 6), Map.entry("luglio", 7), Map.entry("agosto", 8),
            Map.entry("settembre", 9), Map.entry("ottobre", 10), Map.entry("novembre", 11), Map.entry("dicembre", 12));

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
        person.addValue("country", "it");
        person.addValue("nationality", "it");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "president");
        position.addValue("name", POSITION);
        position.addValue("country", "it");
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
        Matcher title = NAME.matcher(html);
        String text = Html.text(html.replaceAll("(?si)<(script|style)[^>]*>.*?</\\1>", " "));
        if (!title.find() || !TITLE.matcher(text).find()) {
            throw new IllegalStateException("La page n'est pas la biographie du « Presidente della Repubblica » : vérifiez "
                    + "l'adresse de la biographie officielle sur quirinale.it dans la configuration du parser.");
        }
        Matcher birth = BIRTH.matcher(text);
        if (!birth.find()) {
            throw new IllegalStateException("Naissance du Président introuvable (« nato a … il … ») : la page a changé.");
        }
        Integer month = MONTHS.get(normalize(birth.group(3)));
        if (month == null) {
            throw new IllegalStateException("Mois de naissance inconnu : « " + birth.group(3) + " ».");
        }
        String name = Html.decode(title.group(1)).trim();
        String[] words = name.split("\\s+");
        String birthDate = birth.group(4) + "-" + String.format("%02d", month) + "-"
                + String.format("%02d", Integer.parseInt(birth.group(2)));
        return new President(name, words[0], words.length > 1 ? name.substring(name.indexOf(' ') + 1) : null,
                birthDate, birth.group(1).trim(), pageUrl);
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
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la biographie du Président sur quirinale.it.");
        }
        return ctx.inputFile();
    }
}
