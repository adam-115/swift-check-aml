package com.swiftcheck.aml.plugins.frpep.euinstitutions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.swiftcheck.aml.plugins.frpep.euinstitutions.EuReaders.Member;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_eu_institutions</b> – Français exerçant une fonction PEP dans une institution de l'Union, autre que
 * député européen ({@code fr_pep_europarl}) ou commissaire ({@code fr_pep_eu_commission}).
 *
 * <p>Catégorie <b>FR-EU</b>, fonction « Français exerçant une autre fonction PEP dans une institution de l'UE ». Les
 * fonctions sont celles de la liste de la Commission (C(2022) 3105, JO C/2023/611) :
 * <ul>
 *   <li>juges, avocats généraux et greffiers de la Cour de justice de l'Union (Cour de justice et Tribunal) ;</li>
 *   <li>membres de la Cour des comptes européenne ;</li>
 *   <li>BCE : directoire et conseil de surveillance prudentielle (les gouverneurs des banques centrales nationales
 *       figurent sur les listes nationales) ;</li>
 *   <li>secrétaire général du Conseil de l'Union européenne.</li>
 * </ul>
 *
 * <p>Ces institutions publient leurs membres, mais pas toujours leur nationalité ({@link EuReaders}). Chaque membre
 * français est publié avec la <b>raison</b> qui le dit français (pays indiqué, nationalité écrite, naissance ou
 * carrière en France) ; les membres non français sont seulement comptés. Paramètres : une adresse par source
 * ({@link EuSources#DEFAULT_URLS}) et {@code delayMs}.
 */
@Extension
public class FrPepEuInstitutionsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(FrPepEuInstitutionsParser.class);

    static final String ID_PREFIX = "fr-pep-eu-institutions";
    static final String TOPIC = "role.pep";
    static final String FAILED_FILE = "sources-en-echec.txt";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        List<String> failed = new EuSources().download(ctx.parameters(), integer(ctx.parameters(), "delayMs", 500),
                ctx.rawDir());
        Files.writeString(ctx.rawDir().resolve(FAILED_FILE), String.join("\n", failed), StandardCharsets.UTF_8);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), dataset, ctx.parameters());
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque membre français, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset, ctx.parameters());
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Bilan : membres lus et français retenus par institution, sources en échec. */
    record Report(Map<String, int[]> byInstitution, List<String> french, List<String> failed) {

        String message() {
            StringBuilder m = new StringBuilder();
            byInstitution.forEach((inst, n) -> m.append(String.format("%s : %d membres lus, %d français. ", inst, n[0], n[1])));
            m.append("Français retenus : ").append(String.join(" ; ", french)).append('.');
            if (!failed.isEmpty()) {
                m.append(" Sources en échec : ").append(String.join(" ; ", failed)).append('.');
            }
            m.append(" Non couvert : chefs des délégations de l'UE (SEAE), nationalité non publiée.");
            return m.toString();
        }
    }

    /**
     * Lit les sources de {@code rawDir} et écrit les entités des membres français. Séparé de {@link #parse} pour
     * être testé sans réseau.
     *
     * @throws IllegalStateException si aucune source n'a donné de membre (pages ou mise en page changées)
     */
    Report convert(Path rawDir, FtmDatasetBuilder dataset, Map<String, String> parameters) throws Exception {
        List<String> failed = new ArrayList<>();
        Path failedFile = rawDir.resolve(FAILED_FILE);
        if (Files.exists(failedFile)) {
            Files.readAllLines(failedFile, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).forEach(failed::add);
        }
        List<Member> members = new ArrayList<>();
        Map<String, String> urls = new LinkedHashMap<>();
        if (Files.exists(rawDir.resolve(EuSources.CJ_FILE))) {
            members.addAll(EuReaders.cjue(rawDir.resolve(EuSources.CJ_FILE), "Cour"));
        }
        if (Files.exists(rawDir.resolve(EuSources.GC_FILE))) {
            members.addAll(EuReaders.cjue(rawDir.resolve(EuSources.GC_FILE), "Tribunal"));
        }
        if (Files.exists(rawDir.resolve(EuSources.ECA_FILE))) {
            members.addAll(EuReaders.eca(rawDir.resolve(EuSources.ECA_FILE)));
        }
        members.addAll(EuReaders.ecbBoard(rawDir));
        members.addAll(EuReaders.ssm(rawDir.resolve(EuSources.SSM_FILE)));
        members.addAll(EuReaders.councilSecretaryGeneral(rawDir.resolve(EuSources.COUNCIL_SG_FILE),
                rawDir.resolve(EuSources.COUNCIL_SG_BIO_FILE)));
        urls.put(EuSources.CJ_FILE, EuSources.url(parameters, "cjUrl"));
        urls.put(EuSources.GC_FILE, EuSources.url(parameters, "gcUrl"));
        urls.put(EuSources.ECA_FILE, EuSources.url(parameters, "ecaUrl"));
        urls.put(EuSources.ECB_BOARD_FILE, EuSources.url(parameters, "ecbBoardUrl"));
        urls.put(EuSources.SSM_FILE, EuSources.url(parameters, "ssmUrl"));
        urls.put(EuSources.COUNCIL_SG_FILE, EuSources.url(parameters, "councilSgUrl"));
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre lu dans les pages des institutions de l'UE : pages ou mise en"
                    + " page changées. Sources en échec : " + failed);
        }

        Map<String, int[]> byInstitution = new LinkedHashMap<>();
        List<String> french = new ArrayList<>();
        for (Member m : members) {
            int[] n = byInstitution.computeIfAbsent(m.institution(), k -> new int[2]);
            n[0]++;
            if (m.french()) {
                n[1]++;
                french.add(m.name() + " (" + m.evidence() + ")");
                write(dataset, m, urls.getOrDefault(m.sourceFile(), m.sourceFile()));
            }
        }
        Report report = new Report(byInstitution, french, failed);
        log.info(report.message());
        return report;
    }

    private static void write(FtmDatasetBuilder dataset, Member m, String sourceUrl) {
        String key = fold(m.name());
        FtmEntity person = dataset.newEntity("Person", key);
        person.addValue("name", m.name());
        person.addValue("nationality", "fr");
        person.addValue("position", m.function());
        // La nationalité est parfois déduite : la raison reste lisible par l'analyste
        person.addValue("notes", "Nationalité française : " + m.evidence());
        person.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", fold(m.function()));
        position.addValue("name", m.function());
        position.addValue("organization", m.institution());
        position.addValue("topics", "gov.igo");
        position.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", key, fold(m.function()));
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(occupancy);
    }

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
