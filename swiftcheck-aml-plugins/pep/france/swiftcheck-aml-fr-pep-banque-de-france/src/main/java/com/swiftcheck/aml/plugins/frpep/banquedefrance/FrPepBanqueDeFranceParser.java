package com.swiftcheck.aml.plugins.frpep.banquedefrance;

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
 * <b>fr_pep_banque_de_france</b> – membres du Conseil général de la Banque de France (catégorie FR-e ; CMF,
 * art. L. 142-7 et R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 e)).
 *
 * <p>Source : page « Gouvernance et comités consultatifs » de banque-france.fr. Type {@code HTTP_FILE} : le core
 * télécharge la page ; ce plugin lit seulement {@code ctx.inputFile()}. La section du Conseil général :
 * <pre>
 * &lt;h3&gt;Le Conseil général&lt;/h3&gt; …
 * &lt;h4&gt;Membres&lt;/h4&gt;
 * &lt;p&gt;Emmanuel Moulin, gouverneur, président du Conseil général&lt;br&gt;
 * Denis Beau, premier sous-gouverneur&lt;br&gt; … Anne-Laure Delatte&lt;br&gt; …&lt;/p&gt;
 * &lt;h4&gt;Censeur&lt;/h4&gt; …
 * </pre>
 * Seule la liste « Membres » est reprise : le censeur et son suppléant assistent aux séances sans en être membres.
 * La page ne donne ni date de naissance ni date de nomination.
 */
@Extension
public class FrPepBanqueDeFranceParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-banque-de-france";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String POSITION = "Membre du Conseil général de la Banque de France";

    /** Liste « Membres » de la section « Le Conseil général », jusqu'à la fin du paragraphe. */
    private static final Pattern MEMBERS = Pattern.compile(
            "(?si)<h3>\\s*Le Conseil général\\s*</h3>.*?<h4>\\s*Membres\\s*</h4>\\s*<p>(.*?)</p>");

    /** Un membre : nom et, s'il est indiqué, son rôle (« gouverneur, président du Conseil général »). */
    record Member(String name, String role) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<Member> members = members(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8));
        convert(members, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(members.size() + " membres du Conseil général de la Banque de France.");
    }

    /** Test du parser : chaque membre, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);   // uniquement pour calculer les ID
        List<SourceRecord> records = new ArrayList<>();
        for (Member m : members(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8))) {
            records.add(new SourceRecord(ids.buildEntityId(m.name()), "Person", m.name(), "Conseil général"));
        }
        return records;
    }

    /**
     * Membres du Conseil général.
     *
     * @throws IllegalStateException si la section n'est pas trouvée ou ne contient aucun nom (page changée)
     */
    static List<Member> members(String html) {
        Matcher m = MEMBERS.matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("Section « Le Conseil général » / « Membres » introuvable : "
                    + "vérifiez l'adresse de la page ou son format.");
        }
        List<Member> members = new ArrayList<>();
        for (String line : m.group(1).split("(?i)<br\\s*/?>")) {
            String text = Html.text(line);
            if (text.isEmpty()) {
                continue;
            }
            int comma = text.indexOf(',');
            members.add(comma < 0 ? new Member(text, null)
                    : new Member(text.substring(0, comma).trim(), text.substring(comma + 1).trim()));
        }
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre dans la section « Le Conseil général ».");
        }
        return members;
    }

    void convert(List<Member> members, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", "general-council");
        position.addValue("name", POSITION);
        position.addValue("country", "fr");
        position.addValue("organization", "Banque de France");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.financial");
        dataset.addEntity(position);

        for (Member m : members) {
            // ID = nom : la page ne donne pas d'identifiant
            FtmEntity person = dataset.newEntity("Person", m.name());
            String[] parts = m.name().split("\\s+", 2);
            person.addValue("name", m.name());
            person.addValue("firstName", parts[0]);
            person.addValue("lastName", parts.length > 1 ? parts[1] : null);
            person.addValue("country", "fr");
            person.addValue("position", POSITION);
            if (m.role() != null) {
                person.addValue("position", capitalize(m.role()) + " (Banque de France)");
            }
            dataset.addEntity(person);

            FtmEntity occupancy = dataset.newEntity("Occupancy", m.name(), "general-council");
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", m.role());
            dataset.addEntity(occupancy);
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Fichier source téléchargé par le core. Il est absent quand le parser est configuré en « Géré par le plugin »
     * ({@code PLUGIN_MANAGED}) : ce plugin ne télécharge rien lui-même, on explique alors quoi corriger.
     */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "la page téléchargée par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de la page de gouvernance de la Banque de France.");
        }
        return ctx.inputFile();
    }
}
