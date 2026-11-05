package com.swiftcheck.aml.plugins.frpep.europarl;

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
import com.swiftcheck.aml.plugins.frpep.europarl.EuroparlApi.Detail;
import com.swiftcheck.aml.plugins.frpep.europarl.EuroparlApi.Mandate;
import com.swiftcheck.aml.plugins.frpep.europarl.EuroparlApi.Mep;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import com.swiftcheck.aml.sdk.WikidataImages;

/**
 * <b>fr_pep_europarl</b> – députés au Parlement européen élus en France, en fonction et sortis depuis moins de
 * 12 mois (catégorie FR-EU ; liste des fonctions PEP des institutions de l'UE, JO C/2023/611).
 *
 * <p>Source : API open data du Parlement européen (voir {@link EuroparlApi}). Même source que
 * {@code lu_pep_europarl}, mais la fiche de chaque député est lue en plus : elle donne la date et le lieu de
 * naissance, et les dates de mandat qui permettent de garder les députés sortis pendant la législature. Type
 * {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"currentUrl": "https://data.europarl.europa.eu/api/v2/meps/show-current?country-of-representation=FR&format=application%2Fld%2Bjson",
 *  "termUrl": "https://data.europarl.europa.eu/api/v2/meps?country-of-representation=FR&parliamentary-term=10&limit=500&format=application%2Fld%2Bjson",
 *  "detailUrl": "https://data.europarl.europa.eu/api/v2/meps/{id}?format=application%2Fld%2Bjson",
 *  "delayMs": "300", "monthsAfterEnd": "12"}
 * }</pre>
 */
@Extension
public class FrPepEuroparlParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-europarl";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    /** Pays de représentation, code de l'Office des publications utilisé dans les fiches. */
    static final String COUNTRY = "FRA";

    static final String PHOTOS_FILE = "wikidata-images.tsv";
    /** Contact générique : jamais l'adresse d'un utilisateur. */
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - photos des députés européens)";

    static final long DEFAULT_DELAY_MS = 300;
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    private static final String POSITION = "Député au Parlement européen élu en France";

    /** Bilan de la conversion, pour le message de l'exécution. */
    /** Au-delà, un ancien député n'est publié que si le fichier des décès de l'INSEE ne le contient pas. */
    static final int COOLING_MONTHS = 12;

    record Report(int current, int former, int tooOld, int monthsAfterEnd, List<String> withoutDetail,
            List<String> failedDownloads, int deceased, List<String> unverified) {

        Report(int current, int former, int tooOld, int monthsAfterEnd, List<String> withoutDetail,
                List<String> failedDownloads) {
            this(current, former, tooOld, monthsAfterEnd, withoutDetail, failedDownloads, 0, List.of());
        }

        String message() {
            StringBuilder m = new StringBuilder(String.format(
                    "%d députés européens en fonction, %d sortis depuis moins de %d mois (%d sortis avant, écartés).",
                    current, former, monthsAfterEnd, tooOld));
            if (!withoutDetail.isEmpty()) {
                m.append(String.format(" %d sans fiche (ni naissance ni dates) : %s.", withoutDetail.size(),
                        String.join(", ", withoutDetail)));
            }
            if (!failedDownloads.isEmpty()) {
                m.append(String.format(" %d fiches non téléchargées.", failedDownloads.size()));
            }
            if (deceased > 0) {
                m.append(String.format(" %d anciens députés décédés (fichier INSEE) non repris.", deceased));
            }
            if (!unverified.isEmpty()) {
                m.append(String.format(" %d anciens députés non repris faute de vérification du décès.", unverified.size()));
            }
            return m.toString();
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int months = monthsAfterEnd(p);
        List<String> previous = months > COOLING_MONTHS && !p.getOrDefault("previousTermUrls", "").isBlank()
                ? List.of(p.get("previousTermUrls").trim().split("\\s+")) : List.of();
        List<String> failed = EuroparlApi.download(EuroparlApi.httpFetcher(), required(p, "currentUrl"),
                required(p, "termUrl"), previous, required(p, "detailUrl"), number(p, "delayMs", DEFAULT_DELAY_MS), ctx.rawDir());
        InseeDeaths.Checker deaths = null;
        if (months > COOLING_MONTHS) {
            String url = p.getOrDefault("deathCheckUrl", InseeDeaths.DEFAULT_URL).trim();
            if (url.isBlank()) {
                throw new IllegalArgumentException("monthsAfterEnd > " + COOLING_MONTHS
                        + " exige deathCheckUrl : sans contrôle des décès, l'historique n'est pas publié.");
            }
            deaths = InseeDeaths.http(url, number(p, "deathDelayMs", 300), p.get("deathCheckToken"));
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now(), months, failed, dataset, deaths);
        String photos = addPhotos(dataset, EuroparlApi.meps(ctx.rawDir()).keySet(),
                WikidataImages.fromParameters(p, USER_AGENT),
                ctx.rawDir());
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message() + " " + photos);
    }

    /**
     * Photos des députés gardés : le site du Parlement refuse les clients automatiques (HTTP 403), on prend donc
     * l'image principale de Wikimedia Commons, trouvée par l'identifiant du Parlement européen (Wikidata P1186).
     * La réponse de Wikidata est déposée dans {@code rawDir}. Les photos sont un complément : une erreur ici ne
     * bloque pas la publication.
     *
     * @return le bilan, pour le message de l'exécution
     */
    static String addPhotos(FtmDatasetBuilder dataset, Collection<String> mepIds, WikidataImages.Fetcher fetcher,
            Path rawDir) {
        return WikidataImages.addPhotos(dataset, "P1186", mepIds, fetcher, rawDir.resolve(PHOTOS_FILE));
    }

    /** Test du parser : chaque député gardé, avec l'ID et le nom que Yente doit connaître (sans réseau). */
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

    /** Lit les fichiers de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour être testé à une date fixe. */
    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, List<String> failedDownloads,
            FtmDatasetBuilder dataset) throws Exception {
        return convert(rawDir, today, monthsAfterEnd, failedDownloads, dataset, (first, last, birth) -> false);   // sans contrôle : aperçu et tests
    }

    /**
     * @param deaths contrôle des décès, obligatoire dès que {@code monthsAfterEnd} dépasse 12 : un ancien député sorti
     *               depuis plus de 12 mois n'est publié que si son décès a été exclu
     */
    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, List<String> failedDownloads,
            FtmDatasetBuilder dataset, InseeDeaths.Checker deaths) throws Exception {
        Map<String, Mep> meps = EuroparlApi.meps(rawDir);
        Set<String> currentIds = new HashSet<>(EuroparlApi.currentIds(rawDir));
        if (currentIds.isEmpty()) {
            throw new IllegalStateException("Aucun député européen en fonction dans " + EuroparlApi.CURRENT_FILE
                    + " : réponse vide ou format changé.");
        }
        String oldestEnd = today.minusMonths(monthsAfterEnd).toString();     // dates ISO : comparables en texte
        int former = 0;
        int tooOld = 0;
        List<String> withoutDetail = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        int deceased = 0;
        String coolingLimit = today.minusMonths(COOLING_MONTHS).toString();
        Set<String> recentIds = EuroparlApi.recentIds(rawDir);
        for (Mep mep : meps.values()) {
            boolean current = currentIds.contains(mep.id());
            Detail detail = EuroparlApi.detail(rawDir, mep.id(), COUNTRY);
            // Une législature précédente sans fiche : ni dates ni naissance, donc ni durée ni décès vérifiables
            if (!current && detail == null && monthsAfterEnd > COOLING_MONTHS && !recentIds.contains(mep.id())) {
                unverified.add(mep.name() + " (fiche non lue)");
                continue;
            }
            if (detail == null) {
                withoutDetail.add(mep.name());
            }
            List<Mandate> mandates = detail == null ? List.of() : detail.mandates().stream()
                    .filter(m -> m.end() == null || m.end().compareTo(oldestEnd) >= 0).toList();
            // Un ancien député sans mandat récent dans sa fiche est sorti depuis trop longtemps. Sans fiche, on ne le
            // sait pas : il est gardé (il figure dans la liste de la législature en cours).
            if (!current && detail != null && mandates.isEmpty()) {
                tooOld++;
                continue;
            }
            if (!current && detail != null && monthsAfterEnd > COOLING_MONTHS
                    && mandates.stream().noneMatch(m -> m.end() == null || m.end().compareTo(coolingLimit) >= 0)) {
                // Sorti depuis plus de 12 mois : publié seulement s'il n'est pas décédé
                if (deaths == null) {
                    throw new IllegalStateException("Contrôle des décès absent alors que monthsAfterEnd = " + monthsAfterEnd);
                }
                if (detail.birthDate() == null || mep.firstName() == null || mep.lastName() == null) {
                    unverified.add(mep.name() + " (naissance inconnue)");
                    continue;
                }
                try {
                    if (deaths.isDeceased(mep.firstName(), mep.lastName(), LocalDate.parse(detail.birthDate()))) {
                        deceased++;
                        continue;
                    }
                } catch (java.io.IOException e) {
                    unverified.add(mep.name() + " (" + e.getMessage() + ")");
                    continue;
                }
            }
            if (!current) {
                former++;
            }
            write(mep, detail, mandates, current, dataset);
        }
        return new Report(currentIds.size(), former, tooOld, monthsAfterEnd, withoutDetail, failedDownloads, deceased,
                unverified);
    }

    private static void write(Mep mep, Detail detail, List<Mandate> mandates, boolean current, FtmDatasetBuilder dataset) {
        String pageUrl = "https://www.europarl.europa.eu/meps/fr/" + mep.id();

        // ID = identifiant officiel du Parlement européen (124760), le même que dans lu_pep_europarl
        FtmEntity person = dataset.newEntity("Person", mep.id());
        person.addValue("name", mep.name());                                // « Nicolas BAY »
        person.addValue("firstName", mep.firstName());
        person.addValue("lastName", mep.lastName());
        if (detail != null) {
            person.addValue("birthDate", detail.birthDate());
            person.addValue("birthPlace", detail.birthPlace());
            person.addValue("gender", detail.gender());
        }
        person.addValue("country", "fr");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", pageUrl);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "member");
        position.addValue("name", POSITION);
        position.addValue("country", "fr");
        position.addValue("organization", "Parlement européen");
        position.addValue("topics", "gov.igo");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        if (mandates.isEmpty()) {
            // Sans fiche : un mandat sans dates, en cours ou terminé selon la liste
            occupancy(dataset, mep, person, position, "term", null, null, current ? "current" : "ended", pageUrl);
        }
        for (Mandate m : mandates) {
            String status = m.end() == null && current ? "current" : "ended";
            occupancy(dataset, mep, person, position, m.id(), m.start(), m.end(), status, pageUrl);
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
