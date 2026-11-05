package com.swiftcheck.aml.plugins.iepep.courts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <b>ie_pep_courts</b> – juges des hautes juridictions irlandaises (Supreme Court, Court of Appeal, High Court), personnes
 * politiquement exposées au titre des « high-level judicial bodies » de l'article 37 du Criminal Justice Act 2010.
 *
 * <p>Source : la page officielle « The Judges » du Courts Service ({@code https://www.courts.ie/judges}). Type
 * {@code HTTP_FILE} : le core télécharge la page ; ce plugin lit seulement {@code ctx.inputFile()}. La page est découpée
 * en sections {@code <h2>Judges of the Supreme Court</h2>}, {@code <h2>Judges of the Court of Appeal</h2>},
 * {@code <h2>Judges of the High Court</h2>}… ; chaque section contient des paragraphes de noms séparés par {@code <br>} :
 * <pre>
 * The Hon. Mr. Justice Donal O'Donnell, Chief Justice
 * The Hon. Ms Justice Niamh Hyland
 * </pre>
 * Une section « Ex officio » (sous-titre {@code h3}) liste les membres de droit. Paramètre facultatif {@code courts} :
 * noms de juridictions séparés par des virgules (« Supreme Court,Court of Appeal,High Court » par défaut).
 *
 * <p>La page ne donne que les juges en fonction, sans dates : les juges sortis ne sont pas nommés (le core garde une
 * personne retirée de la source pendant la durée de rétention du parser).
 */
@Extension
public class IePepCourtsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ie-pep-courts";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final List<String> DEFAULT_COURTS = List.of("Supreme Court", "Court of Appeal", "High Court");

    private static final Pattern BLOCK = Pattern.compile("(?is)<(h2|h3)[^>]*>(.*?)</\\1>|<p[^>]*>(.*?)</p>");
    private static final Pattern SECTION = Pattern.compile("(?i)^Judges of the (.+)$");
    private static final Pattern HONORIFIC = Pattern.compile("(?i)^(?:The Hon\\.?\\s*)?(?:Mr|Ms|Mrs|Miss)\\.?\\s+Justice\\s+");

    /** Un juge lu dans une section. */
    record Judge(String name, String court, String role, boolean exOfficio) {
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int persons, Map<String, Integer> byCourt) {

        String message() {
            return String.format("%d juges : %s.", persons, byCourt);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8), courts(ctx.parameters()), dataset,
                pageUrl(ctx));
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque juge, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(Files.readString(sourceFile(ctx), StandardCharsets.UTF_8), courts(ctx.parameters()), dataset, pageUrl(ctx));
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Lit la page et écrit les juges. Séparé de {@link #parse} pour les tests. */
    Report convert(String html, List<String> courts, FtmDatasetBuilder dataset, String pageUrl) {
        List<Judge> judges = read(html, courts);
        if (judges.isEmpty()) {
            throw new IllegalStateException("Aucun juge lu dans la page (sections « Judges of the " + String.join(" / ", courts)
                    + " ») : la page a peut-être changé.");
        }
        Map<String, Integer> byCourt = new LinkedHashMap<>();
        Set<String> persons = new LinkedHashSet<>();
        for (Judge j : judges) {
            write(j, dataset, pageUrl);
            persons.add(j.name());
            {
                byCourt.merge(j.court() + (j.exOfficio() ? " (ex officio)" : ""), 1, Integer::sum);
            }
        }
        return new Report(persons.size(), byCourt);
    }

    /** Parcourt les titres et les paragraphes dans l'ordre de la page. */
    static List<Judge> read(String html, List<String> courts) {
        List<Judge> judges = new ArrayList<>();
        String court = null;
        boolean exOfficio = false;
        Matcher m = BLOCK.matcher(html);
        while (m.find()) {
            if (m.group(1) != null) {
                String title = Html.text(m.group(2));
                if (m.group(1).equalsIgnoreCase("h2")) {
                    Matcher s = SECTION.matcher(title);
                    court = s.matches() && courts.stream().anyMatch(c -> c.equalsIgnoreCase(s.group(1).trim())) ? s.group(1).trim() : null;
                    exOfficio = false;
                } else {
                    exOfficio = title.toLowerCase().startsWith("ex officio");
                }
                continue;
            }
            if (court == null) {
                continue;
            }
            for (String line : m.group(3).split("(?i)<br\\s*/?>")) {
                String text = Html.text(line);
                if (text.toLowerCase().startsWith("ex officio")) {
                    exOfficio = true;                           // « Ex officio: » en simple texte (sans titre h3)
                    continue;
                }
                if (!HONORIFIC.matcher(text).find()) {
                    continue;                                   // « Vacancy (x1) », « [incl. the circuit…] »
                }
                String rest = HONORIFIC.matcher(text).replaceFirst("");
                String name = rest;
                String role = null;
                int comma = rest.indexOf(',');
                if (comma > 0) {
                    name = rest.substring(0, comma).trim();
                    role = rest.substring(comma + 1).trim();
                }
                judges.add(new Judge(name, court, role, exOfficio));
            }
        }
        return judges;
    }

    private static void write(Judge j, FtmDatasetBuilder dataset, String pageUrl) {
        String seat = exOfficioLabel(j);
        String[] words = j.name().split("\\s+");

        FtmEntity person = dataset.newEntity("Person", j.name());
        person.addValue("name", j.name());
        person.addValue("firstName", words[0]);
        person.addValue("lastName", words.length > 1 ? j.name().substring(j.name().indexOf(' ') + 1) : null);
        person.addValue("country", "ie");
        person.addValue("position", seat);
        if (j.role() != null) {
            person.addValue("position", j.role());
        }
        person.addValue("sourceUrl", pageUrl);
        dataset.addEntity(person);

        List<String> posts = new ArrayList<>();
        posts.add(seat);
        if (j.role() != null) {
            posts.add(j.role());
        }
        for (String label : posts) {
            FtmEntity position = dataset.newEntity("Position", label);
            position.addValue("name", label);
            position.addValue("country", "ie");
            position.addValue("organization", j.court());
            position.addValue("topics", "gov.judicial");
            position.addValue("topics", "gov.national");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", j.name(), label);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", pageUrl);
            dataset.addEntity(occupancy);
        }
    }

    private static String exOfficioLabel(Judge j) {
        return j.exOfficio() ? "Ex officio member of the " + j.court() : "Judge of the " + j.court();
    }

    static List<String> courts(Map<String, String> parameters) {
        String value = parameters == null ? null : parameters.get("courts");
        if (value == null || value.isBlank()) {
            return DEFAULT_COURTS;
        }
        List<String> out = new ArrayList<>();
        for (String c : value.split(",")) {
            if (!c.isBlank()) {
                out.add(c.trim());
            }
        }
        return out;
    }

    private static String pageUrl(ParserContext ctx) {
        String url = ctx.parameters() == null ? null : ctx.parameters().get("pageUrl");
        return url == null || url.isBlank() ? "https://www.courts.ie/judges" : url.trim();
    }

    /** Fichier source téléchargé par le core, absent si le parser n'est pas de type « Fichier HTTP ». */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit la page "
                    + "téléchargée par le core. Choisissez le type « Fichier HTTP » (HTTP_FILE) avec l'adresse "
                    + "https://www.courts.ie/judges.");
        }
        return ctx.inputFile();
    }
}
