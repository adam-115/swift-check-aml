package com.swiftcheck.aml.plugins.nopep.norgesbank;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
 * <b>no_pep_norges_bank</b> – membres du conseil de direction de Norges Bank (gouverneur, sous-gouverneurs, membres externes
 * et représentants du personnel), organe de direction d'une banque centrale.
 *
 * <p>Source : la page officielle « The Executive Board »
 * ({@code https://www.norges-bank.no/en/topics/about/Organisation/The-Executive-Board/}). Type {@code HTTP_FILE} : le core
 * télécharge la page ; ce plugin lit seulement {@code ctx.inputFile()}. Sous « Members of the Executive Board » chaque membre est
 * une ligne de nom suivie d'une ligne « Appointed … » (ou « Employee representative from … ») :
 * <pre>
 * Ida Wolden Bache
 * Appointed Governor of Norges Bank from 8 April 2022 to 2028. …
 * Hans Aasnes
 * Appointed board member for the period to 15 May 2028. …
 * </pre>
 * La fonction vient de cette ligne, la date de début de « from <date> » quand elle existe. Les suppléants des représentants du
 * personnel (« … is alternate for … ») ne sont pas repris. La page ne donne pas de naissance.
 */
@Extension
public class NoPepNorgesBankParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "no-pep-norges-bank";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String START_MARK = "Members of the Executive Board";

    private static final Pattern FROM = Pattern.compile("from (\\d{1,2}) ([A-Z][a-z]+) (\\d{4})");
    private static final Pattern NAME = Pattern.compile("^[\\p{Lu}][\\p{L}'’.-]*(?: [\\p{Lu}][\\p{L}'’.-]*){1,3}$");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("January", 1), Map.entry("February", 2),
            Map.entry("March", 3), Map.entry("April", 4), Map.entry("May", 5), Map.entry("June", 6), Map.entry("July", 7),
            Map.entry("August", 8), Map.entry("September", 9), Map.entry("October", 10), Map.entry("November", 11),
            Map.entry("December", 12));

    /** Un membre du conseil de direction. */
    record Member(String name, String role, LocalDate since, String note) {
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
        FtmDatasetBuilder builder = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (Member m : read(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8))) {
            records.add(new SourceRecord(builder.buildEntityId(m.name()), "Person", m.name(), pageUrl(ctx)));
        }
        return records;
    }

    /** Lit les membres sous « Members of the Executive Board ». */
    static List<Member> read(String html) {
        List<String> lines = new ArrayList<>();
        for (String raw : Html.decode(html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", "\n")).split("\n")) {
            String line = raw.replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        int start = lines.indexOf(START_MARK);
        List<Member> out = new ArrayList<>();
        if (start < 0) {
            throw new IllegalStateException("Rubrique « " + START_MARK + " » introuvable : la page a peut-être changé.");
        }
        for (int i = start + 1; i + 1 < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals("Subcommittees")) {
                break;
            }
            String next = lines.get(i + 1);
            boolean appointed = next.startsWith("Appointed ") || next.startsWith("Employee representative");
            if (NAME.matcher(line).matches() && appointed && !line.equals("Employee representatives")) {
                String role = next.startsWith("Employee") ? "Représentant du personnel au conseil de direction"
                        : next.contains("Governor") && !next.contains("Deputy") ? "Gouverneur (Governor)"
                        : next.contains("Deputy Governor") ? "Sous-gouverneur (Deputy Governor)" : "Membre du conseil de direction";
                LocalDate since = null;
                Matcher m = FROM.matcher(next);
                if (m.find() && MONTHS.containsKey(m.group(2))) {
                    since = LocalDate.of(Integer.parseInt(m.group(3)), MONTHS.get(m.group(2)), Integer.parseInt(m.group(1)));
                }
                out.add(new Member(line, role, since, next.length() > 160 ? next.substring(0, 160) : next));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun membre lu sous « " + START_MARK + " » : la page a peut-être changé.");
        }
        return out;
    }

    /** Écrit les membres. Séparé de {@link #parse} pour les tests. */
    void convert(List<Member> members, FtmDatasetBuilder dataset, String pageUrl) {
        for (Member m : members) {
            String[] words = m.name().split("\\s+");
            FtmEntity person = dataset.newEntity("Person", m.name());
            person.addValue("name", m.name());
            person.addValue("firstName", words[0]);
            person.addValue("lastName", m.name().substring(m.name().indexOf(' ') + 1));
            person.addValue("country", "no");
            person.addValue("position", m.role());
            person.addValue("sourceUrl", pageUrl);
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", m.role());
            position.addValue("name", m.role());
            position.addValue("country", "no");
            position.addValue("organization", "Norges Bank");
            position.addValue("topics", "gov.financial");
            position.addValue("topics", "gov.national");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", m.name(), m.role());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", m.since() == null ? null : m.since().toString());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", m.note());
            occupancy.addValue("sourceUrl", pageUrl);
            dataset.addEntity(occupancy);
        }
    }

    private static String pageUrl(ParserContext ctx) {
        String url = ctx.parameters() == null ? null : ctx.parameters().get("pageUrl");
        return url == null || url.isBlank() ? "https://www.norges-bank.no/en/topics/about/Organisation/The-Executive-Board/" : url.trim();
    }

    /** Fichier source téléchargé par le core, absent si le parser n'est pas de type « Fichier HTTP ». */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit la page "
                    + "téléchargée par le core. Choisissez le type « Fichier HTTP » (HTTP_FILE) avec l'adresse "
                    + "https://www.norges-bank.no/en/topics/about/Organisation/The-Executive-Board/.");
        }
        return ctx.inputFile();
    }
}
