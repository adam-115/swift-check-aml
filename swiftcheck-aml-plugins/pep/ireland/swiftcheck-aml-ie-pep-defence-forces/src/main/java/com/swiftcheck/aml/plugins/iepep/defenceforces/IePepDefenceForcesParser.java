package com.swiftcheck.aml.plugins.iepep.defenceforces;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <b>ie_pep_defence_forces</b> – officiers généraux et amiraux des Defence Forces irlandaises (officiers de haut rang des
 * forces armées, article 37 du Criminal Justice Act 2010).
 *
 * <p>Source : la page « Designated Public Officials » du site officiel des Defence Forces, publiée au titre de la loi sur le
 * lobbying ({@code https://www.military.ie/en/public-information/designated-public-officials/}). Type {@code HTTP_FILE} :
 * le core télécharge la page ; ce plugin lit seulement {@code ctx.inputFile()}. La page contient un tableau
 * {@code RANK | NAME | APPT} :
 * <pre>
 * Lieutenant General | Rossa Mulcahy | Chief of Staff
 * Brigadier General  | Neil Nolan    | Assistant Chief of Staff
 * Assistant Secretary | Brian Molloy | Head of Strategic Transformation, Strategic Transformation Division
 * </pre>
 * Seuls les grades d'officier général ou d'amiral sont repris ({@code General}, {@code Commodore}, {@code Admiral}) ; les
 * « Assistant Secretary » sont des fonctionnaires civils du ministère et ne sont pas des officiers. La page ne donne ni dates ni
 * naissance.
 */
@Extension
public class IePepDefenceForcesParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ie-pep-defence-forces";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<td[^>]*>(.*?)</td>");
    private static final Pattern GENERAL_OFFICER = Pattern.compile("(?i)\\b(general|commodore|admiral)\\b");

    /** Un officier lu dans le tableau. */
    record Officer(String rank, String name, String appointment) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8)), dataset, pageUrl(ctx));
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : chaque officier, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder builder = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (Officer o : read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8))) {
            records.add(new SourceRecord(builder.buildEntityId(o.name()), "Person", o.name(), pageUrl(ctx)));
        }
        return records;
    }

    /** Lit les lignes {@code RANK | NAME | APPT} dont le grade est celui d'un officier général ou amiral. */
    static List<Officer> read(String html) {
        List<Officer> officers = new ArrayList<>();
        Matcher row = ROW.matcher(html);
        while (row.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(row.group(1));
            while (cell.find()) {
                cells.add(Html.text(cell.group(1)));
            }
            if (cells.size() < 3 || cells.get(1).isBlank() || !GENERAL_OFFICER.matcher(cells.get(0)).find()) {
                continue;                                       // en-tête, « Assistant Secretary », lignes vides
            }
            officers.add(new Officer(cells.get(0), cells.get(1), cells.get(2)));
        }
        if (officers.isEmpty()) {
            throw new IllegalStateException("Aucun officier général lu (tableau RANK / NAME / APPT) : la page a peut-être changé.");
        }
        return officers;
    }

    /** Écrit les officiers. Séparé de {@link #parse} pour les tests. */
    void convert(List<Officer> officers, FtmDatasetBuilder dataset, String pageUrl) {
        for (Officer o : officers) {
            String[] words = o.name().split("\\s+");
            FtmEntity person = dataset.newEntity("Person", o.name());
            person.addValue("name", o.name());
            person.addValue("firstName", words[0]);
            person.addValue("lastName", words.length > 1 ? o.name().substring(o.name().indexOf(' ') + 1) : null);
            person.addValue("country", "ie");
            person.addValue("position", o.appointment());
            person.addValue("sourceUrl", pageUrl);
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", o.appointment());
            position.addValue("name", o.appointment());
            position.addValue("country", "ie");
            position.addValue("organization", "Defence Forces");
            position.addValue("topics", "gov.security");
            position.addValue("topics", "gov.national");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", o.name(), o.appointment());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", "Grade : " + o.rank());
            occupancy.addValue("sourceUrl", pageUrl);
            dataset.addEntity(occupancy);
        }
    }

    private static String pageUrl(ParserContext ctx) {
        String url = ctx.parameters() == null ? null : ctx.parameters().get("pageUrl");
        return url == null || url.isBlank() ? "https://www.military.ie/en/public-information/designated-public-officials/" : url.trim();
    }

    /** Fichier source téléchargé par le core, absent si le parser n'est pas de type « Fichier HTTP ». */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit la page "
                    + "téléchargée par le core. Choisissez le type « Fichier HTTP » (HTTP_FILE) avec l'adresse "
                    + "https://www.military.ie/en/public-information/designated-public-officials/.");
        }
        return ctx.inputFile();
    }
}
