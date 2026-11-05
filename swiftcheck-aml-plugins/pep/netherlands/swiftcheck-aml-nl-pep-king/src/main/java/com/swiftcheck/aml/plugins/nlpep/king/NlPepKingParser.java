package com.swiftcheck.aml.plugins.nlpep.king;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
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
 * <b>nl_pep_king</b> – Roi des Pays-Bas (chef d'État), personne politiquement exposée au sens de la Wwft.
 *
 * <p>Source : la page du Roi sur le site officiel de la Maison royale ({@code https://www.koninklijkhuis.nl/leden-koninklijk-huis/koning-willem-alexander}).
 * Type {@code HTTP_FILE} : le core télécharge la page ; ce plugin lit seulement {@code ctx.inputFile()}. La page donne :
 * <pre>
 * &lt;h1 class="nav-bar__page-title"&gt;Koning Willem-Alexander&lt;/h1&gt;
 * Op 30 april 2013 volgt Koning Willem-Alexander zijn moeder op als Koning der Nederlanden.
 * </pre>
 * Une page qui ne contient pas la phrase d'accession au trône (« volgt … op als Koning der Nederlanden ») est refusée, pour ne
 * pas publier un autre membre de la famille comme chef d'État. La page ne donne ni date ni lieu de naissance.
 *
 * <p>Au changement de règne, l'adresse change : il faut la modifier dans l'écran « Parsers » ; le Roi sortant reste publié
 * pendant la durée de rétention du parser (12 mois).
 */
@Extension
public class NlPepKingParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "nl-pep-king";
    static final String TOPIC = "role.pep";
    static final String POSITION = "Roi des Pays-Bas (Koning der Nederlanden)";

    private static final Pattern NAME = Pattern.compile("(?is)<h1[^>]*class=\"[^\"]*page-title[^\"]*\"[^>]*>(.*?)</h1>");
    private static final Pattern ACCESSION = Pattern.compile(
            "Op (\\d{1,2}) ([a-zé]+) (\\d{4}) volgt (?:Koning|Koningin) ([^ ]+) .{0,60}? op als (?:Koning|Koningin) der Nederlanden");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("januari", 1), Map.entry("februari", 2),
            Map.entry("maart", 3), Map.entry("april", 4), Map.entry("mei", 5), Map.entry("juni", 6), Map.entry("juli", 7),
            Map.entry("augustus", 8), Map.entry("september", 9), Map.entry("oktober", 10), Map.entry("november", 11),
            Map.entry("december", 12));

    /** Le chef d'État, tel que lu sur la page. */
    record King(String name, String title, LocalDate accession, String pageUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8), pageUrl(ctx)), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : le chef d'État, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        King k = read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8), pageUrl(ctx));
        return List.of(new SourceRecord(new FtmDatasetBuilder(ID_PREFIX).buildEntityId(k.name()), "Person", k.name(), k.pageUrl()));
    }

    /** La personne, la fonction et le mandat en cours. Séparé de {@link #parse} pour les tests. */
    void convert(King k, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", k.name());
        person.addValue("name", k.name());
        person.addValue("alias", k.title() + " " + k.name());
        person.addValue("firstName", k.name());
        person.addValue("country", "nl");
        person.addValue("nationality", "nl");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", k.pageUrl());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "head-of-state");
        position.addValue("name", POSITION);
        position.addValue("country", "nl");
        position.addValue("topics", "gov.head");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", k.name(), "head-of-state");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", k.accession().toString());
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", k.pageUrl());
        dataset.addEntity(occupancy);
    }

    /**
     * Lit la page du Roi.
     *
     * @throws IllegalStateException si la page ne contient pas la phrase d'accession au trône
     */
    static King read(String html, String pageUrl) {
        Matcher name = NAME.matcher(html);
        String text = Html.text(html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " "));
        Matcher accession = ACCESSION.matcher(text);
        if (!name.find() || !accession.find() || !MONTHS.containsKey(accession.group(2))) {
            throw new IllegalStateException("La page n'est pas celle du chef d'État : phrase « Op … volgt Koning … op als Koning der "
                    + "Nederlanden » introuvable. Vérifiez l'adresse de la page du Roi sur koninklijkhuis.nl dans la configuration du parser.");
        }
        String full = Html.text(name.group(1));                     // « Koning Willem-Alexander »
        String[] words = full.split("\\s+", 2);
        String title = words.length > 1 ? words[0] : "Koning";
        String personName = words.length > 1 ? words[1] : full;
        LocalDate date = LocalDate.of(Integer.parseInt(accession.group(3)), MONTHS.get(accession.group(2)), Integer.parseInt(accession.group(1)));
        return new King(personName, title, date, pageUrl);
    }

    private static String pageUrl(ParserContext ctx) {
        String url = ctx.parameters() == null ? null : ctx.parameters().get("pageUrl");
        return url == null || url.isBlank() ? "https://www.koninklijkhuis.nl/leden-koninklijk-huis/koning-willem-alexander" : url.trim();
    }

    /** Fichier source téléchargé par le core, absent si le parser n'est pas de type « Fichier HTTP ». */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit la page "
                    + "téléchargée par le core. Choisissez le type « Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la page du Roi "
                    + "sur koninklijkhuis.nl.");
        }
        return ctx.inputFile();
    }
}
