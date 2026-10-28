package com.swiftcheck.aml.plugins.frpep.president;

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
 * <b>fr_pep_president</b> – Président de la République française (catégorie FR-a, « chefs d'État » ; CMF,
 * art. R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 a)).
 *
 * <p>Source : biographie officielle du Président sur elysee.fr (ex. {@code https://www.elysee.fr/emmanuel-macron}).
 * Type {@code HTTP_FILE} : le core télécharge la page à l'adresse configurée dans le parser ; ce plugin lit
 * seulement {@code ctx.inputFile()}. La page donne :
 * <pre>
 * &lt;meta property="og:title" content="Emmanuel Macron"&gt;
 * &lt;meta property="og:description" content="… président de la République depuis 2017, réélu … le 24 avril 2022."&gt;
 * … Né en décembre 1977 à Amiens, dans la Somme, Emmanuel Macron a étudié …
 * </pre>
 * La date de naissance n'est donnée qu'au mois près ; elle est gardée telle quelle ({@code 1977-12}).
 *
 * <p>À chaque nouveau Président, l'adresse de la page change : il faut la modifier dans l'écran « Parsers ».
 * Le Président sortant disparaît alors de la source ; le core le garde publié pendant la durée de rétention
 * du parser (12 mois en France, R. 561-18).
 */
@Extension
public class FrPepPresidentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-president";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final Pattern OG_TITLE = meta("og:title");
    private static final Pattern OG_URL = meta("og:url");
    private static final Pattern H1 = Pattern.compile("(?si)<h1[^>]*>(.*?)</h1>");
    private static final Pattern PRESIDENT = Pattern.compile("(?iu)présidente? de la République");
    /** « président de la République depuis 2017 », dans le texte ou la description (og:description). */
    private static final Pattern SINCE = Pattern.compile("(?iu)présidente? de la République depuis (\\d{4})");
    /** « Né en décembre 1977 à Amiens, » ou « Née le 1er mars 1960 à Lyon. ». */
    private static final Pattern BIRTH = Pattern.compile(
            "(?u)Né(?:e)? (?:le (\\d{1,2})(?:er)? |en )(\\p{L}+) (\\d{4})(?: à ([\\p{Lu}][\\p{L}' -]+?)(?=[,.;(]| dans | en ))?");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janvier", 1), Map.entry("fevrier", 2), Map.entry("mars", 3), Map.entry("avril", 4),
            Map.entry("mai", 5), Map.entry("juin", 6), Map.entry("juillet", 7), Map.entry("aout", 8),
            Map.entry("septembre", 9), Map.entry("octobre", 10), Map.entry("novembre", 11), Map.entry("decembre", 12));

    /** Le Président, tel que lu sur la page. */
    record President(String name, String firstName, String lastName, String birthDate, String birthPlace,
            String startYear, String pageUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(read(sourceFile(ctx)), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : le Président, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        President p = read(sourceFile(ctx));
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
        person.addValue("country", "fr");
        person.addValue("nationality", "fr");
        person.addValue("position", "Président de la République française");
        person.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "president");
        position.addValue("name", "Président de la République française");
        position.addValue("country", "fr");
        position.addValue("topics", "gov.head");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", p.name(), "president");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", p.startYear());
        occupancy.addValue("status", "current");
        dataset.addEntity(occupancy);
    }

    static President read(Path htmlFile) throws Exception {
        return read(Files.readString(htmlFile, StandardCharsets.UTF_8));
    }

    /**
     * Lit la biographie officielle.
     *
     * @throws IllegalStateException si la page ne décrit pas le Président de la République (page changée,
     *                               mauvaise adresse, page d'erreur)
     */
    static President read(String html) {
        String text = Html.text(html.replaceAll("(?si)<(script|style)[^>]*>.*?</\\1>", " "));
        if (!PRESIDENT.matcher(text).find()) {
            throw new IllegalStateException("La page ne mentionne pas le « président de la République » : "
                    + "vérifiez l'adresse de la biographie officielle sur elysee.fr dans la configuration du parser.");
        }
        String name = first(OG_TITLE, html);
        if (name == null) {
            name = first(H1, html);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Nom du Président introuvable (ni og:title ni titre h1).");
        }
        String[] parts = name.split("\\s+", 2);

        String birthDate = null;
        String birthPlace = null;
        Matcher birth = BIRTH.matcher(text);
        if (birth.find()) {
            Integer month = MONTHS.get(normalize(birth.group(2)));
            if (month != null) {
                birthDate = birth.group(3) + "-" + String.format("%02d", month)
                        + (birth.group(1) == null ? "" : "-" + String.format("%02d", Integer.parseInt(birth.group(1))));
            }
            birthPlace = birth.group(4) == null ? null : birth.group(4).trim();
        }
        Matcher since = SINCE.matcher(text + " " + Html.decode(html));
        return new President(name, parts[0], parts.length > 1 ? parts[1] : null, birthDate, birthPlace,
                since.find() ? since.group(1) : null, first(OG_URL, html));
    }

    private static Pattern meta(String property) {
        return Pattern.compile("(?i)<meta[^>]+property=[\"']" + Pattern.quote(property)
                + "[\"'][^>]+content=[\"']([^\"']*)[\"']");
    }

    private static String first(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? Html.text(m.group(1)) : null;
    }

    /** « Décembre » → « decembre ». */
    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Fichier source téléchargé par le core. Il est absent quand le parser est configuré en « Géré par le plugin »
     * ({@code PLUGIN_MANAGED}) : ce plugin ne télécharge rien lui-même, on explique alors quoi corriger.
     */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "la page téléchargée par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la biographie du Président sur elysee.fr.");
        }
        return ctx.inputFile();
    }
}
