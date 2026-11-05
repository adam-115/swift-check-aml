package com.swiftcheck.aml.plugins.iepep.centralbank;

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
 * <b>ie_pep_central_bank</b> – membres de la Commission de la Banque centrale d'Irlande (Governor, Deputy Governors,
 * Secretary General du Department of Finance et membres nommés), organe de direction d'une banque centrale au sens de
 * l'article 37 du Criminal Justice Act 2010.
 *
 * <p>Source : la page officielle « The Commission » ({@code https://www.centralbank.ie/about/who-we-are/the-commission}).
 * Type {@code HTTP_FILE} : le core télécharge la page ; ce plugin lit seulement {@code ctx.inputFile()}. Chaque membre est
 * un bloc {@code <div class="spotlight-content">} :
 * <pre>
 * &lt;h3&gt;Gabriel Makhlouf&lt;/h3&gt;&lt;h3&gt;Governor*&lt;/h3&gt;           (* = membre de droit)
 * &lt;h3&gt;Shay Cody&lt;/h3&gt;                                  (membre nommé, sans titre)
 * &lt;h3&gt;John Hogan, Secretary General of the Department of Finance*&lt;/h3&gt;
 * </pre>
 * Le bloc « Secretary of the Central Bank » (secrétaire de la Commission, non membre) n'est pas repris. La page ne donne ni
 * dates ni naissance.
 */
@Extension
public class IePepCentralBankParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ie-pep-central-bank";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String SEAT = "Member of the Central Bank Commission";

    private static final Pattern BLOCK = Pattern.compile("(?is)<div[^>]*class=\"[^\"]*spotlight-content[^\"]*\"[^>]*>(.*?)</div>");
    private static final Pattern H3 = Pattern.compile("(?is)<h3[^>]*>(.*?)</h3>");

    /** Un membre de la Commission. */
    record Member(String name, String role, boolean exOfficio) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8)), dataset, pageUrl(ctx));
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : chaque membre, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        List<SourceRecord> records = new ArrayList<>();
        FtmDatasetBuilder builder = new FtmDatasetBuilder(ID_PREFIX);
        for (Member m : read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8))) {
            records.add(new SourceRecord(builder.buildEntityId(m.name()), "Person", m.name(), pageUrl(ctx)));
        }
        return records;
    }

    /** Lit les membres de la page. */
    static List<Member> read(String html) {
        List<Member> members = new ArrayList<>();
        Matcher block = BLOCK.matcher(html);
        while (block.find()) {
            List<String> titles = new ArrayList<>();
            Matcher h3 = H3.matcher(block.group(1));
            while (h3.find()) {
                titles.add(Html.text(h3.group(1)));
            }
            if (titles.isEmpty() || titles.get(0).toLowerCase().startsWith("secretary of the central bank")) {
                continue;
            }
            String first = titles.get(0);
            String name = first;
            String role = titles.size() > 1 ? titles.get(1) : null;
            int comma = first.indexOf(',');
            if (comma > 0) {
                name = first.substring(0, comma);
                role = first.substring(comma + 1);
            }
            boolean exOfficio = name.endsWith("*") || (role != null && role.endsWith("*"));
            name = name.replace("*", "").trim();
            role = role == null ? null : role.replace("*", "").trim();
            members.add(new Member(name, role == null || role.isBlank() ? null : role, exOfficio));
        }
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre de la Commission lu (blocs « spotlight-content » avec un titre h3) : "
                    + "la page a peut-être changé.");
        }
        return members;
    }

    /** Écrit les membres. Séparé de {@link #parse} pour les tests. */
    void convert(List<Member> members, FtmDatasetBuilder dataset, String pageUrl) {
        for (Member m : members) {
            String[] words = m.name().split("\\s+");
            FtmEntity person = dataset.newEntity("Person", m.name());
            person.addValue("name", m.name());
            person.addValue("firstName", words[0]);
            person.addValue("lastName", words.length > 1 ? m.name().substring(m.name().indexOf(' ') + 1) : null);
            person.addValue("country", "ie");
            person.addValue("position", SEAT);
            if (m.role() != null) {
                person.addValue("position", m.role());
            }
            person.addValue("sourceUrl", pageUrl);
            dataset.addEntity(person);

            List<String> labels = new ArrayList<>(List.of(SEAT));
            if (m.role() != null) {
                labels.add(m.role());
            }
            for (String label : labels) {
                FtmEntity position = dataset.newEntity("Position", label);
                position.addValue("name", label);
                position.addValue("country", "ie");
                position.addValue("organization", "Central Bank of Ireland");
                position.addValue("topics", "gov.financial");
                position.addValue("topics", "gov.national");
                dataset.addEntity(position);

                FtmEntity occupancy = dataset.newEntity("Occupancy", m.name(), label);
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", position.getId());
                occupancy.addValue("status", "current");
                occupancy.addValue("description", m.exOfficio() ? "Membre de droit de la Commission" : "Membre nommé par le Minister for Finance");
                occupancy.addValue("sourceUrl", pageUrl);
                dataset.addEntity(occupancy);
            }
        }
    }

    private static String pageUrl(ParserContext ctx) {
        String url = ctx.parameters() == null ? null : ctx.parameters().get("pageUrl");
        return url == null || url.isBlank() ? "https://www.centralbank.ie/about/who-we-are/the-commission" : url.trim();
    }

    /** Fichier source téléchargé par le core, absent si le parser n'est pas de type « Fichier HTTP ». */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit la page "
                    + "téléchargée par le core. Choisissez le type « Fichier HTTP » (HTTP_FILE) avec l'adresse "
                    + "https://www.centralbank.ie/about/who-we-are/the-commission.");
        }
        return ctx.inputFile();
    }
}
