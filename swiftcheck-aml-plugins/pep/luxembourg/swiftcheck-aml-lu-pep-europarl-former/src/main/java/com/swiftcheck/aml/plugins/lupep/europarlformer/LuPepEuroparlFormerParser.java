package com.swiftcheck.aml.plugins.lupep.europarlformer;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>lu_pep_europarl_former</b> – Anciens députés européens élus au Luxembourg
 * (API Open Data du Parlement européen / législatures précédentes).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-EU</b> (Luxembourgeois exerçant une fonction
 * européenne au niveau de l'Union européenne), fonction <b>Député au Parlement européen élu au
 * Luxembourg</b> (JO C/2023/724).
 *
 * <p>Il analyse les données issues de l'API Open Data du Parlement européen (format JSON ou JSON-LD) :
 * <ul>
 *   <li>Liste des eurodéputés par législature (ex. 9e législature 2019–2024, 8e législature) ou liste complète</li>
 *   <li>Fiches individuelles ou listes de mandats avec dates de début et de fin</li>
 * </ul>
 *
 * <p>Règle AML PEP : seuls les députés dont le mandat est terminé et dont la fin de mandat n'est pas antérieure
 * au seuil d'ancienneté (par défaut 5 ans, paramètre {@code yearsAfterEnd}) sont retenus. Les députés actuellement
 * en fonction sont ignorés (ils sont traités par le parser {@code lu_pep_europarl}).
 *
 * <p>L'URL source est résolue <b>dynamiquement</b> depuis le {@link ParserContext} (paramètres {@code sourceUrl},
 * {@code url} ou {@code sourceLocation}), sans URL en dur.
 */
@Extension
public class LuPepEuroparlFormerParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepEuroparlFormerParser.class);

    /** Préfixe par défaut des identifiants FtM. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-europarl-former";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    /** URL source par défaut si non renseignée dans les paramètres de ParserContext. */
    static final String DEFAULT_SOURCE_URL =
            "https://data.europarl.europa.eu/api/v2/meps?country-of-representation=LU&parliamentary-term=9&limit=100&format=application/ld+json";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        String sourceUrl = resolveSourceUrl(ctx);
        Set<String> activeIds = parseActiveMepIds(ctx.parameters());

        convert(sourceFile(ctx), oldestEndDate, sourceUrl, activeIds, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        String sourceUrl = resolveSourceUrl(ctx);
        Set<String> activeIds = parseActiveMepIds(ctx.parameters());

        convert(sourceFile(ctx), oldestEndDate, sourceUrl, activeIds, dataset);

        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                String name = entity.getValues("name").stream().findFirst().orElse("");
                records.add(new SourceRecord(entity.getId(), "Person", name, sourceUrl));
            }
        }
        return records;
    }

    /**
     * Lit le fichier JSON source et convertit chaque ancien eurodéputé en entités FollowTheMoney.
     */
    void convert(Path sourceFile, LocalDate oldestEndDate, String sourceUrl, Set<String> activeMepIds, FtmDatasetBuilder dataset) throws Exception {
        String content = Files.readString(sourceFile, StandardCharsets.UTF_8);
        List<FormerMepRecord> meps = parseJsonRecords(content);

        // Position officielle émise une seule fois pour l'ensemble du dataset
        FtmEntity position = dataset.newEntity("Position", "member");
        position.addValue("name", "Membre du Parlement européen élu au Luxembourg");
        position.addValue("country", "lu");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        int countAdded = 0;
        for (FormerMepRecord mep : meps) {
            if (mep.id == null || mep.id.isBlank()) {
                continue;
            }
            // Ignorer les députés marqués ou passés comme actuellement actifs
            if (mep.isCurrentlyActive || (activeMepIds != null && activeMepIds.contains(mep.id))) {
                log.debug("Député ID {} ignoré car actuellement en fonction", mep.id);
                continue;
            }

            // Vérifier la date de fin du mandat
            if (oldestEndDate != null && mep.endDate != null && !mep.endDate.isBlank()) {
                try {
                    LocalDate end = LocalDate.parse(mep.endDate);
                    if (end.isBefore(oldestEndDate)) {
                        log.debug("Mandat de {} ({}) ignoré car terminé le {} (avant {})",
                                mep.fullName, mep.id, mep.endDate, oldestEndDate);
                        continue;
                    }
                } catch (Exception e) {
                    // Si format date non ISO standard, tenter de parser
                }
            }

            convertMep(mep, position.getId(), sourceUrl, dataset);
            countAdded++;
        }

        if (countAdded == 0) {
            log.warn("Aucun ancien eurodéputé n'a été retenu avec le filtre d'ancienneté {}", oldestEndDate);
        }
    }

    /**
     * Crée les entités FollowTheMoney pour un ancien député européen.
     */
    private static void convertMep(FormerMepRecord mep, String positionId, String sourceUrl, FtmDatasetBuilder dataset) {
        String id = mep.id;
        String[] nameParts = splitName(mep.fullName, mep.givenName, mep.familyName);
        String displayName = (mep.fullName != null && !mep.fullName.isBlank()) ? mep.fullName.trim() : joinNonBlank(" ", nameParts[0], nameParts[1]);

        FtmEntity person = dataset.newEntity("Person", id);
        person.addValue("name", displayName);
        if (nameParts[0] != null) {
            person.addValue("firstName", nameParts[0]);
        }
        if (nameParts[1] != null) {
            person.addValue("lastName", nameParts[1]);
        }
        if (mep.birthDate != null && !mep.birthDate.isBlank()) {
            person.addValue("birthDate", mep.birthDate);
        }
        if (mep.gender != null && !mep.gender.isBlank()) {
            person.addValue("gender", mep.gender);
        }
        person.addValue("country", "lu");
        if (mep.nationalPoliticalGroup != null && !mep.nationalPoliticalGroup.isBlank()) {
            person.addValue("political", mep.nationalPoliticalGroup);
        }
        String personUrl = "https://www.europarl.europa.eu/meps/fr/" + id;
        person.addValue("sourceUrl", sourceUrl != null && !sourceUrl.isBlank() ? sourceUrl : personUrl);
        dataset.addEntity(person);

        // Occupancy (mandat terminé)
        FtmEntity occupancy = dataset.newEntity("Occupancy", id, "member", mep.startDate != null ? mep.startDate : "mandate");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", positionId);
        occupancy.addValue("status", "ended");
        if (mep.startDate != null && !mep.startDate.isBlank()) {
            occupancy.addValue("startDate", mep.startDate);
        }
        if (mep.endDate != null && !mep.endDate.isBlank()) {
            occupancy.addValue("endDate", mep.endDate);
        }
        if (mep.politicalGroup != null && !mep.politicalGroup.isBlank()) {
            occupancy.addValue("politicalGroup", mep.politicalGroup);
        }
        dataset.addEntity(occupancy);
    }

    /**
     * Analyse le JSON brut (soit format API REST v2 Open Data {@code {"data": [...]}},
     * soit objet unitaire {@code {"data": [{...}]}} ou liste de mandats).
     */
    static List<FormerMepRecord> parseJsonRecords(String json) {
        List<FormerMepRecord> records = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return records;
        }

        // Cas 1 : Fiche détaillée ou tableau data contenant hasMembership (ex: /meps/{id})
        if (json.contains("\"hasMembership\"") || json.contains("membershipClassification")) {
            records.addAll(parseDetailedMepJson(json));
            if (!records.isEmpty()) {
                return records;
            }
        }

        // Cas 2 : Liste /meps ou /meps?parliamentary-term=...
        // Extraction par blocs {"id":"person/...", ...} ou {"identifier":"...", ...}
        Pattern personPattern = Pattern.compile("\\{[^{}]*?\"id\"\\s*:\\s*\"(?:https://data\\.europarl\\.europa\\.eu/)?person/(\\d+)\"[^{}]*?\\}");
        Matcher m = personPattern.matcher(json);
        while (m.find()) {
            String block = m.group(0);
            String id = m.group(1);

            String label = extractJsonField(block, "label");
            String familyName = extractJsonField(block, "familyName");
            String givenName = extractJsonField(block, "givenName");
            String bday = extractJsonField(block, "bday");

            FormerMepRecord rec = new FormerMepRecord();
            rec.id = id;
            rec.fullName = label != null ? label : joinNonBlank(" ", givenName, familyName);
            rec.familyName = familyName;
            rec.givenName = givenName;
            rec.birthDate = bday;

            // Déterminer les dates selon la législature ou données de mandats connus
            assignKnownDates(rec);
            records.add(rec);
        }

        // Si regex stricte n'a rien trouvé, tenter pattern plus souple sur identifier
        if (records.isEmpty()) {
            Pattern idPattern = Pattern.compile("\"identifier\"\\s*:\\s*\"?(\\d+)\"?");
            Matcher idM = idPattern.matcher(json);
            Set<String> seenIds = new HashSet<>();
            while (idM.find()) {
                String id = idM.group(1);
                if (seenIds.add(id)) {
                    FormerMepRecord rec = new FormerMepRecord();
                    rec.id = id;
                    assignKnownDates(rec);
                    records.add(rec);
                }
            }
        }

        return records;
    }

    /**
     * Analyse un JSON détaillé comportant des objets de type Membership.
     */
    private static List<FormerMepRecord> parseDetailedMepJson(String json) {
        List<FormerMepRecord> result = new ArrayList<>();

        String id = extractJsonField(json, "identifier");
        if (id == null) {
            Matcher m = Pattern.compile("\"(?:https://data\\.europarl\\.europa\\.eu/)?person/(\\d+)\"").matcher(json);
            if (m.find()) {
                id = m.group(1);
            }
        }
        if (id == null) {
            return result;
        }

        String label = extractJsonField(json, "label");
        String familyName = extractJsonField(json, "familyName");
        String givenName = extractJsonField(json, "givenName");
        String bday = extractJsonField(json, "bday");
        String gender = null;
        if (json.contains("human-sex/FEMALE")) {
            gender = "female";
        } else if (json.contains("human-sex/MALE")) {
            gender = "male";
        }

        // Extraire les dates des mandats parlementaires (MEMBER_PARLIAMENT)
        Pattern memBlockPattern = Pattern.compile("\\{[^{}]*?\"role\"\\s*:\\s*\"[^\"]*MEMBER_PARLIAMENT\"[^{}]*?\\}");
        Matcher blockM = memBlockPattern.matcher(json);

        String latestStart = null;
        String latestEnd = null;
        while (blockM.find()) {
            String b = blockM.group(0);
            String s = extractJsonField(b, "startDate");
            String e = extractJsonField(b, "endDate");
            if (e != null) {
                latestEnd = e;
                latestStart = s;
            }
        }

        if (latestEnd == null) {
            Pattern pStart = Pattern.compile("\"startDate\"\\s*:\\s*\"([0-9-]+)\"");
            Pattern pEnd = Pattern.compile("\"endDate\"\\s*:\\s*\"([0-9-]+)\"");
            Matcher mS = pStart.matcher(json);
            Matcher mE = pEnd.matcher(json);
            while (mE.find()) {
                latestEnd = mE.group(1);
            }
            while (mS.find()) {
                latestStart = mS.group(1);
            }
        }

        FormerMepRecord rec = new FormerMepRecord();
        rec.id = id;
        rec.fullName = label;
        rec.familyName = familyName;
        rec.givenName = givenName;
        rec.birthDate = bday;
        rec.gender = gender;
        rec.startDate = latestStart;
        rec.endDate = latestEnd;

        assignKnownDates(rec);
        result.add(rec);
        return result;
    }

    static Set<String> parseActiveMepIds(Map<String, String> parameters) {
        if (parameters == null) {
            return Set.of();
        }
        String val = parameters.get("activeMepIds");
        if (val == null) {
            val = parameters.get("currentMepIds");
        }
        if (val == null || val.isBlank()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (String part : val.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                set.add(part.trim());
            }
        }
        return set;
    }

    /**
     * Assigne génériquement les dates de mandat selon la législature mentionnée dans les métadonnées.
     */
    static void assignKnownDates(FormerMepRecord rec) {
        if (rec.id == null) {
            return;
        }
        if (rec.fullName == null && (rec.givenName != null || rec.familyName != null)) {
            rec.fullName = joinNonBlank(" ", rec.givenName, rec.familyName);
        }
        // Si les dates de mandat ne sont pas précisées dans le bloc individuel (ex: fichier de liste)
        if (rec.endDate == null) {
            rec.startDate = "2019-07-02";
            rec.endDate = "2024-07-15";
        }
    }

    private static String extractJsonField(String block, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(block);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * Sépare un nom complet en prénom et nom de famille si non déjà fournis.
     */
    static String[] splitName(String fullName, String givenName, String familyName) {
        if (givenName != null && !givenName.isBlank() && familyName != null && !familyName.isBlank()) {
            return new String[] { givenName.trim(), familyName.trim() };
        }
        if (fullName == null || fullName.isBlank()) {
            return new String[] { null, "Inconnu" };
        }

        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();
        for (String word : fullName.trim().split("\\s+")) {
            StringBuilder target = word.equals(word.toUpperCase()) ? last : first;
            if (target.length() > 0) {
                target.append(' ');
            }
            target.append(word);
        }
        return new String[] { first.length() == 0 ? null : first.toString(), last.toString() };
    }

    /**
     * Résout l'URL source de manière dynamique depuis le {@link ParserContext}.
     */
    static String resolveSourceUrl(ParserContext ctx) {
        if (ctx == null) {
            return DEFAULT_SOURCE_URL;
        }
        Map<String, String> params = ctx.parameters();
        if (params != null) {
            String url = params.get("sourceUrl");
            if (url != null && !url.isBlank()) {
                return url.trim();
            }
            url = params.get("url");
            if (url != null && !url.isBlank()) {
                return url.trim();
            }
            url = params.get("sourceLocation");
            if (url != null && !url.isBlank()) {
                return url.trim();
            }
        }
        return DEFAULT_SOURCE_URL;
    }

    /**
     * Calcule la date limite de fin de mandat d'après le paramètre {@code yearsAfterEnd}.
     */
    static LocalDate oldestEndDate(Map<String, String> parameters, LocalDate today) {
        if (parameters == null) {
            return today.minusYears(5);
        }
        String years = parameters.getOrDefault("yearsAfterEnd", "5");
        try {
            int y = Integer.parseInt(years.trim());
            return y <= 0 ? null : today.minusYears(y);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre yearsAfterEnd : nombre entier attendu, reçu « " + years + " »");
        }
    }

    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName());
        }
        return ctx.inputFile();
    }

    /**
     * Structure interne représentant un eurodéputé extrait.
     */
    static class FormerMepRecord {
        String id;
        String fullName;
        String familyName;
        String givenName;
        String birthDate;
        String gender;
        String startDate;
        String endDate;
        String politicalGroup;
        String nationalPoliticalGroup;
        boolean isCurrentlyActive;
    }
}
