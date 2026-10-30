package com.swiftcheck.aml.plugins.frpep.internationalorgs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.internationalorgs.OrgConfig.Function;
import com.swiftcheck.aml.plugins.frpep.internationalorgs.OrgConfig.Organization;
import com.swiftcheck.aml.plugins.frpep.internationalorgs.OrgConfig.Page;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_international_orgs</b> – Dirigeants des organisations internationales situées en France.
 *
 * <p>Catégorie <b>FR-h</b>, fonction <b>Dirigeant d'une organisation internationale située en France</b>. Le
 * périmètre est la liste notifiée par la France à la Commission (JO C/2023/724) : 21 organisations, pour chacune les
 * fonctions considérées comme PEP (directeur général, secrétaire général, membres du conseil…).
 *
 * <p>Chaque organisation publie ses dirigeants à sa façon : la page à lire et, pour chaque fonction, le motif qui y
 * trouve le nom sont décrits dans {@code international-orgs.json} ({@link OrgConfig}). Les noms sont toujours lus
 * dans la page officielle, jamais écrits dans le code ou la configuration. Une organisation sans page exploitable y
 * est marquée {@code pending} et citée dans le message de chaque exécution.
 *
 * <p>Paramètres : {@code {"delayMs": "1000", "configFile": ""}} ({@code configFile} : chemin d'une configuration qui
 * remplace celle du plugin, pour corriger un motif sans le reconstruire).
 *
 * <p>Pour chaque dirigeant : la personne ({@code Person}, {@code role.pep}), la fonction ({@code Position}, une par
 * organisation et par fonction de la liste) et le mandat en cours ({@code Occupancy}).
 */
@Extension
public class FrPepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(FrPepInternationalOrgsParser.class);

    static final String ID_PREFIX = "fr-pep-intorgs";
    static final String TOPIC = "role.pep";
    static final String FAILED_FILE = "pages-en-echec.txt";

    /** Titres devant un nom, retirés : « H.E. », « Dr », « Prof. », « docteure ». */
    private static final Pattern HONORIFIC = Pattern.compile(
            "^(?:(?:H\\.\\s?E\\.|Dr\\.?|Dre|Prof\\.?|Pr\\.?|Mme|M\\.|Mr\\.?|Mrs\\.?|Ms\\.?|Sir|docteure?|Docteure?)\\s+)+");

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters();
        List<Organization> orgs = OrgConfig.load(p.get("configFile"));
        List<String> failed = new OrgPagesDownloader().download(orgs, integer(p, "delayMs", 1000), ctx.rawDir());
        Files.writeString(ctx.rawDir().resolve(FAILED_FILE), String.join("\n", failed), StandardCharsets.UTF_8);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(orgs, ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque dirigeant, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(OrgConfig.load(ctx.parameters().get("configFile")), ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Un dirigeant trouvé dans une page. */
    record Leader(String name, String function, Organization org, String url) {
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int organizationsRead, int leaders, List<String> functionsNotFound, List<String> pagesFailed,
            List<String> pending) {

        String message() {
            StringBuilder m = new StringBuilder(String.format("%d dirigeants dans %d organisations lues.", leaders,
                    organizationsRead));
            if (!pagesFailed.isEmpty()) {
                m.append(" Pages en échec : ").append(String.join(" ; ", pagesFailed)).append('.');
            }
            if (!functionsNotFound.isEmpty()) {
                m.append(" Fonctions non trouvées (page modifiée ?) : ").append(String.join(" ; ", functionsNotFound))
                        .append('.');
            }
            if (!pending.isEmpty()) {
                m.append(" Non couvert : ").append(String.join(" ; ", pending)).append('.');
            }
            return m.toString();
        }
    }

    /**
     * Lit les pages de {@code rawDir} avec la configuration et écrit les entités. Séparé de {@link #parse} pour être
     * testé sans réseau.
     *
     * @throws IllegalStateException si aucun dirigeant n'est trouvé (pages ou motifs à revoir)
     */
    Report convert(List<Organization> orgs, Path rawDir, FtmDatasetBuilder dataset) throws Exception {
        List<String> notFound = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Path failedFile = rawDir.resolve(FAILED_FILE);
        if (Files.exists(failedFile)) {
            Files.readAllLines(failedFile, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank())
                    .forEach(failed::add);
        }
        List<Leader> leaders = new ArrayList<>();
        Set<String> orgsRead = new LinkedHashSet<>();
        for (Organization org : orgs) {
            if (org.pending() != null) {
                pending.add(org.name() + " (" + org.pending() + ")");
            }
            for (int i = 0; i < org.pages().size(); i++) {
                Path file = rawDir.resolve(OrgConfig.pageFile(org, i));
                if (!Files.exists(file)) {
                    continue;           // page en échec, déjà citée
                }
                Page page = org.pages().get(i);
                String text = PageText.of(Files.readString(file, StandardCharsets.UTF_8));
                orgsRead.add(org.key());
                for (Function function : page.functions()) {
                    List<String> names = names(function.pattern(), text);
                    if (names.isEmpty()) {
                        notFound.add(org.key() + " – " + function.label());
                    }
                    names.forEach(n -> leaders.add(new Leader(n, function.label(), org, page.url())));
                }
            }
        }
        if (leaders.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant trouvé dans les pages des organisations internationales :"
                    + " sites ou motifs de international-orgs.json à revoir. Fonctions non trouvées : " + notFound);
        }
        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        for (Leader leader : leaders) {
            write(dataset, leader, persons);
        }
        persons.values().forEach(dataset::addEntity);

        Report report = new Report(orgsRead.size(), persons.size(), notFound, failed, pending);
        log.info(report.message());
        return report;
    }

    /** Noms trouvés par un motif, sans doublon, débarrassés des titres (« H.E. », « Dr »). */
    static List<String> names(Pattern pattern, String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String name = group(m, "name");
            if (name == null) {
                String first = group(m, "first");
                String last = group(m, "last");
                name = first == null || last == null ? null : first + " " + last;
            }
            if (name != null) {
                String clean = HONORIFIC.matcher(name.replaceAll("\\s+", " ").trim()).replaceFirst("")
                        .replaceAll("\\s*\\(ex-officio\\)$", "").trim();
                if (clean.contains(" ")) {
                    names.add(clean);
                }
            }
        }
        return new ArrayList<>(names);
    }

    private static String group(Matcher m, String group) {
        try {
            return m.group(group);
        } catch (IllegalArgumentException e) {
            return null;            // groupe absent du motif
        }
    }

    private static void write(FtmDatasetBuilder dataset, Leader leader, Map<String, FtmEntity> persons) {
        // Une personne par nom : présidente d'un conseil et membre d'un autre, elle a deux fonctions
        String personKey = fold(leader.name());
        String function = leader.function() + " – " + leader.org().name();
        FtmEntity person = persons.computeIfAbsent(personKey, k -> {
            FtmEntity p = dataset.newEntity("Person", personKey);
            p.addValue("name", leader.name());
            return p;
        });
        person.addValue("position", function);
        person.addValue("sourceUrl", leader.url());

        FtmEntity position = dataset.newEntity("Position", leader.org().key(), fold(leader.function()));
        position.addValue("name", function);
        position.addValue("country", "fr");
        position.addValue("organization", leader.org().name());
        position.addValue("topics", "gov.igo");
        position.addValue("sourceUrl", leader.url());
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", fold(leader.name()), leader.org().key(),
                fold(leader.function()));
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", leader.url());
        dataset.addEntity(occupancy);
    }

    /** Clé d'identifiant : minuscules sans accents. */
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
