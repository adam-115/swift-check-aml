package com.swiftcheck.aml.plugins.frpep.nationalassembly;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.nationalassembly.AmoArchive.Deputy;
import com.swiftcheck.aml.plugins.frpep.nationalassembly.AmoArchive.Mandate;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_national_assembly</b> – députés à l'Assemblée nationale en fonction, et ceux sortis depuis moins de
 * 12 mois, avec la présidence de l'Assemblée (catégorie FR-b ; CMF, art. R. 561-18 ; directive (UE) 2015/849,
 * art. 3, point 9 b)).
 *
 * <p>Source : archive AMO30 « tous acteurs, tous mandats, tous organes » de data.assemblee-nationale.fr, au format
 * JSON zippé, mise à jour chaque jour (Licence ouverte). Type {@code HTTP_FILE} : le core télécharge l'archive à
 * l'adresse configurée dans le parser ; ce plugin lit seulement {@code ctx.inputFile()} (voir {@link AmoArchive}).
 * Le fichier des députés en exercice (CSV) ne suffit pas : il ne donne ni la date de naissance ni les députés
 * sortis.
 *
 * <p>Paramètre facultatif : {@code {"monthsAfterEnd": "12"}}, durée pendant laquelle un député sorti reste PEP
 * (un an après la fin des fonctions, R. 561-18).
 *
 * <p>Entités FollowTheMoney : le député ({@code Person}, identifiant de l'Assemblée {@code PA…}), la fonction
 * ({@code Position} « Député » ou « Président de l'Assemblée nationale ») et chaque mandat gardé
 * ({@code Occupancy}, avec la circonscription, le groupe politique, le début et la fin).
 */
@Extension
public class FrPepNationalAssemblyParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-national-assembly";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final int DEFAULT_MONTHS_AFTER_END = 12;

    private static final String DEPUTY = "Député à l'Assemblée nationale";
    private static final String PRESIDENT = "Président de l'Assemblée nationale";

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int current, int former, int monthsAfterEnd, List<String> presidents) {

        String message() {
            return String.format("%d députés en fonction, %d sortis depuis moins de %d mois%s.", current, former,
                    monthsAfterEnd, presidents.isEmpty() ? "" : " ; présidence : " + String.join(", ", presidents));
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(sourceFile(ctx), LocalDate.now(), monthsAfterEnd(ctx.parameters()), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque député gardé, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(sourceFile(ctx), LocalDate.now(), monthsAfterEnd(ctx.parameters()), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /**
     * Lit l'archive et écrit les députés en fonction, et ceux sortis depuis {@code monthsAfterEnd} mois au plus.
     * Séparé de {@link #parse} pour être testé à une date fixe.
     */
    Report convert(Path zip, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws Exception {
        AmoArchive.Content content = AmoArchive.read(zip);
        String oldestEnd = today.minusMonths(monthsAfterEnd).toString();     // dates ISO : comparables en texte
        int current = 0;
        int former = 0;
        List<String> presidents = new ArrayList<>();
        for (Deputy d : content.deputies()) {
            List<Mandate> seats = d.seats().stream().filter(m -> kept(m, oldestEnd)).toList();
            if (seats.isEmpty()) {
                continue;                                   // sorti depuis plus longtemps
            }
            if (seats.stream().anyMatch(Mandate::current)) {
                current++;
            } else {
                former++;
            }
            String group = group(d, seats.stream().max(Comparator.comparing(Mandate::start)).orElseThrow(),
                    today.toString(), content.groups());
            FtmEntity person = person(d, group, dataset);
            FtmEntity deputy = position(dataset, "deputy", DEPUTY);
            for (Mandate seat : seats) {
                occupancy(dataset, d, person, deputy, seat, group(d, seat, today.toString(), content.groups()));
            }
            for (Mandate presidency : d.presidencies()) {
                if (kept(presidency, oldestEnd)) {
                    occupancy(dataset, d, person, position(dataset, "president", PRESIDENT), presidency, null);
                    person.addValue("position", PRESIDENT);
                    if (presidency.current()) {
                        presidents.add(joinNonBlank(" ", d.firstName(), d.lastName()));
                    }
                }
            }
            dataset.addEntity(person);
        }
        if (current == 0) {
            throw new IllegalStateException("Aucun député en fonction dans l'archive : fichier incomplet ou "
                    + "format changé (attendu : AMO30 tous acteurs, tous mandats, tous organes, en JSON).");
        }
        return new Report(current, former, monthsAfterEnd, presidents);
    }

    private static boolean kept(Mandate m, String oldestEnd) {
        return m.current() || m.end().compareTo(oldestEnd) >= 0;
    }

    private static FtmEntity person(Deputy d, String group, FtmDatasetBuilder dataset) {
        // ID = identifiant de l'Assemblée (PA721908), le même pour toute la carrière du député
        FtmEntity person = dataset.newEntity("Person", d.uid());
        person.addValue("name", joinNonBlank(" ", d.firstName(), d.lastName()));
        person.addValue("firstName", d.firstName());
        person.addValue("lastName", d.lastName());
        person.addValue("birthDate", d.birthDate());
        person.addValue("birthPlace", d.birthPlace());
        person.addValue("gender", toFtmGender(d.title()));                   // « Mme » → female
        person.addValue("country", "fr");
        person.addValue("political", group);
        person.addValue("position", DEPUTY);
        person.addValue("sourceUrl", "https://www.assemblee-nationale.fr/dyn/deputes/" + d.uid());
        return person;
    }

    /** Une fonction commune à tous ses titulaires : le dataset la fusionne (même ID). */
    private static FtmEntity position(FtmDatasetBuilder dataset, String key, String name) {
        FtmEntity position = dataset.newEntity("Position", key);
        position.addValue("name", name);
        position.addValue("country", "fr");
        position.addValue("organization", "Assemblée nationale");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);
        return position;
    }

    private static void occupancy(FtmDatasetBuilder dataset, Deputy d, FtmEntity person, FtmEntity position,
            Mandate m, String group) {
        FtmEntity occupancy = dataset.newEntity("Occupancy", d.uid(), m.uid());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", m.start());
        occupancy.addValue("endDate", m.end());
        occupancy.addValue("status", m.current() ? "current" : "ended");
        occupancy.addValue("constituency", m.constituency());
        occupancy.addValue("politicalGroup", group);
        dataset.addEntity(occupancy);
    }

    /** Groupe politique du mandat : celui en cours à sa fin (ou aujourd'hui), le plus récent s'il y en a deux. */
    private static String group(Deputy d, Mandate seat, String today, Map<String, String> groups) {
        String at = seat.current() ? today : seat.end();
        return d.groups().stream()
                .filter(g -> g.start() != null && g.start().compareTo(at) <= 0 && (g.current() || g.end().compareTo(at) >= 0))
                .max(Comparator.comparing(Mandate::start))
                .map(g -> groups.get(g.organ()))
                .orElse(null);
    }

    static int monthsAfterEnd(Map<String, String> parameters) {
        String value = parameters == null ? null : parameters.get("monthsAfterEnd");
        if (value == null || value.isBlank()) {
            return DEFAULT_MONTHS_AFTER_END;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « monthsAfterEnd » invalide : « " + value + " » (nombre de mois attendu).");
        }
    }

    /**
     * Fichier source téléchargé par le core. Il est absent quand le parser est configuré en « Géré par le plugin »
     * ({@code PLUGIN_MANAGED}) : ce plugin ne télécharge rien lui-même, on explique alors quoi corriger.
     */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "l'archive téléchargée par le core. Dans la configuration du parser, choisissez le type "
                    + "« Fichier HTTP » (HTTP_FILE) et indiquez l'adresse de l'archive AMO30 (JSON) de l'Assemblée nationale.");
        }
        return ctx.inputFile();
    }
}
