package com.swiftcheck.aml.plugins.ptpep.president;

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
 * <b>pt_pep_president</b> – Président de la République portugaise (catégorie PT-a ; loi 83/2017, art. 2.º, n.º 1,
 * al. cc) ; liste notifiée à la Commission, JO C/2023/724).
 *
 * <p>Source : la biographie officielle du Président sur le site de la Présidence
 * ({@code https://www.presidencia.pt/presidente-da-republica/o-presidente/biografia/}). Type {@code HTTP_FILE} : le
 * core télécharge la page à l'adresse configurée dans le parser ; ce plugin lit seulement {@code ctx.inputFile()}.
 * La page commence par une phrase de la forme :
 * <pre>
 * António José Martins Seguro nasceu a 11 de março de 1962, em Penamacor, vila de que é Cidadão Honorário …
 * </pre>
 * et elle est vérifiée par le titre de la page (« Presidente da República »). La page ne donne pas la date de prise
 * de fonctions : le mandat est publié sans date de début. Aucune donnée familiale n'est reprise.
 *
 * <p>À chaque nouveau Président, la page est remplacée par la biographie du nouveau titulaire : le Président sortant
 * disparaît alors de la source et le core le garde publié pendant la durée de rétention du parser (12 mois).
 */
@Extension
public class PtPepPresidentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "pt-pep-president";
    static final String TOPIC = "role.pep";
    static final String POSITION = "Président de la République portugaise";
    static final String DEFAULT_PAGE_URL = "https://www.presidencia.pt/presidente-da-republica/o-presidente/biografia/";

    /** « <Nom> nasceu a 11 de março de 1962, em Penamacor, » (la première phrase de la biographie). */
    private static final Pattern BIRTH = Pattern.compile(
            "(?u)([\\p{Lu}][\\p{L}'’ -]+?) nasceu a (\\d{1,2}) de (\\p{L}+) de (\\d{4}), em ([\\p{Lu}][\\p{L}'’ -]+?)(?=[,.;(])");
    private static final Pattern TITLE = Pattern.compile("(?iu)Presidente da Rep[uú]blica");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janeiro", 1), Map.entry("fevereiro", 2), Map.entry("marco", 3), Map.entry("abril", 4),
            Map.entry("maio", 5), Map.entry("junho", 6), Map.entry("julho", 7), Map.entry("agosto", 8),
            Map.entry("setembro", 9), Map.entry("outubro", 10), Map.entry("novembro", 11), Map.entry("dezembro", 12));

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
        person.addValue("country", "pt");
        person.addValue("nationality", "pt");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "president");
        position.addValue("name", POSITION);
        position.addValue("country", "pt");
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
            throw new IllegalStateException("La page ne mentionne pas le « Presidente da República » : vérifiez "
                    + "l'adresse de la biographie officielle sur presidencia.pt dans la configuration du parser.");
        }
        Matcher birth = BIRTH.matcher(text);
        if (!birth.find()) {
            throw new IllegalStateException("Nom et naissance du Président introuvables (« … nasceu a … de … de … ») : "
                    + "la page a changé.");
        }
        Integer month = MONTHS.get(normalize(birth.group(3)));
        if (month == null) {
            throw new IllegalStateException("Mois de naissance inconnu : « " + birth.group(3) + " ».");
        }
        String name = birth.group(1).trim();
        String[] words = name.split("\\s+");
        String birthDate = birth.group(4) + "-" + String.format("%02d", month) + "-"
                + String.format("%02d", Integer.parseInt(birth.group(2)));
        return new President(name, words[0], words.length > 1 ? words[words.length - 1] : null, birthDate,
                birth.group(5).trim(), pageUrl);
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
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la biographie du Président sur presidencia.pt.");
        }
        return ctx.inputFile();
    }
}
