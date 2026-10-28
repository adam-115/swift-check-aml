package com.swiftcheck.aml.plugins.frpep.senate;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.senate.SenateFiles.Mandate;
import com.swiftcheck.aml.plugins.frpep.senate.SenateFiles.Senator;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_senate</b> – sénateurs en fonction, sénateurs sortis depuis moins de 12 mois et présidence du Sénat
 * (catégorie FR-b ; CMF, art. R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 b)).
 *
 * <p>Source : open data du Sénat, data.senat.fr (Licence ouverte) : la liste générale des sénateurs et la liste de
 * leurs mandats (voir {@link SenateFiles}). Type {@code PLUGIN_MANAGED} : deux fichiers sont nécessaires, le plugin
 * les télécharge et les dépose dans {@code rawDir}. Paramètres :
 * <pre>{@code
 * {"generalUrl": "https://data.senat.fr/data/senateurs/ODSEN_GENERAL.csv",
 *  "mandatesUrl": "https://data.senat.fr/data/senateurs/ODSEN_ELUSEN.csv", "monthsAfterEnd": "12"}
 * }</pre>
 *
 * <p>Entités FollowTheMoney : le sénateur ({@code Person}, matricule du Sénat), la fonction ({@code Position}
 * « Sénateur » ou « Président du Sénat ») et chaque mandat gardé ({@code Occupancy}). Un ancien sénateur décédé
 * n'est pas repris.
 */
@Extension
public class FrPepSenateParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-senate";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final int DEFAULT_MONTHS_AFTER_END = 12;

    private static final String SENATOR = "Sénateur";
    private static final String PRESIDENT = "Président du Sénat";

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int current, int former, int monthsAfterEnd, List<String> presidents) {

        String message() {
            return String.format("%d sénateurs en fonction, %d sortis depuis moins de %d mois%s.", current, former,
                    monthsAfterEnd, presidents.isEmpty() ? "" : " ; présidence : " + String.join(", ", presidents));
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        SenateFiles.download(SenateFiles.httpFetcher(), required(p, "generalUrl"), required(p, "mandatesUrl"), ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque sénateur gardé, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Lit les fichiers de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour être testé à une date fixe. */
    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws Exception {
        Map<String, Senator> senators = SenateFiles.senators(rawDir);
        Map<String, List<Mandate>> mandates = SenateFiles.mandates(rawDir);
        String oldestEnd = today.minusMonths(monthsAfterEnd).toString();     // dates ISO : comparables en texte
        int current = 0;
        int former = 0;
        List<String> presidents = new ArrayList<>();
        for (Senator s : senators.values()) {
            List<Mandate> kept = mandates.getOrDefault(s.id(), List.of()).stream()
                    .filter(m -> m.current() || m.end().compareTo(oldestEnd) >= 0).toList();
            if (kept.isEmpty() || s.deathDate() != null) {
                continue;                                   // sorti depuis plus longtemps, ou décédé
            }
            boolean active = kept.stream().anyMatch(Mandate::current);
            if (active) {
                current++;
            } else {
                former++;
            }
            FtmEntity person = person(s, dataset);
            FtmEntity senator = position(dataset, "senator", SENATOR);
            for (Mandate m : kept) {
                occupancy(dataset, s, person, senator, m.id(), m.start(), m.end());
            }
            // La fonction au Bureau n'est datée que pour le sénateur en fonction (dernière fonction connue pour un ancien)
            if (active && PRESIDENT.equals(s.bureauFunction())) {
                occupancy(dataset, s, person, position(dataset, "president", PRESIDENT), "president", null, null);
                person.addValue("position", PRESIDENT);
                presidents.add(joinNonBlank(" ", s.firstName(), s.lastName()));
            }
            dataset.addEntity(person);
        }
        if (current == 0) {
            throw new IllegalStateException("Aucun sénateur en fonction dans les fichiers : fichier incomplet ou format changé.");
        }
        return new Report(current, former, monthsAfterEnd, presidents);
    }

    private static FtmEntity person(Senator s, FtmDatasetBuilder dataset) {
        // ID = matricule du Sénat (86034E), le même pour toute la carrière du sénateur
        FtmEntity person = dataset.newEntity("Person", s.id());
        person.addValue("name", joinNonBlank(" ", s.firstName(), s.lastName()));
        person.addValue("firstName", s.firstName());
        person.addValue("lastName", s.lastName());
        person.addValue("birthDate", s.birthDate());
        person.addValue("gender", toFtmGender(s.title()));                   // « Mme » → female
        person.addValue("country", "fr");
        person.addValue("political", s.group());
        person.addValue("position", SENATOR);
        person.addValue("sourceUrl", "https://www.senat.fr/senateur/" + s.id().toLowerCase() + ".html");
        return person;
    }

    /** Une fonction commune à tous ses titulaires : le dataset la fusionne (même ID). */
    private static FtmEntity position(FtmDatasetBuilder dataset, String key, String name) {
        FtmEntity position = dataset.newEntity("Position", key);
        position.addValue("name", name);
        position.addValue("country", "fr");
        position.addValue("organization", "Sénat");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);
        return position;
    }

    private static void occupancy(FtmDatasetBuilder dataset, Senator s, FtmEntity person, FtmEntity position,
            String key, String start, String end) {
        FtmEntity occupancy = dataset.newEntity("Occupancy", s.id(), key);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", start);
        occupancy.addValue("endDate", end);
        occupancy.addValue("status", end == null ? "current" : "ended");
        occupancy.addValue("constituency", s.constituency());
        occupancy.addValue("politicalGroup", s.group());
        dataset.addEntity(occupancy);
    }

    static int monthsAfterEnd(Map<String, String> p) {
        String value = p.get("monthsAfterEnd");
        if (value == null || value.isBlank()) {
            return DEFAULT_MONTHS_AFTER_END;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « monthsAfterEnd » invalide : « " + value + " » (nombre de mois attendu).");
        }
    }

    private static String required(Map<String, String> p, String key) {
        String value = p.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Paramètre « " + key + " » manquant dans la configuration du parser, ex. "
                    + "{\"generalUrl\": \"https://data.senat.fr/data/senateurs/ODSEN_GENERAL.csv\", "
                    + "\"mandatesUrl\": \"https://data.senat.fr/data/senateurs/ODSEN_ELUSEN.csv\"}.");
        }
        return value.trim();
    }
}
