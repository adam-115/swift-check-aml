package com.swiftcheck.aml.plugins.bepep.euinstitutions;

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
import com.swiftcheck.aml.plugins.bepep.euinstitutions.EuReaders.Member;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>be_pep_eu_institutions</b> – Belges exerçant une fonction PEP dans une institution de l'Union, autre que
 * député européen ({@code be_pep_europarl}) ou commissaire ({@code be_pep_eu_commission}).
 *
 * <p>Catégorie <b>BE-EU</b>, fonction « Belge exerçant une autre fonction PEP dans une institution de l'UE »
 * (loi du 18/09/2017, art. 4, 28°, i) ; liste de la Commission, JO C/2023/724) :
 * <ul>
 *   <li>juges, avocats généraux et greffiers de la Cour de justice de l'Union (Cour de justice et Tribunal) ;</li>
 *   <li>membres de la Cour des comptes européenne ;</li>
 *   <li>BCE : directoire et conseil de surveillance prudentielle (les gouverneurs des banques centrales nationales
 *       figurent sur les listes nationales) ;</li>
 *   <li>secrétaire général du Conseil de l'Union européenne.</li>
 * </ul>
 */
@Extension
public class BePepEuInstitutionsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepEuInstitutionsParser.class);

    static final String ID_PREFIX = "be-pep-eu-institutions";
    static final String TOPIC = "role.pep";
    static final String FAILED_FILE = "sources-en-echec.txt";
    static final String CJUE_PHOTO_LICENCE = "© Cour de justice de l'Union européenne";

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

    /** Test du parser : chaque membre belge, avec l'ID et le nom que Yente doit connaître (sans réseau). */
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

    /** Bilan : membres lus et belges retenus par institution, sources en échec. */
    record Report(Map<String, int[]> byInstitution, List<String> belgian, List<String> failed, int photos) {

        String message() {
            StringBuilder m = new StringBuilder();
            byInstitution.forEach((inst, n) -> m.append(String.format("%s : %d membres lus, %d belges. ", inst, n[0], n[1])));
            m.append("Belges retenus : ").append(String.join(" ; ", belgian)).append('.');
            m.append(" Portraits : ").append(photos).append('.');
            if (!failed.isEmpty()) {
                m.append(" Sources en échec : ").append(String.join(" ; ", failed)).append('.');
            }
            return m.toString();
        }
    }

    /**
     * Lit les sources de {@code rawDir} et écrit les entités des membres belges. Séparé de {@link #parse} pour
     * être testé sans réseau.
     */
    Report convert(Path rawDir, FtmDatasetBuilder dataset, Map<String, String> parameters) throws Exception {
        List<String> failed = List.of();
        if (Files.exists(rawDir.resolve(FAILED_FILE))) {
            failed = Files.readAllLines(rawDir.resolve(FAILED_FILE), StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank()).toList();
        }

        List<Member> members = new ArrayList<>();
        Map<String, Map<String, String>> portraits = new LinkedHashMap<>();
        Map<String, String> urls = new LinkedHashMap<>();

        if (Files.exists(rawDir.resolve(EuSources.CJ_FILE))) {
            members.addAll(EuReaders.cjue(rawDir.resolve(EuSources.CJ_FILE), "Cour de justice"));
            portraits.put(EuSources.CJ_FILE, EuReaders.cjuePortraits(rawDir.resolve(EuSources.CJ_FILE)));
        }
        if (Files.exists(rawDir.resolve(EuSources.GC_FILE))) {
            members.addAll(EuReaders.cjue(rawDir.resolve(EuSources.GC_FILE), "Tribunal"));
            portraits.put(EuSources.GC_FILE, EuReaders.cjuePortraits(rawDir.resolve(EuSources.GC_FILE)));
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
        List<String> belgian = new ArrayList<>();
        int photos = 0;
        for (Member m : members) {
            int[] n = byInstitution.computeIfAbsent(m.institution(), k -> new int[2]);
            n[0]++;
            if (m.belgian()) {
                n[1]++;
                belgian.add(m.name() + " (" + m.evidence() + ")");
                String sourceUrl = urls.getOrDefault(m.sourceFile(), m.sourceFile());
                write(dataset, m, sourceUrl);
                String photo = portraits.getOrDefault(m.sourceFile(), Map.of()).get(fold(m.name()));
                if (photo != null) {
                    dataset.addMedia(dataset.buildEntityId(fold(m.name())), photo, sourceUrl, CJUE_PHOTO_LICENCE);
                    photos++;
                }
            }
        }
        Report report = new Report(byInstitution, belgian, failed, photos);
        log.info(report.message());
        return report;
    }

    private static void write(FtmDatasetBuilder dataset, Member m, String sourceUrl) {
        String key = fold(m.name());
        FtmEntity person = dataset.newEntity("Person", key);
        person.addValue("name", m.name());
        person.addValue("country", "be");
        person.addValue("nationality", "be");
        person.addValue("position", m.function());
        person.addValue("notes", "Nationalité belge : " + m.evidence());
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
        if (parameters == null) {
            return defaultValue;
        }
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
