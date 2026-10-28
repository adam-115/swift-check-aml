package com.swiftcheck.aml.plugins.frpep.eucommission;

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
 * <b>fr_pep_eu_commission</b> – membre français de la Commission européenne (catégorie FR-EU ; liste des fonctions
 * PEP des institutions de l'UE, JO C/2023/611).
 *
 * <p>Source : fiche officielle du commissaire sur commission.europa.eu (ex.
 * {@code …/college-commissioners/stephane-sejourne_fr}). Type {@code HTTP_FILE} : le core télécharge la page à
 * l'adresse configurée dans le parser ; ce plugin lit seulement {@code ctx.inputFile()}. La page donne :
 * <pre>
 * &lt;meta name="description" content="Stéphane Séjourné est le commissaire français chargé de …"&gt;
 * &lt;h1 class="ecl-page-header__title"&gt;&lt;span&gt;Stéphane Séjourné&lt;/span&gt;&lt;/h1&gt;
 * &lt;p class="ecl-page-header__description"&gt;VICE-PRÉSIDENT EXÉCUTIF (2024-2029) | Prospérité et stratégie industrielle
 * </pre>
 * La page du collège ne donne pas la nationalité des commissaires : c'est la fiche du commissaire français qui est
 * configurée, et sa description doit le dire (« commissaire français », « French », « from France »). Une autre
 * fiche est refusée, pour ne pas publier le mauvais commissaire.
 *
 * <p>À chaque nouvelle Commission, l'adresse de la fiche change : il faut la modifier dans l'écran « Parsers ».
 * Le commissaire sortant est alors gardé par le core pendant la durée de rétention (12 mois).
 */
@Extension
public class FrPepEuCommissionParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-eu-commission";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String POSITION = "Membre français de la Commission européenne";

    private static final Pattern DESCRIPTION = Pattern.compile(
            "(?i)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)[\"']");
    private static final Pattern OG_TITLE = Pattern.compile(
            "(?i)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)[\"']");
    private static final Pattern OG_URL = Pattern.compile(
            "(?i)<meta[^>]+property=[\"']og:url[\"'][^>]+content=[\"']([^\"']*)[\"']");
    private static final Pattern H1 = Pattern.compile("(?si)<h1[^>]*class=\"[^\"]*ecl-page-header__title[^\"]*\"[^>]*>(.*?)</h1>");
    /** « VICE-PRÉSIDENT EXÉCUTIF (2024-2029) | Prospérité et stratégie industrielle ». */
    private static final Pattern HEADER = Pattern.compile(
            "(?si)<p[^>]*class=\"[^\"]*ecl-page-header__description[^\"]*\"[^>]*>\\s*([^<(]+?)\\s*\\((\\d{4})\\s*[-–]\\s*(\\d{4})\\)\\s*\\|?\\s*([^<]*)");
    private static final Pattern FRENCH = Pattern.compile("(?iu)\\b(commissaire français|commissaire française|french|from france)\\b");

    /** Le commissaire, tel que lu sur sa fiche. */
    record Commissioner(String name, String firstName, String lastName, String role, String portfolio,
            String startYear, String endYear, String pageUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8)), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : le commissaire, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Commissioner c = read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8));
        return List.of(new SourceRecord(new FtmDatasetBuilder(ID_PREFIX).buildEntityId(c.name()), "Person", c.name(),
                c.pageUrl()));
    }

    /** La personne, la fonction et son mandat. Séparé de {@link #parse} pour les tests. */
    void convert(Commissioner c, FtmDatasetBuilder dataset) {
        // ID = nom : la page ne donne pas d'identifiant, et il n'y a qu'un commissaire français à la fois
        FtmEntity person = dataset.newEntity("Person", c.name());
        person.addValue("name", c.name());
        person.addValue("firstName", c.firstName());
        person.addValue("lastName", c.lastName());
        person.addValue("country", "fr");
        person.addValue("nationality", "fr");
        person.addValue("position", POSITION);
        person.addValue("position", c.portfolio() == null ? c.role() : c.role() + " – " + c.portfolio());
        person.addValue("sourceUrl", c.pageUrl());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "member");
        position.addValue("name", POSITION);
        position.addValue("country", "fr");                              // pays du titulaire, comme lu_pep_eu_commission
        position.addValue("organization", "Commission européenne");
        position.addValue("topics", "gov.igo");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", c.name(), "member", c.startYear());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", c.startYear());                     // la fiche ne donne que l'année
        occupancy.addValue("status", "current");
        occupancy.addValue("description", c.portfolio() == null ? c.role() : c.role() + " – " + c.portfolio());
        occupancy.addValue("sourceUrl", c.pageUrl());
        dataset.addEntity(occupancy);
    }

    /**
     * Lit la fiche du commissaire.
     *
     * @throws IllegalStateException si la fiche n'est pas celle d'un commissaire français, ou si son format a changé
     */
    static Commissioner read(String html) {
        String description = first(DESCRIPTION, html);
        if (description == null || !FRENCH.matcher(description).find()) {
            throw new IllegalStateException("La fiche ne présente pas un commissaire français (description : « "
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
                portfolio.isBlank() ? null : portfolio, header.group(2), header.group(3), first(OG_URL, html));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String first(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? Html.text(m.group(1)) : null;
    }

    /**
     * Fichier source téléchargé par le core. Il est absent quand le parser est configuré en « Géré par le plugin »
     * ({@code PLUGIN_MANAGED}) : ce plugin ne télécharge rien lui-même, on explique alors quoi corriger.
     */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "la page téléchargée par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la fiche du commissaire français.");
        }
        return ctx.inputFile();
    }
}
