package com.swiftcheck.aml.plugins.frpep.diplomats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.diplomats.DiplomatListReader.Diplomat;
import com.swiftcheck.aml.plugins.frpep.diplomats.DiplomatListReader.DiplomatList;
import com.swiftcheck.aml.plugins.frpep.diplomats.DiplomatListReader.Kind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_diplomats</b> – Ambassadeurs, représentants permanents, chargés d'affaires et ambassadeurs thématiques en
 * poste, d'après les listes publiées par le ministère de l'Europe et des Affaires étrangères (MEAE).
 *
 * <p>Catégorie <b>FR-f</b>, fonction <b>Ambassadeur ou chargé d'affaires</b> (CMF, art. R. 561-18). Contrairement à
 * {@code fr_pep_jorf}, qui reconstitue les mandats à partir des décrets, cette liste donne directement les titulaires
 * en poste à sa date de mise à jour, y compris ceux nommés il y a longtemps. Les deux se complètent : le JO donne les
 * nominations parues depuis la dernière mise à jour de la liste et les ambassadeurs sortis depuis moins d'un an.
 *
 * <p>Source PLUGIN_MANAGED ({@link DiplomatsDownloader}) ; paramètres :
 * <pre>{@code
 * {"embassiesUrl": "https://www.diplomatie.gouv.fr/fr/le-ministere/reseau-diplomatique/ambassades",
 *  "thematicUrl": "https://www.diplomatie.gouv.fr/fr/le-ministere/reseau-diplomatique/ambassadeurs-et-ambassadrices-thematiques",
 *  "maxListAgeDays": "200", "delayMs": "500"}
 * }</pre>
 * {@code maxListAgeDays} : au-delà, le message de l'exécution signale une liste ancienne (le ministère la met à jour
 * après les mouvements d'été et d'hiver).
 *
 * <p>Pour chaque diplomate : la personne ({@code Person}, {@code role.pep} et {@code role.diplo}), le poste
 * ({@code Position}) et le mandat en cours ({@code Occupancy}, début = prise de fonctions).
 */
@Extension
public class FrPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(FrPepDiplomatsParser.class);

    static final String ID_PREFIX = "fr-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_EMBASSIES_URL = "https://www.diplomatie.gouv.fr/fr/le-ministere/reseau-diplomatique/ambassades";
    static final String DEFAULT_THEMATIC_URL =
            "https://www.diplomatie.gouv.fr/fr/le-ministere/reseau-diplomatique/ambassadeurs-et-ambassadrices-thematiques";
    static final int DEFAULT_MAX_LIST_AGE_DAYS = 200;
    /** Moins de chefs de poste : la liste ou sa mise en page a changé (environ 180 postes). */
    static final int MIN_HEADS_OF_POST = 100;

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters();
        new DiplomatsDownloader().download(p.getOrDefault("embassiesUrl", DEFAULT_EMBASSIES_URL).trim(),
                p.getOrDefault("thematicUrl", DEFAULT_THEMATIC_URL).trim(), integer(p, "delayMs", 500), ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message(integer(p, "maxListAgeDays",
                DEFAULT_MAX_LIST_AGE_DAYS)));
    }

    /** Test du parser : chaque diplomate, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int headsOfPost, int chargesDAffaires, int vacant, int thematic, LocalDate headsUpdated,
            LocalDate thematicUpdated, LocalDate today, List<String> unread) {

        String message(int maxListAgeDays) {
            StringBuilder m = new StringBuilder(String.format("Liste des chefs de poste du %s : %d en poste (dont %d"
                    + " chargés d'affaires), %d postes sans titulaire. Liste des ambassadeurs thématiques du %s : %d.",
                    headsUpdated, headsOfPost, chargesDAffaires, vacant, thematicUpdated, thematic));
            if (headsUpdated != null && ChronoUnit.DAYS.between(headsUpdated, today) > maxListAgeDays) {
                m.append(String.format(" Attention : liste des chefs de poste vieille de %d jours ; les nominations"
                        + " plus récentes sont dans fr_pep_jorf.", ChronoUnit.DAYS.between(headsUpdated, today)));
            }
            if (!unread.isEmpty()) {
                m.append(" Lignes non lues : ").append(String.join(" ; ", unread.subList(0, Math.min(10,
                        unread.size())))).append(unread.size() > 10 ? "…" : ".");
            }
            return m.toString();
        }
    }

    /**
     * Lit les deux PDF de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour être testé sans réseau.
     *
     * @throws IllegalStateException si la liste des chefs de poste est presque vide (mise en page changée)
     */
    Report convert(Path rawDir, LocalDate today, FtmDatasetBuilder dataset) throws Exception {
        List<String> sources = Files.exists(rawDir.resolve(DiplomatsDownloader.SOURCES_FILE))
                ? Files.readAllLines(rawDir.resolve(DiplomatsDownloader.SOURCES_FILE), StandardCharsets.UTF_8)
                : List.of();
        String headsUrl = sources.size() > 0 ? sources.get(0) : DEFAULT_EMBASSIES_URL;
        String thematicUrl = sources.size() > 1 ? sources.get(1) : DEFAULT_THEMATIC_URL;

        DiplomatList heads = DiplomatListReader.readHeadsOfPost(
                DiplomatListReader.pdfLines(rawDir.resolve(DiplomatsDownloader.HEADS_OF_POST_PDF)));
        if (heads.diplomats().size() < MIN_HEADS_OF_POST) {
            throw new IllegalStateException("Seulement " + heads.diplomats().size() + " chefs de poste lus dans la"
                    + " liste du MEAE : sa mise en page a probablement changé. Lignes non lues : " + heads.unread());
        }
        Path thematicPdf = rawDir.resolve(DiplomatsDownloader.THEMATIC_PDF);
        DiplomatList thematic = Files.exists(thematicPdf)
                ? DiplomatListReader.readThematic(DiplomatListReader.pdfLines(thematicPdf))
                : new DiplomatList(List.of(), null, List.of(), List.of());

        for (Diplomat d : heads.diplomats()) {
            write(dataset, d, headsUrl);
        }
        for (Diplomat d : thematic.diplomats()) {
            write(dataset, d, thematicUrl);
        }
        List<String> unread = new ArrayList<>(heads.unread());
        unread.addAll(thematic.unread());
        Report report = new Report(heads.diplomats().size(),
                (int) heads.diplomats().stream().filter(d -> d.kind() == Kind.CHARGE_D_AFFAIRES).count(),
                heads.vacant().size(), thematic.diplomats().size(), heads.updated(), thematic.updated(), today, unread);
        log.info(report.message(DEFAULT_MAX_LIST_AGE_DAYS));
        return report;
    }

    private static void write(FtmDatasetBuilder dataset, Diplomat d, String sourceUrl) {
        String label = label(d);
        String personKey = fold(d.fullName());

        FtmEntity person = dataset.newEntity("Person", personKey);
        person.addValue("name", d.fullName());
        person.addValue("firstName", d.firstName().isEmpty() ? null : d.firstName());
        person.addValue("lastName", d.lastName());
        person.addValue("gender", d.civility().equals("Mme") ? "female" : "male");
        person.addValue("country", "fr");
        person.addValue("position", label);
        person.addValue("topics", "role.diplo");
        person.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", fold(label));
        position.addValue("name", label);
        position.addValue("country", "fr");
        position.addValue("organization", "Ministère de l'Europe et des Affaires étrangères");
        position.addValue("topics", "role.diplo");
        position.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", personKey, fold(label));
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", d.start() == null ? null : d.start().toString());
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(occupancy);
    }

    /**
     * Libellé du poste : « Ambassadeur de France – Allemagne (BERLIN) », « Représentant permanent de la France – UE
     * BRUXELLES », « Chargé d'affaires – Mali (BAMAKO) », ou la fonction d'un ambassadeur thématique.
     */
    static String label(Diplomat d) {
        String where = d.country().isEmpty() ? d.post() : d.country() + " (" + d.post() + ")";
        return switch (d.kind()) {
            case AMBASSADOR -> "Ambassadeur de France – " + where;
            case PERMANENT_REPRESENTATIVE -> "Représentant permanent de la France – " + d.post().substring(3);
            case CHARGE_D_AFFAIRES -> "Chargé d'affaires – " + where;
            case THEMATIC -> d.post().isEmpty() ? "Ambassadeur thématique" : d.post();
        };
    }

    /** Clé d'identifiant : minuscules sans accents (« Hélène TREHEUX-DUCHENE » → « helene treheux-duchene »). */
    static String fold(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.FRENCH), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('’', '\'').replaceAll("\\s+", " ").trim();
    }

    static int integer(Map<String, String> parameters, String name, int defaultValue) {
        String value = parameters.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre " + name + " : nombre entier attendu, reçu « " + value + " »");
        }
    }
}
