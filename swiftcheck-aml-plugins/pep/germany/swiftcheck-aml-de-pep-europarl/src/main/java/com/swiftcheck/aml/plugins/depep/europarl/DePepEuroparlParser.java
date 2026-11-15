package com.swiftcheck.aml.plugins.depep.europarl;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.depep.europarl.EuroparlApi.Detail;
import com.swiftcheck.aml.plugins.depep.europarl.EuroparlApi.Mandate;
import com.swiftcheck.aml.plugins.depep.europarl.EuroparlApi.Mep;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import com.swiftcheck.aml.sdk.WikidataImages;

/**
 * <b>de_pep_europarl</b> – Députés au Parlement européen élus en Allemagne (Story 17 - Ingestion_Europarl_MEP_DE).
 *
 * <p>Conforme aux spécifications de la Story 17 :
 * <ul>
 *   <li>API v2 du Parlement européen : {@code /meps/show-current?country-of-representation=DE} et {@code /meps/{id}}</li>
 *   <li>Classes PEP cibles : <b>B.1</b> (actifs, terme 10), <b>B.2</b> (historiques, sortis de mandat), <b>H.1</b> (Bureau du PE)</li>
 *   <li>Titre de fonction officiel non traduit : {@value #POSITION}</li>
 *   <li>Photos officielles directes : {@code https://www.europarl.europa.eu/mepphoto/{identifier}.jpg}</li>
 *   <li>URL source officielle en langue allemande : {@code https://www.europarl.europa.eu/meps/de/{identifier}}</li>
 *   <li>Identifiant externe : {@code EU_MEP_{identifier}}</li>
 * </ul>
 */
@Extension
public class DePepEuroparlParser implements Iparser {

    /** Préfixe des identifiants FtM : stable pour la gestion différentielle et le rattachement des alertes. */
    static final String ID_PREFIX = "de-pep-europarl";

    /** Catégorie de risque principale. */
    static final String TOPIC = "role.pep";

    /** Code pays pour les requêtes officielles de l'Office des publications. */
    static final String COUNTRY = "DEU";

    static final String PHOTOS_FILE = "wikidata-images.tsv";

    static final long DEFAULT_DELAY_MS = 300;
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    /** Titre officiel strict exigé par la Story 17 (conservé en allemand sans traduction). */
    public static final String POSITION = "Mitglied des Europäischen Parlaments (MdEP)";
    public static final String ORGANIZATION = "Europäisches Parlament";

    record Report(int current, int former, int tooOld, int monthsAfterEnd, List<String> withoutDetail,
            List<String> failedDownloads) {

        String message() {
            StringBuilder m = new StringBuilder(String.format(
                    "%d députés européens allemands actifs (B.1), %d sortis depuis moins de %d mois (B.2) (%d archivés).",
                    current, former, monthsAfterEnd, tooOld));
            if (!withoutDetail.isEmpty()) {
                m.append(String.format(" %d sans fiche : %s.", withoutDetail.size(), String.join(", ", withoutDetail)));
            }
            if (!failedDownloads.isEmpty()) {
                m.append(String.format(" %d fiches non téléchargées.", failedDownloads.size()));
            }
            return m.toString();
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<String> failed = EuroparlApi.download(EuroparlApi.httpFetcher(), required(p, "currentUrl"),
                required(p, "termUrl"), required(p, "detailUrl"), number(p, "delayMs", DEFAULT_DELAY_MS), ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), failed, dataset);
        String wikidataPhotos = addPhotos(dataset, EuroparlApi.meps(ctx.rawDir()).keySet(),
                WikidataImages.fromParameters(p, EuroparlApi.USER_AGENT),
                ctx.rawDir());
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message() + " Photos : " + wikidataPhotos);
    }

    static String addPhotos(FtmDatasetBuilder dataset, Collection<String> mepIds, WikidataImages.Fetcher fetcher,
            Path rawDir) {
        return WikidataImages.addPhotos(dataset, "P1186", mepIds, fetcher, rawDir.resolve(PHOTOS_FILE));
    }

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

    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, List<String> failedDownloads,
            FtmDatasetBuilder dataset) throws Exception {
        Map<String, Mep> meps = EuroparlApi.meps(rawDir);
        Set<String> currentIds = new HashSet<>(EuroparlApi.currentIds(rawDir));
        if (currentIds.isEmpty()) {
            throw new IllegalStateException("Aucun député européen en fonction dans " + EuroparlApi.CURRENT_FILE
                    + " : réponse vide ou format changé (AC2.4).");
        }
        String oldestEnd = today.minusMonths(monthsAfterEnd).toString();
        int former = 0;
        int tooOld = 0;
        List<String> withoutDetail = new ArrayList<>();
        for (Mep mep : meps.values()) {
            boolean current = currentIds.contains(mep.id());
            Detail detail = EuroparlApi.detail(rawDir, mep.id(), COUNTRY);
            if (detail == null) {
                withoutDetail.add(mep.name());
            }
            List<Mandate> mandates = detail == null ? List.of() : detail.mandates().stream()
                    .filter(m -> m.end() == null || m.end().compareTo(oldestEnd) >= 0).toList();
            if (!current && detail != null && mandates.isEmpty()) {
                tooOld++;
                continue;
            }
            if (!current) {
                former++;
            }
            write(mep, detail, mandates, current, today, dataset);
        }
        return new Report(currentIds.size(), former, tooOld, monthsAfterEnd, withoutDetail, failedDownloads);
    }

    private static void write(Mep mep, Detail detail, List<Mandate> mandates, boolean current, LocalDate today,
            FtmDatasetBuilder dataset) {
        String pageUrl = "https://www.europarl.europa.eu/meps/de/" + mep.id();
        String photoUrl = "https://www.europarl.europa.eu/mepphoto/" + mep.id() + ".jpg";

        FtmEntity person = dataset.newEntity("Person", mep.id());
        person.addValue("name", mep.name());
        person.addValue("firstName", mep.firstName());
        person.addValue("lastName", mep.lastName());
        if (detail != null) {
            person.addValue("birthDate", detail.birthDate());
            person.addValue("birthPlace", detail.birthPlace());
            person.addValue("gender", detail.gender());
        }
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", pageUrl);
        person.addValue("description", "external_id: EU_MEP_" + mep.id());
        dataset.addEntity(person);

        // Enregistrement de la photo officielle du Parlement européen (Story 17)
        dataset.addMedia(person.getId(), photoUrl, pageUrl, "© Europäisches Parlament");

        // Position officielle
        FtmEntity position = dataset.newEntity("Position", "member");
        position.addValue("name", POSITION);
        position.addValue("country", "de");
        position.addValue("organization", ORGANIZATION);
        position.addValue("topics", "gov.igo");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        // Si le depute est membre du Bureau du PE (Classe H.1 : President, Vice-president, Questeur)
        if (detail != null && detail.bureauRole() != null) {
            person.addValue("position", detail.bureauRole());
            person.addValue("topics", "gov.igo");
            person.addValue("topics", "gov.executive");

            FtmEntity bureauPos = dataset.newEntity("Position", "bureau-" + mep.id());
            bureauPos.addValue("name", detail.bureauRole());
            bureauPos.addValue("country", "de");
            bureauPos.addValue("organization", ORGANIZATION + " (Präsidium)");
            bureauPos.addValue("topics", "gov.igo");
            bureauPos.addValue("topics", "gov.executive");
            dataset.addEntity(bureauPos);

            FtmEntity bureauOcc = dataset.newEntity("Occupancy", mep.id(), "bureau");
            bureauOcc.addValue("holder", person.getId());
            bureauOcc.addValue("post", bureauPos.getId());
            bureauOcc.addValue("status", current ? "current" : "ended");
            bureauOcc.addValue("sourceUrl", pageUrl);
            dataset.addEntity(bureauOcc);
        }

        // Mandats parlementaires (B.1 actif ou B.2 historique avec transition automatique - AC2.2)
        if (mandates.isEmpty()) {
            occupancy(dataset, mep, person, position, "term", null, current ? null : today.toString(),
                    current ? "current" : "ended", pageUrl);
        }
        for (Mandate m : mandates) {
            String status = m.end() == null && current ? "current" : "ended";
            String endDate = m.end() != null ? m.end() : (!current ? today.toString() : null);
            occupancy(dataset, mep, person, position, m.id(), m.start(), endDate, status, pageUrl);
        }
    }

    private static void occupancy(FtmDatasetBuilder dataset, Mep mep, FtmEntity person, FtmEntity position, String key,
            String start, String end, String status, String pageUrl) {
        FtmEntity occupancy = dataset.newEntity("Occupancy", mep.id(), key);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", start);
        occupancy.addValue("endDate", end);
        occupancy.addValue("status", status);
        occupancy.addValue("politicalGroup", mep.group());
        occupancy.addValue("sourceUrl", pageUrl);
        dataset.addEntity(occupancy);
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
            throw new IllegalStateException("Paramètre « " + key + " » manquant dans la configuration du parser "
                    + "(currentUrl, termUrl et detailUrl : voir le README du plugin).");
        }
        return value.trim();
    }
}
