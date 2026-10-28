package com.swiftcheck.aml.plugins.frpep.government;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.government.HatvpList.Facts;
import com.swiftcheck.aml.plugins.frpep.government.HatvpList.Member;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_government</b> – Premier ministre, ministres, ministres délégués et secrétaires d'État en fonction, et
 * ceux qui ont quitté le Gouvernement récemment (catégorie FR-a ; CMF, art. R. 561-18 ; directive (UE) 2015/849,
 * art. 3, point 9 a)).
 *
 * <p>Source : open data de la HATVP (Licence ouverte), où chaque membre du Gouvernement dépose ses déclarations
 * d'intérêts et de patrimoine (voir {@link HatvpList}). Type {@code PLUGIN_MANAGED} : le plugin télécharge la
 * liste puis une déclaration par membre ({@link HatvpDownloader}) et les dépose dans {@code rawDir} ; la conversion
 * ne lit ensuite que ces fichiers. Paramètres :
 * <pre>{@code
 * {"listUrl": "https://www.hatvp.fr/livraison/opendata/liste.csv",
 *  "declarationsUrl": "https://www.hatvp.fr/livraison/dossiers/", "delayMs": "300", "monthsAfterEnd": "12"}
 * }</pre>
 *
 * <p>Entités FollowTheMoney : le membre ({@code Person}, identifiant de sa page HATVP), sa fonction
 * ({@code Position}, ex. « Ministre de l'intérieur ») et le lien ({@code Occupancy}, avec le début et, pour un
 * ancien membre, la fin). Un ancien membre dont la fin date de plus de {@code monthsAfterEnd} mois est écarté.
 *
 * <p>Limite : un nouveau membre n'apparaît qu'après le dépôt de sa première déclaration (dans les deux mois de sa
 * nomination), et ses dates qu'après sa publication.
 */
@Extension
public class FrPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-government";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final long DEFAULT_DELAY_MS = 300;
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int current, int former, int monthsAfterEnd, List<String> tooOld, List<String> withoutDeclaration,
            List<String> failedDownloads) {

        String message() {
            StringBuilder m = new StringBuilder(String.format("%d membres du Gouvernement en fonction, %d anciens membres",
                    current, former));
            if (!tooOld.isEmpty()) {
                m.append(String.format(" ; %d sortis depuis plus de %d mois écartés", tooOld.size(), monthsAfterEnd));
            }
            m.append('.');
            if (!withoutDeclaration.isEmpty()) {
                m.append(String.format(" %d sans déclaration publiée (ni naissance ni dates) : %s.",
                        withoutDeclaration.size(), String.join(", ", withoutDeclaration)));
            }
            if (!failedDownloads.isEmpty()) {
                m.append(String.format(" %d déclarations non téléchargées : %s.", failedDownloads.size(),
                        String.join(", ", failedDownloads)));
            }
            return m.toString();
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<String> failed = new HatvpDownloader(number(p, "delayMs", DEFAULT_DELAY_MS))
                .download(required(p, "listUrl"), required(p, "declarationsUrl"), ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), failed, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque membre gardé, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), List.of(), dataset);
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
     * Lit la liste et les déclarations déposées dans {@code rawDir} et écrit les entités. Séparé de {@link #parse}
     * pour être testé sans réseau et à une date fixe.
     */
    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, List<String> failedDownloads,
            FtmDatasetBuilder dataset) throws Exception {
        Path list = rawDir.resolve(HatvpDownloader.LIST_FILE);
        if (!Files.exists(list)) {
            throw new IllegalStateException("Liste HATVP absente de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        String oldestEnd = today.minusMonths(monthsAfterEnd).toString();     // dates ISO : comparables en texte
        int current = 0;
        int former = 0;
        List<String> tooOld = new ArrayList<>();
        List<String> withoutDeclaration = new ArrayList<>();
        for (Member m : HatvpList.members(list)) {
            String name = joinNonBlank(" ", m.firstName(), m.lastName());
            Facts facts = facts(rawDir, m);
            if (facts == null) {
                withoutDeclaration.add(name);
                facts = new Facts(null, null, null);
            }
            // La liste décide qui est ancien membre. Pour un membre en fonction, une déclaration qui porte une date de
            // fin concerne sa fonction précédente (remaniement) : ses dates ne sont pas celles de la fonction actuelle.
            boolean ended = m.former();
            if (!ended && facts.endDate() != null) {
                facts = new Facts(facts.birthDate(), null, null);
            }
            if (ended && facts.endDate() != null && facts.endDate().compareTo(oldestEnd) < 0) {
                tooOld.add(name);
                continue;
            }
            if (ended) {
                former++;
            } else {
                current++;
            }
            write(m, name, facts, ended, dataset);
        }
        if (current == 0) {
            throw new IllegalStateException("Aucun membre du Gouvernement en fonction dans la liste HATVP : "
                    + "fichier incomplet ou format changé.");
        }
        return new Report(current, former, monthsAfterEnd, tooOld, withoutDeclaration, failedDownloads);
    }

    private static void write(Member m, String name, Facts facts, boolean ended, FtmDatasetBuilder dataset) {
        // ID = page HATVP de la personne (« lecornu-sebastien »), la même pour toutes ses déclarations
        FtmEntity person = dataset.newEntity("Person", m.id());
        person.addValue("name", name);
        person.addValue("firstName", m.firstName());
        person.addValue("lastName", m.lastName());
        person.addValue("birthDate", facts.birthDate());
        person.addValue("gender", toFtmGender(m.title()));                   // « Mme » → female
        person.addValue("country", "fr");
        person.addValue("nationality", "fr");
        person.addValue("position", m.function());
        person.addValue("sourceUrl", m.pageUrl());
        dataset.addEntity(person);

        // Une fonction par intitulé : le dataset la fusionne si deux membres ont le même
        FtmEntity position = dataset.newEntity("Position", m.function());
        position.addValue("name", m.function());
        position.addValue("country", "fr");
        position.addValue("organization", "Gouvernement de la République française");
        position.addValue("topics", "gov.executive");
        position.addValue("topics", "gov.national");
        if (m.function().startsWith("Premier ministre")) {
            position.addValue("topics", "gov.head");
        }
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.id(), m.function());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", facts.startDate());
        occupancy.addValue("endDate", facts.endDate());
        occupancy.addValue("status", ended ? "ended" : "current");
        occupancy.addValue("sourceUrl", m.pageUrl());
        dataset.addEntity(occupancy);
    }

    /** Dates de la déclaration téléchargée, ou {@code null} si aucune n'est publiée ou disponible. */
    private static Facts facts(Path rawDir, Member m) throws Exception {
        Optional<HatvpList.Declaration> d = m.bestDeclaration();
        if (d.isEmpty() || !Files.exists(rawDir.resolve(d.get().xmlFile()))) {
            return null;
        }
        return HatvpList.facts(rawDir.resolve(d.get().xmlFile()));
    }

    static int monthsAfterEnd(Map<String, String> p) {
        return (int) number(p, "monthsAfterEnd", DEFAULT_MONTHS_AFTER_END);
    }

    private static long number(Map<String, String> p, String key, long defaultValue) {
        String value = p.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « " + key + " » invalide : « " + value + " » (nombre attendu).");
        }
    }

    private static String required(Map<String, String> p, String key) {
        String value = p.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Paramètre « " + key + " » manquant dans la configuration du parser, ex. "
                    + "{\"listUrl\": \"https://www.hatvp.fr/livraison/opendata/liste.csv\", "
                    + "\"declarationsUrl\": \"https://www.hatvp.fr/livraison/dossiers/\"}.");
        }
        return value.trim();
    }
}
