package com.swiftcheck.aml.plugins.lupep.government;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <b>lu_pep_government</b> – membres du gouvernement du Luxembourg en fonction (catégorie a : Premier ministre,
 * ministres et secrétaires d'État, nommés selon l'article 76 de la Constitution).
 *
 * <p>Le gouvernement ne publie pas cette liste en open data : la source est la page officielle
 * gouvernement.lu/fr/gouvernement.html. Type {@code HTTP_FILE} : le core télécharge la page à l'adresse
 * configurée dans le parser ; ce plugin lit seulement {@code ctx.inputFile()} et écrit le NDJSON.
 *
 * <p>La page contient un lien par membre ; le nom de famille est écrit en majuscules :
 * <pre>{@code <a href="//gouvernement.lu/fr/gouvernement/luc-frieden.html" ...><span>Luc FRIEDEN</span></a>}</pre>
 * Si la page change de structure et qu'aucun membre n'est trouvé, l'exécution échoue au lieu de publier une
 * liste vide.
 */
@Extension
public class LuPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "lu-pep-government";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    /** Un lien vers la page d'un membre : groupe 1 = identifiant de la page, groupe 2 = nom affiché. */
    private static final Pattern MEMBER_LINK = Pattern.compile(
            "href=\"//gouvernement\\.lu/fr/gouvernement/([a-z0-9-]+)\\.html\"[^>]*>\\s*<span>\\s*([^<]+?)\\s*</span>");

    /** Lien du même menu qui n'est pas un membre en fonction. */
    private static final String FORMER_MEMBERS_PAGE = "anciens-membres-gouvernement";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(sourceFile(ctx), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : chaque membre de la page, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);   // uniquement pour calculer les ID
        List<SourceRecord> records = new ArrayList<>();
        readMembers(sourceFile(ctx)).forEach((memberPage, displayedName) -> records.add(new SourceRecord(
                ids.buildEntityId(memberPage), "Person", displayedName, "lien " + memberPage)));
        return records;
    }

    /** Lit la page et convertit chaque membre. Séparé de {@link #parse} pour être testé directement. */
    void convert(Path html, FtmDatasetBuilder dataset) throws Exception {
        readMembers(html).forEach((memberPage, displayedName) -> convertMember(memberPage, displayedName, dataset));
    }

    /** Un membre : la personne, la fonction de membre du gouvernement et son mandat en cours. */
    private static void convertMember(String memberPage, String displayedName, FtmDatasetBuilder dataset) {
        String[] name = splitName(displayedName);

        // ID = identifiant de sa page sur gouvernement.lu (ex. luc-frieden)
        FtmEntity person = dataset.newEntity("Person", memberPage);
        person.addValue("name", joinNonBlank(" ", name[0], name[1]));
        person.addValue("firstName", name[0]);
        person.addValue("lastName", name[1]);
        person.addValue("country", "lu");
        person.addValue("sourceUrl", "https://gouvernement.lu/fr/gouvernement/" + memberPage + ".html");
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "member");
        position.addValue("name", "Membre du gouvernement du Luxembourg");
        position.addValue("country", "lu");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", memberPage, "member");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        dataset.addEntity(occupancy);
    }

    /**
     * Les membres trouvés dans la page : identifiant de la page du membre → nom affiché.
     * Chaque membre apparaît dans plusieurs menus de la page : on le garde une fois.
     */
    private static Map<String, String> readMembers(Path html) throws Exception {
        Map<String, String> namesByPage = new LinkedHashMap<>();
        Matcher link = MEMBER_LINK.matcher(Files.readString(html, StandardCharsets.UTF_8));
        while (link.find()) {
            if (!FORMER_MEMBERS_PAGE.equals(link.group(1))) {
                namesByPage.putIfAbsent(link.group(1), link.group(2));
            }
        }
        if (namesByPage.isEmpty()) {
            throw new IllegalStateException("Aucun membre du gouvernement trouvé : la structure de la page a "
                    + "probablement changé.");
        }
        return namesByPage;
    }

    /**
     * Sépare « Luc FRIEDEN » en prénom « Luc » et nom « FRIEDEN » : les mots tout en majuscules forment le nom.
     *
     * @return {prénom, nom} ; le prénom vaut {@code null} si tous les mots sont en majuscules
     */
    static String[] splitName(String displayedName) {
        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();
        for (String word : displayedName.trim().split("\\s+")) {
            StringBuilder target = word.equals(word.toUpperCase()) ? last : first;
            if (target.length() > 0) {
                target.append(' ');
            }
            target.append(word);
        }
        return new String[] { first.length() == 0 ? null : first.toString(), last.toString() };
    }
    /**
     * Fichier source téléchargé par le core. Il est absent quand le parser est configuré en « Géré par le plugin »
     * ({@code PLUGIN_MANAGED}) : ce plugin ne télécharge rien lui-même, on explique alors quoi corriger.
     */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "un fichier téléchargé par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse du fichier source (voir le README des plugins).");
        }
        return ctx.inputFile();
    }
}
