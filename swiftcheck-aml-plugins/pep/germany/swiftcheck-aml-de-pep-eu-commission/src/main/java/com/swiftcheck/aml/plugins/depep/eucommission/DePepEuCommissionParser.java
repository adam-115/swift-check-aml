package com.swiftcheck.aml.plugins.depep.eucommission;

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
 * <b>de_pep_eu_commission</b> – membre allemand de la Commission européenne (Présidente de la Commission ;
 * catégorie DE-EU ; GwG § 1 (12) ; liste des fonctions PEP de la Commission, JO C/2023/724).
 *
 * <p>Source : fiche officielle de la présidente sur commission.europa.eu
 * (ex. {@code …/college-commissioners/ursula-von-der-leyen_fr}). Type {@code HTTP_FILE}.
 */
@Extension
public class DePepEuCommissionParser implements Iparser {

    static final String ID_PREFIX = "de-pep-eu-commission";
    static final String TOPIC = "role.pep";
    private static final String POSITION = "Membre allemand de la Commission européenne (Présidente)";

    private static final Pattern DESCRIPTION = Pattern.compile(
            "(?i)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)[\"']");
    private static final Pattern OG_TITLE = Pattern.compile(
            "(?i)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)[\"']");
    private static final Pattern OG_URL = Pattern.compile(
            "(?i)<meta[^>]+property=[\"']og:url[\"'][^>]+content=[\"']([^\"']*)[\"']");
    private static final Pattern OG_IMAGE = Pattern.compile(
            "(?i)<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']*)[\"']");
    static final String PHOTO_LICENCE = "© Union européenne";
    private static final Pattern H1 = Pattern.compile("(?si)<h1[^>]*class=\"[^\"]*ecl-page-header__title[^\"]*\"[^>]*>(.*?)</h1>");

    private static final Pattern HEADER = Pattern.compile(
            "(?si)<p[^>]*class=\"[^\"]*ecl-page-header__description[^\"]*\"[^>]*>\\s*([^<(]+?)\\s*\\((\\d{4})\\s*[-–]\\s*(\\d{4})\\)\\s*\\|?\\s*([^<]*)");

    private static final Pattern GERMAN = Pattern.compile("(?iu)\\b(allemande?|german|deutsche?|von der leyen|présidente|president|präsidentin)\\b");

    record Commissioner(String name, String firstName, String lastName, String role, String portfolio,
            String startYear, String endYear, String pageUrl, String photo) {
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
        Commissioner c = read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8));
        return List.of(new SourceRecord(new FtmDatasetBuilder(ID_PREFIX).buildEntityId(c.name()), "Person", c.name(),
                c.pageUrl()));
    }

    void convert(Commissioner c, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", c.name());
        person.addValue("name", c.name());
        person.addValue("firstName", c.firstName());
        person.addValue("lastName", c.lastName());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", POSITION);
        person.addValue("position", c.portfolio() == null ? c.role() : c.role() + " – " + c.portfolio());
        person.addValue("sourceUrl", c.pageUrl());
        dataset.addEntity(person);
        if (c.photo() != null) {
            dataset.addMedia(person.getId(), c.photo(), c.pageUrl(), PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", "president");
        position.addValue("name", POSITION);
        position.addValue("country", "de");
        position.addValue("organization", "Commission européenne");
        position.addValue("topics", "gov.igo");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", c.name(), "president", c.startYear());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", c.startYear());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", c.portfolio() == null ? c.role() : c.role() + " – " + c.portfolio());
        occupancy.addValue("sourceUrl", c.pageUrl());
        dataset.addEntity(occupancy);
    }

    static Commissioner read(String html) {
        String description = first(DESCRIPTION, html);
        if (description == null || !GERMAN.matcher(description).find()) {
            throw new IllegalStateException("La fiche ne présente pas un commissaire allemand (description : « "
                    + description + " ») : vérifiez l'adresse de la fiche dans la configuration du parser.");
        }
        String name = first(H1, html);
        if (name == null || name.isBlank()) {
            name = first(OG_TITLE, html);
        }
        Matcher header = HEADER.matcher(html);
        if (name == null || name.isBlank() || !header.find()) {
            throw new IllegalStateException("Nom ou fonction du commissaire introuvable : la page a peut-être changé.");
        }
        String[] parts = name.split("\\s+", 2);
        String role = capitalize(Html.text(header.group(1)).toLowerCase());
        String portfolio = Html.text(header.group(4));
        return new Commissioner(name, parts[0], parts.length > 1 ? parts[1] : null, role,
                portfolio.isBlank() ? null : portfolio, header.group(2), header.group(3), first(OG_URL, html),
                https(first(OG_IMAGE, html)));
    }

    private static String https(String url) {
        return url != null && url.startsWith("https://") ? url : null;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String first(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? Html.text(m.group(1)) : null;
    }

    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "la page téléchargée par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la fiche de la présidente de la Commission.");
        }
        return ctx.inputFile();
    }
}

