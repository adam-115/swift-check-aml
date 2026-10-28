package com.swiftcheck.aml.plugins.frpep.associated;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.associated.HatvpDeclarations.Declaration;
import com.swiftcheck.aml.plugins.frpep.associated.HatvpDeclarations.Directorship;
import com.swiftcheck.aml.plugins.frpep.associated.HatvpDeclarations.Holding;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_associated_entities</b> – organismes dirigés par les PEP français et sociétés dans lesquelles ils
 * détiennent une participation significative, d'après leurs déclarations d'intérêts publiées par la HATVP
 * (catégorie FR-RCA, proches associés : CMF, art. R. 561-18, III). Même modèle que {@code lu_pep_associated_entities}.
 *
 * <p>Source : {@code https://www.hatvp.fr/livraison/merge/declarations.xml} (Licence ouverte). Type {@code HTTP_FILE} :
 * le core télécharge le fichier ; ce plugin lit seulement {@code ctx.inputFile()} (voir {@link HatvpDeclarations}).
 * Paramètres :
 * <pre>{@code
 * {"mandateTypes": "depute senateur gouvernement europe", "minCapitalPercent": "10", "minValue": "100000",
 *  "monthsAfterEnd": "12"}
 * }</pre>
 * <ul>
 *   <li>organes dirigeants : ceux en cours et ceux quittés depuis moins de {@code monthsAfterEnd} mois ;</li>
 *   <li>participations : significatives si le capital détenu atteint {@code minCapitalPercent} % ou la valeur
 *       {@code minValue} € ; les petites lignes d'actions cotées (« 20 actions REXEL ») sont écartées.</li>
 * </ul>
 * Entités : la PEP ({@code Person}, {@code role.pep}), l'organisme ({@code Organization}, {@code role.rca}) et le lien
 * ({@code Directorship} ou {@code Ownership}). Un organisme cité par plusieurs PEP n'est écrit qu'une fois.
 */
@Extension
public class FrPepAssociatedEntitiesParser implements Iparser {

    static final String ID_PREFIX = "fr-pep-assoc";
    static final String TOPIC_PEP = "role.pep";
    static final String TOPIC_RCA = "role.rca";

    static final String DEFAULT_MANDATE_TYPES = "depute senateur gouvernement europe";
    static final double DEFAULT_MIN_CAPITAL_PERCENT = 10;
    static final double DEFAULT_MIN_VALUE = 100_000;
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    /** Règles de sélection, lues dans les paramètres. */
    record Rules(Set<String> mandateTypes, double minCapitalPercent, double minValue, String oldestEnd) {

        static Rules of(Map<String, String> p, LocalDate today) {
            int months = (int) number(p, "monthsAfterEnd", DEFAULT_MONTHS_AFTER_END);
            return new Rules(new LinkedHashSet<>(Arrays.asList(p.getOrDefault("mandateTypes", DEFAULT_MANDATE_TYPES)
                    .trim().split("[\\s,;]+"))), number(p, "minCapitalPercent", DEFAULT_MIN_CAPITAL_PERCENT),
                    number(p, "minValue", DEFAULT_MIN_VALUE), today.minusMonths(months).toString().substring(0, 7));
        }

        boolean keep(Directorship d) {
            return d.end() == null || d.end().compareTo(oldestEnd) >= 0;          // dates AAAA-MM comparables en texte
        }

        boolean keep(Holding h) {
            return (h.capitalPercent() != null && h.capitalPercent() >= minCapitalPercent)
                    || (h.value() != null && h.value() >= minValue);
        }
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int declarations, int pepsWithLinks, int organizations, int directorships, int holdings,
            int ignoredHoldings) {

        String message() {
            return String.format("%d déclarations d'intérêts de PEP : %d organismes liés à %d PEP (%d organes dirigeants, "
                    + "%d participations significatives ; %d petites participations écartées).", declarations,
                    organizations, pepsWithLinks, directorships, holdings, ignoredHoldings);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        Report report = convert(sourceFile(ctx), Rules.of(params(ctx), LocalDate.now()), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque organisme gardé, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        convert(sourceFile(ctx), Rules.of(params(ctx), LocalDate.now()), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Organization".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Organization", e.getValues("name").iterator().next(),
                        "declarations.xml"));
            }
        }
        return records;
    }

    /** Lit le fichier et écrit les entités. Séparé de {@link #parse} pour être testé à une date fixe. */
    Report convert(Path xml, Rules rules, FtmDatasetBuilder dataset) throws Exception {
        List<Declaration> declarations = HatvpDeclarations.read(xml, rules.mandateTypes());
        if (declarations.isEmpty()) {
            throw new IllegalStateException("Aucune déclaration d'intérêts de PEP (" + rules.mandateTypes() + ") dans "
                    + xml.getFileName() + " : fichier incomplet ou format changé.");
        }
        Set<String> organizations = new HashSet<>();
        int withLinks = 0;
        int directorships = 0;
        int holdings = 0;
        int ignored = 0;
        for (Declaration d : declarations) {
            List<Directorship> kept = d.directorships().stream().filter(rules::keep).toList();
            List<Holding> significant = d.holdings().stream().filter(rules::keep).toList();
            ignored += d.holdings().size() - significant.size();
            if (kept.isEmpty() && significant.isEmpty()) {
                continue;
            }
            withLinks++;
            FtmEntity pep = pep(d, dataset);
            for (Directorship dir : kept) {
                FtmEntity org = organization(dir.organization(), d, dataset);
                organizations.add(org.getId());
                FtmEntity link = dataset.newEntity("Directorship", d.key(), "director", org.getId());
                link.addValue("director", pep.getId());
                link.addValue("organization", org.getId());
                link.addValue("role", dir.role());
                link.addValue("startDate", dir.start());
                link.addValue("endDate", dir.end());
                link.addValue("summary", "Organe dirigeant déclaré à la HATVP");
                dataset.addEntity(link);
                directorships++;
            }
            for (Holding h : significant) {
                FtmEntity org = organization(h.organization(), d, dataset);
                organizations.add(org.getId());
                FtmEntity link = dataset.newEntity("Ownership", d.key(), "owner", org.getId());
                link.addValue("owner", pep.getId());
                link.addValue("asset", org.getId());
                link.addValue("percentage", h.capitalPercent() == null ? null : format(h.capitalPercent()));
                link.addValue("sharesCount", h.shares());
                link.addValue("sharesValue", h.value() == null ? null : format(h.value()));
                link.addValue("sharesCurrency", h.value() == null ? null : "EUR");
                link.addValue("summary", "Participation financière déclarée à la HATVP");
                dataset.addEntity(link);
                holdings++;
            }
        }
        return new Report(declarations.size(), withLinks, organizations.size(), directorships, holdings, ignored);
    }

    private static FtmEntity pep(Declaration d, FtmDatasetBuilder dataset) {
        FtmEntity pep = dataset.newEntity("Person", d.key());
        pep.addValue("name", joinNonBlank(" ", d.firstName(), d.lastName()));
        pep.addValue("firstName", d.firstName());
        pep.addValue("lastName", d.lastName());
        pep.addValue("birthDate", d.birthDate());
        pep.addValue("gender", toFtmGender(d.title()));
        pep.addValue("country", "fr");
        pep.addValue("position", d.mandate());
        pep.addValue("topics", TOPIC_PEP);
        dataset.addEntity(pep);
        return pep;
    }

    /** Un organisme par nom (casse et espaces ignorés par l'ID) : partagé entre les PEP qui le déclarent. */
    private static FtmEntity organization(String name, Declaration d, FtmDatasetBuilder dataset) {
        FtmEntity org = dataset.newEntity("Organization", name.toLowerCase());
        org.addValue("name", name);
        org.addValue("topics", TOPIC_RCA);
        org.addValue("summary", "Déclaré à la HATVP par " + joinNonBlank(" ", d.firstName(), d.lastName()));
        dataset.addEntity(org);
        return org;
    }

    private static String format(double n) {
        return n == Math.rint(n) ? String.valueOf((long) n) : String.valueOf(n);
    }

    private static Map<String, String> params(ParserContext ctx) {
        return ctx.parameters() == null ? Map.of() : ctx.parameters();
    }

    private static double number(Map<String, String> p, String key, double defaultValue) {
        String value = p.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « " + key + " » invalide : « " + value + " » (nombre attendu).");
        }
    }

    /**
     * Fichier source téléchargé par le core. Il est absent quand le parser est configuré en « Géré par le plugin »
     * ({@code PLUGIN_MANAGED}) : ce plugin ne télécharge rien lui-même, on explique alors quoi corriger.
     */
    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "le fichier téléchargé par le core. Dans la configuration du parser, choisissez le type « Fichier "
                    + "HTTP » (HTTP_FILE) et indiquez https://www.hatvp.fr/livraison/merge/declarations.xml.");
        }
        return ctx.inputFile();
    }
}
