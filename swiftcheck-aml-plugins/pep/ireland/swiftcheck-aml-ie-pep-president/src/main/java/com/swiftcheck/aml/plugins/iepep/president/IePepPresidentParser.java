package com.swiftcheck.aml.plugins.iepep.president;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * <b>ie_pep_president</b> – Président d'Irlande (Uachtarán na hÉireann), personne politiquement exposée au sens de
 * l'article 37 du Criminal Justice (Money Laundering and Terrorist Financing) Act 2010 (chef d'État).
 *
 * <p>Source : la page du Président en fonction sur le site officiel {@code president.ie}
 * (ex. {@code https://president.ie/the-president/catherine-connolly}). Type {@code HTTP_FILE} : le core télécharge la
 * page ; ce plugin lit seulement {@code ctx.inputFile()}. La page donne :
 * <pre>
 * &lt;h1 class="c-hero__title"&gt; Catherine Connolly &lt;/h1&gt;
 * &lt;div class="c-hero__text"&gt;2025 - Present Day&lt;/div&gt;
 * </pre>
 * « Present Day » signale le Président en fonction ; une page d'ancien Président (« 2011 - 2025 ») est refusée, pour ne
 * pas publier le mauvais titulaire comme actuel. La page ne donne ni date ni lieu de naissance : le mandat est publié
 * avec l'année de début seulement.
 *
 * <p>À chaque nouveau Président, l'adresse de la page change : il faut la modifier dans l'écran « Parsers ». Le sortant
 * est alors gardé par le core pendant la durée de rétention du parser (12 mois).
 */
@Extension
public class IePepPresidentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ie-pep-president";
    static final String TOPIC = "role.pep";
    static final String POSITION = "Président d'Irlande (Uachtarán na hÉireann)";

    private static final Pattern NAME = Pattern.compile("(?is)<h1[^>]*class=\"[^\"]*c-hero__title[^\"]*\"[^>]*>(.*?)</h1>");
    private static final Pattern PERIOD = Pattern.compile(
            "(?is)<div[^>]*class=\"[^\"]*c-hero__text[^\"]*\"[^>]*>\\s*(\\d{4})\\s*[-–]\\s*([^<]*?)\\s*</div>");
    private static final Pattern IN_OFFICE = Pattern.compile("(?iu)present day|présent|present");

    /** Le Président, tel que lu sur la page. */
    record President(String name, String firstName, String lastName, String startYear, String pageUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8), pageUrl(ctx)), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : le Président, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        President p = read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8), pageUrl(ctx));
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
        person.addValue("country", "ie");
        person.addValue("nationality", "ie");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "president");
        position.addValue("name", POSITION);
        position.addValue("country", "ie");
        position.addValue("topics", "gov.head");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", p.name(), "president");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", p.startYear());
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(occupancy);
    }

    /**
     * Lit la page du Président.
     *
     * @throws IllegalStateException si la page n'est pas celle du Président en fonction (ancien Président, mauvaise
     *                               adresse, page changée)
     */
    static President read(String html, String pageUrl) {
        Matcher name = NAME.matcher(html);
        Matcher period = PERIOD.matcher(html);
        if (!name.find() || !period.find()) {
            throw new IllegalStateException("Nom ou période du Président introuvables (h1 « c-hero__title », texte « 2025 - "
                    + "Present Day ») : la page a peut-être changé ou n'est pas celle d'un Président.");
        }
        if (!IN_OFFICE.matcher(period.group(2)).find()) {
            throw new IllegalStateException("La page est celle d'un ancien Président (« " + period.group(1) + " - "
                    + period.group(2) + " ») : indiquez l'adresse de la page du Président en fonction (menu « President in Office » "
                    + "de president.ie).");
        }
        String full = Html.decode(Html.text(name.group(1))).trim();
        String[] words = full.split("\\s+");
        return new President(full, words[0], words.length > 1 ? full.substring(full.indexOf(' ') + 1) : null,
                period.group(1), pageUrl);
    }

    private static String pageUrl(ParserContext ctx) {
        String url = ctx.parameters() == null ? null : ctx.parameters().get("pageUrl");
        return url == null || url.isBlank() ? "https://president.ie/" : url.trim();
    }

    /** Fichier source téléchargé par le core, absent si le parser n'est pas de type « Fichier HTTP ». */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "la page téléchargée par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la page du Président sur president.ie.");
        }
        return ctx.inputFile();
    }
}
