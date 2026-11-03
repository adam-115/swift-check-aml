package com.swiftcheck.aml.plugins.depep.president;

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
 * <b>de_pep_president</b> – Président fédéral de la République fédérale d'Allemagne (Bundespräsident ;
 * catégorie DE-a ; GwG § 1 (12) a) ; JO C/2023/724).
 *
 * <p>Source : biographie officielle du Président sur bundespraesident.de (ex.
 * {@code https://www.bundespraesident.de/DE/bundespraesident/frank-walter-steinmeier_node.html}). Type {@code HTTP_FILE}.
 */
@Extension
public class DePepPresidentParser implements Iparser {

    static final String ID_PREFIX = "de-pep-president";
    static final String TOPIC = "role.pep";
    private static final String POSITION = "Président fédéral (Bundespräsident)";

    private static final Pattern OG_TITLE = Pattern.compile("(?i)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)[\"']");
    private static final Pattern OG_URL = Pattern.compile("(?i)<meta[^>]+property=[\"']og:url[\"'][^>]+content=[\"']([^\"']*)[\"']");
    private static final Pattern H1 = Pattern.compile("(?si)<h1[^>]*class=\"[^\"]*c-intro-president__headline[^\"]*\"[^>]*>(.*?)</h1>");
    private static final Pattern PHOTO = Pattern.compile("(?si)<div[^>]*class=\"[^\"]*c-intro-president__media[^\"]*\"[^>]*>.*?<img[^>]+src=[\"']([^\"']+)[\"']");
    static final String PHOTO_LICENCE = "© Bundespräsidialamt";

    private static final Pattern BIRTH = Pattern.compile("(?u)geboren\\s+(?:am\\s+\\d{1,2}\\.\\s+\\p{L}+\\s+)?(\\d{4})\\s+in\\s+([\\p{L}' -]+?)(?=[,.;]| im Kreis)");
    private static final Pattern ELECTED = Pattern.compile("(?u)(\\d{4})\\s+zum\\s+(?:\\d+\\.)?\\s*Bundespräsidenten gewählt");

    record President(String name, String firstName, String lastName, String birthDate, String birthPlace,
            String startYear, String pageUrl, String photo) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8)), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        President p = read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8));
        String id = new FtmDatasetBuilder(ID_PREFIX).buildEntityId(p.name());
        return List.of(new SourceRecord(id, "Person", p.name(), p.pageUrl()));
    }

    void convert(President p, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", p.name());
        person.addValue("name", p.name());
        person.addValue("firstName", p.firstName());
        person.addValue("lastName", p.lastName());
        if (p.birthDate() != null) {
            person.addValue("birthDate", p.birthDate());
        }
        if (p.birthPlace() != null) {
            person.addValue("birthPlace", p.birthPlace());
        }
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(person);
        if (p.photo() != null) {
            dataset.addMedia(person.getId(), p.photo(), p.pageUrl(), PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", "president");
        position.addValue("name", POSITION);
        position.addValue("country", "de");
        position.addValue("organization", "Présidence fédérale");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.headofstate");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", p.name(), "president", p.startYear() != null ? p.startYear() : "current");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        if (p.startYear() != null) {
            occupancy.addValue("startDate", p.startYear());
        }
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(occupancy);
    }

    static President read(String html) {
        String name = first(H1, html);
        if (name == null || name.isBlank()) {
            name = first(OG_TITLE, html);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Nom du Président introuvable dans la page : format inattendu.");
        }
        String[] parts = name.split("\\s+", 2);
        String firstName = parts[0];
        String lastName = parts.length > 1 ? parts[1] : null;

        String birthDate = null;
        String birthPlace = null;
        Matcher mBirth = BIRTH.matcher(html);
        if (mBirth.find()) {
            birthDate = mBirth.group(1);
            birthPlace = Html.text(mBirth.group(2));
        }

        String startYear = null;
        Matcher mElected = ELECTED.matcher(html);
        if (mElected.find()) {
            startYear = mElected.group(1);
        }

        String pageUrl = first(OG_URL, html);
        if (pageUrl == null) {
            pageUrl = "https://www.bundespraesident.de";
        }

        String photo = first(PHOTO, html);
        if (photo != null && !photo.startsWith("http")) {
            photo = "https://www.bundespraesident.de" + (photo.startsWith("/") ? "" : "/") + photo;
        }

        return new President(name, firstName, lastName, birthDate, birthPlace, startYear, pageUrl, photo);
    }

    private static String first(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? Html.text(m.group(1)) : null;
    }

    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "la page téléchargée par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la page du Président fédéral.");
        }
        return ctx.inputFile();
    }
}

