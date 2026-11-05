package com.swiftcheck.aml.plugins.atpep.federalgovernment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <b>at_pep_federal_government</b> – Chancelier, vice-chancelier, ministres fédéraux et secrétaires d'État (catégorie
 * AT-a ; FM-GwG § 2 Z 6 lit. a ; liste notifiée à la Commission, JO C/2023/724).
 *
 * <p>Source : la page officielle « Bundesministerinnen und Bundesminister, Staatssekretärinnen und Staatssekretäre » du
 * Bundeskanzleramt
 * ({@code https://www.bundeskanzleramt.gv.at/bundeskanzleramt/die-bundesregierung/bundesministerinnen-und-bundesminister.html}).
 * Type {@code HTTP_FILE} : le core télécharge la page à l'adresse configurée dans le parser ; ce plugin lit seulement
 * {@code ctx.inputFile()}. Chaque membre est un bloc « titre {@code <h2>} avec le nom, suivi de la fonction dans le
 * paragraphe suivant » :
 * <pre>
 * &lt;h2&gt;Andreas Babler, &lt;abbr title="Master of Science"&gt;MSc&lt;/abbr&gt;&lt;/h2&gt; … &lt;p&gt;Vizekanzler,&lt;br /&gt;Bundesminister für …&lt;/p&gt;
 * </pre>
 * Les titres académiques ({@code <abbr>}) ne sont pas repris dans le nom ; un ancien nom (« Claudia Bauer (vormals
 * Plakolm) ») devient un alias. La page ne donne ni date de naissance ni date de prise de fonctions : les mandats sont
 * publiés en cours, sans date de début, et la personne est identifiée par son nom.
 *
 * <p>Quand un gouvernement est remplacé, la page change : les membres sortants disparaissent de la source et le core les
 * garde publiés pendant la durée de rétention du parser (12 mois).
 */
@Extension
public class AtPepFederalGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "at-pep-federal-government";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_PAGE_URL =
            "https://www.bundeskanzleramt.gv.at/bundeskanzleramt/die-bundesregierung/bundesministerinnen-und-bundesminister.html";

    private static final Pattern BLOCK = Pattern.compile(
            "(?s)<div class=\"richtext_output\"><h2>(.*?)</h2>.*?<div class=\"richtext_output\"><p>(.*?)</p>");
    private static final Pattern ABBR = Pattern.compile("(?s)<abbr[^>]*>.*?</abbr>");
    private static final Pattern FORMER_NAME = Pattern.compile("^(.*?)\\s*\\((?:vormals|geb\\.|geborene?)\\s+([^)]+)\\)\\s*$");
    private static final Pattern MEMBER_ROLE = Pattern.compile(
            "(?iu)^(Bundeskanzler(?:in)?|Vizekanzler(?:in)?|Bundesminister(?:in)?|Staatssekret[aä]r(?:in)?)\\b.*");

    /** Un membre du gouvernement, tel que lu sur la page. */
    record Member(String name, String firstName, String lastName, String formerName, String role, String pageUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<Member> members = read(sourceFile(ctx), pageUrl(ctx));
        convert(members, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        long secretaries = members.stream().filter(m -> m.role().startsWith("Staatssekret")).count();
        return dataset.toConversionResult().withMessage(String.format(
                "%d membres du gouvernement fédéral lus (dont %d secrétaires d'État). Source : Bundeskanzleramt.",
                members.size(), secretaries));
    }

    /** Test du parser : chaque membre, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        List<SourceRecord> records = new ArrayList<>();
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        for (Member m : read(sourceFile(ctx), pageUrl(ctx))) {
            records.add(new SourceRecord(ids.buildEntityId(m.name()), "Person", m.name(), m.pageUrl()));
        }
        return records;
    }

    /** Les personnes, les fonctions et les mandats en cours. Séparé de {@link #parse} pour les tests. */
    void convert(List<Member> members, FtmDatasetBuilder dataset) {
        for (Member m : members) {
            // ID = nom : la page ne donne pas d'identifiant, et un nom n'apparaît qu'une fois dans un gouvernement
            FtmEntity person = dataset.newEntity("Person", m.name());
            person.addValue("name", m.name());
            person.addValue("alias", m.formerName());
            person.addValue("firstName", m.firstName());
            person.addValue("lastName", m.lastName());
            person.addValue("country", "at");
            person.addValue("nationality", "at");
            person.addValue("position", m.role());
            person.addValue("sourceUrl", m.pageUrl());
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", m.role());
            position.addValue("name", m.role());
            position.addValue("country", "at");
            position.addValue("topics", m.role().startsWith("Bundeskanzler") ? "gov.head" : "gov.executive");
            position.addValue("topics", "gov.national");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", m.name(), m.role());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", m.pageUrl());
            dataset.addEntity(occupancy);
        }
    }

    static List<Member> read(Path htmlFile, String pageUrl) throws Exception {
        return read(Files.readString(htmlFile, StandardCharsets.UTF_8), pageUrl);
    }

    /**
     * Lit la liste officielle.
     *
     * @throws IllegalStateException si la page ne liste ni chancelier ni ministre (page changée, mauvaise adresse,
     *                               page d'erreur)
     */
    static List<Member> read(String html, String pageUrl) {
        List<Member> members = new ArrayList<>();
        Matcher block = BLOCK.matcher(html);
        while (block.find()) {
            String role = Html.text(block.group(2)).replaceAll("\\s+,", ",");
            if (!MEMBER_ROLE.matcher(role).matches()) {
                continue;                       // « Hinweise » et autres titres de la page
            }
            String heading = Html.text(ABBR.matcher(block.group(1)).replaceAll(" ")).replaceAll("\\s+,", ",");
            String name = heading.split(",")[0].trim();
            String formerName = null;
            Matcher former = FORMER_NAME.matcher(name);
            if (former.matches()) {
                name = former.group(1).trim();
                String[] words = name.split("\\s+");
                formerName = words[0] + " " + former.group(2).trim();
            }
            final String finalName = name;
            if (name.isBlank() || members.stream().anyMatch(m -> m.name().equals(finalName))) {
                continue;
            }
            String[] words = name.split("\\s+");
            String lastName = words.length > 1 ? words[words.length - 1] : null;
            members.add(new Member(name, words[0], lastName, formerName, role, pageUrl));
        }
        if (members.stream().noneMatch(m -> m.role().startsWith("Bundeskanzler") || m.role().startsWith("Bundesminister"))) {
            throw new IllegalStateException("Aucun chancelier ni ministre fédéral lu sur la page : vérifiez l'adresse "
                    + "de la liste officielle sur bundeskanzleramt.gv.at dans la configuration du parser, ou la page a changé.");
        }
        return members;
    }

    /** Adresse de la page : le paramètre {@code pageUrl} du parser, sinon l'adresse officielle. */
    private static String pageUrl(ParserContext ctx) {
        String url = ctx.parameters() == null ? null : ctx.parameters().get("pageUrl");
        return url == null || url.isBlank() ? DEFAULT_PAGE_URL : url.trim();
    }

    /**
     * Fichier source téléchargé par le core. Il est absent quand le parser est configuré en « Géré par le plugin »
     * ({@code PLUGIN_MANAGED}) : on explique alors quoi corriger.
     */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "la page téléchargée par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la liste du gouvernement sur bundeskanzleramt.gv.at.");
        }
        return ctx.inputFile();
    }
}
