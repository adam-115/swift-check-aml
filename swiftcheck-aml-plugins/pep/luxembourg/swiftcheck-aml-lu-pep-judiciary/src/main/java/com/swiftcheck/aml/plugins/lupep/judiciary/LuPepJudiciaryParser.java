package com.swiftcheck.aml.plugins.lupep.judiciary;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Parser PEP Luxembourg : Hautes juridictions judiciaires (justice.public.lu).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-d</b> (Membres des cours suprêmes, des cours
 * constitutionnelles ou d'autres hautes juridictions nationales, Directive (UE) 2015/849, art. 3, point 9 d)),
 * pour les quatre juridictions et fonctions judiciaires :
 * <ul>
 *   <li><b>Cour constitutionnelle</b> (fonction id 9 : Membre de la Cour constitutionnelle) ;</li>
 *   <li><b>Cour supérieure de justice</b> (fonction id 10 : Président ou vice-président de la Cour supérieure de justice) ;</li>
 *   <li><b>Cour de cassation</b> (fonction id 11 : Membre de la Cour de cassation) ;</li>
 *   <li><b>Parquet général</b> (fonction id 12 : Procureur général d'État ou adjoint).</li>
 * </ul>
 *
 * <p>L'URL source est résolue <b>dynamiquement</b> depuis le {@link ParserContext} passé en argument
 * (paramètres {@code sourceUrl}, {@code url} ou {@code sourceLocation}), avec repli par défaut sur
 * {@value #DEFAULT_SOURCE_URL}.
 */
@Extension
public class LuPepJudiciaryParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepJudiciaryParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-judiciary";

    /** URL source officielle par défaut vers la composition de la Cour constitutionnelle. */
    public static final String DEFAULT_SOURCE_URL =
            "https://justice.public.lu/fr/organisation-justice/cour-constitutionnelle.html";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    // Intitulés officiels des fonctions (référentiel LU-d)
    static final String POS_COUR_CONST = "Membre de la Cour constitutionnelle";
    static final String POS_PRES_CSJ = "Président ou vice-président de la Cour supérieure de justice";
    static final String POS_CASSATION = "Membre de la Cour de cassation";
    static final String POS_PARQUET_GEN = "Procureur général d'État ou adjoint";

    // Institutions
    static final String INST_COUR_CONST = "Cour constitutionnelle";
    static final String INST_CSJ = "Cour supérieure de justice";
    static final String INST_CASSATION = "Cour de cassation";
    static final String INST_PARQUET_GEN = "Parquet général";

    /** Pattern pour extraire les items <li> de composition. */
    private static final Pattern LI_PATTERN = Pattern.compile("(?si)<li[^>]*>(.*?)</li>");

    /** Pattern pour détecter et extraire un magistrat : "Monsieur/Madame Prénom NOM, Fonction..." */
    /** Pattern pour détecter et extraire un magistrat : "Monsieur/Madame Prénom NOM" */
    private static final Pattern PERSON_PATTERN = Pattern.compile(
            "(?i)(Monsieur|Madame)\\s+([A-Za-zÀ-ÿ\\-\\s]+?)(?:,|\\bet\\b)");

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        log.info("Analyse dynamique des hautes juridictions judiciaires (sourceUrl : {})", sourceUrl);

        convert(sourceFile(ctx), sourceUrl, oldestEndDate, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        convert(sourceFile(ctx), sourceUrl, oldestEndDate, dataset);

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
     * Lit le fichier source HTML et génère les entités FollowTheMoney dynamiquement.
     */
    void convert(Path htmlFile, String sourceUrl, LocalDate oldestEndDate, FtmDatasetBuilder dataset) throws Exception {
        String content = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<MagistrateData> list = extractMagistrates(content, sourceUrl, oldestEndDate);

        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun magistrat judiciaire extrait depuis " + htmlFile);
        }

        // 1. Déclarer les Positions officielles dynamiquement nécessaires
        Map<String, FtmEntity> positions = createPositions(dataset);

        // 2. Convertir chaque magistrat extrait
        for (MagistrateData m : list) {
            convertMagistrate(m, positions, sourceUrl, dataset);
        }

        log.info("Hautes juridictions : {} magistrat(s) généré(s) dynamiquement depuis la source", list.size());
    }

    /**
     * Crée les entités Position correspondantes aux juridictions de LU-d.
     */
    private Map<String, FtmEntity> createPositions(FtmDatasetBuilder dataset) {
        Map<String, FtmEntity> map = new LinkedHashMap<>();

        // 1. Cour constitutionnelle
        FtmEntity posConst = dataset.newEntity("Position", "pos-cour-constitutionnelle");
        posConst.addValue("name", POS_COUR_CONST);
        posConst.addValue("country", "lu");
        posConst.addValue("organization", INST_COUR_CONST);
        posConst.addValue("summary", "Membre de la Cour constitutionnelle (catégorie LU-d)");
        dataset.addEntity(posConst);
        map.put(POS_COUR_CONST, posConst);

        // 2. Cour supérieure de justice
        FtmEntity posCsj = dataset.newEntity("Position", "pos-cour-superieure-justice");
        posCsj.addValue("name", POS_PRES_CSJ);
        posCsj.addValue("country", "lu");
        posCsj.addValue("organization", INST_CSJ);
        posCsj.addValue("summary", "Président ou vice-président de la Cour supérieure de justice (catégorie LU-d)");
        dataset.addEntity(posCsj);
        map.put(POS_PRES_CSJ, posCsj);

        // 3. Cour de cassation
        FtmEntity posCass = dataset.newEntity("Position", "pos-cour-cassation");
        posCass.addValue("name", POS_CASSATION);
        posCass.addValue("country", "lu");
        posCass.addValue("organization", INST_CASSATION);
        posCass.addValue("summary", "Membre de la Cour de cassation (catégorie LU-d)");
        dataset.addEntity(posCass);
        map.put(POS_CASSATION, posCass);

        // 4. Parquet général
        FtmEntity posParquet = dataset.newEntity("Position", "pos-parquet-general");
        posParquet.addValue("name", POS_PARQUET_GEN);
        posParquet.addValue("country", "lu");
        posParquet.addValue("organization", INST_PARQUET_GEN);
        posParquet.addValue("summary", "Procureur général d'État ou adjoint (catégorie LU-d)");
        dataset.addEntity(posParquet);
        map.put(POS_PARQUET_GEN, posParquet);

        return map;
    }

    /**
     * Analyse le document HTML téléchargé et extrait dynamiquement les magistrats en fonction.
     */
    static List<MagistrateData> extractMagistrates(String html, String sourceUrl, LocalDate oldestEndDate) {
        Map<String, MagistrateData> result = new LinkedHashMap<>();

        // Recherche du bloc de composition des membres dans la page
        int idx = html.indexOf("membres de la Cour constitutionnelle");
        if (idx < 0) {
            idx = html.indexOf("Composition");
        }

        if (idx >= 0) {
            int endUl = html.indexOf("</ul>", idx);
            if (endUl > idx) {
                String ulContent = html.substring(idx, endUl + 5);
                Matcher mLi = LI_PATTERN.matcher(ulContent);
                while (mLi.find()) {
                    String liText = stripHtml(mLi.group(1));

                    // Exclusion stricte du personnel non-magistrat (greffe)
                    if (liText.toLowerCase(Locale.ROOT).contains("greffier")) {
                        continue;
                    }

                    // Détection des rôles mentionnés dans la ligne
                    List<String> roles = detectRoles(liText);

                    Matcher mPerson = PERSON_PATTERN.matcher(liText + ",");
                    while (mPerson.find()) {
                        String honorific = mPerson.group(1).trim();
                        String rawName = mPerson.group(2).trim();
                        if (rawName.isBlank()) continue;

                        String[] nameParts = splitFirstLastName(rawName);
                        String firstName = nameParts[0];
                        String lastName = nameParts[1];
                        String fullName = firstName + " " + lastName;
                        String slug = slug(fullName);

                        MagistrateData data = new MagistrateData(
                                slug,
                                fullName,
                                firstName,
                                lastName,
                                honorific,
                                roles,
                                null,
                                null,
                                "current",
                                sourceUrl
                        );
                        result.put(slug, data);
                    }
                }
            }
        }

        return new ArrayList<>(result.values());
    }

    private static String[] splitFirstLastName(String rawName) {
        String[] tokens = rawName.trim().split("\\s+");
        if (tokens.length == 1) {
            return new String[] { "", formatLastName(tokens[0]) };
        }
        int lastIdx = tokens.length - 1;
        String lastName = formatLastName(tokens[lastIdx]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lastIdx; i++) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(tokens[i]);
        }
        return new String[] { sb.toString(), lastName };
    }

    /**
     * Détecte dynamiquement les fonctions judiciaires selon l'intitulé de la ligne.
     */
    private static List<String> detectRoles(String text) {
        String clean = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        List<String> roles = new ArrayList<>();

        if (clean.contains("vice-president") || clean.contains("vice president")) {
            roles.add(POS_COUR_CONST);
        } else if (clean.contains("president") && clean.contains("constitutionnelle")) {
            roles.add(POS_COUR_CONST);
            roles.add(POS_PRES_CSJ);
            roles.add(POS_CASSATION);
        } else if (clean.contains("conseiller")) {
            roles.add(POS_COUR_CONST);
            if (clean.contains("cassation") || !clean.contains("administrat")) {
                roles.add(POS_CASSATION);
                roles.add(POS_PRES_CSJ);
            }
        } else if (clean.contains("procureur")) {
            roles.add(POS_PARQUET_GEN);
        } else if (clean.contains("cassation")) {
            roles.add(POS_CASSATION);
        } else {
            roles.add(POS_COUR_CONST);
        }

        return roles;
    }

    private static void convertMagistrate(
            MagistrateData m,
            Map<String, FtmEntity> positions,
            String sourceUrl,
            FtmDatasetBuilder dataset) {

        // 1. Person
        FtmEntity person = dataset.newEntity("Person", m.slug());
        person.addValue("name", m.fullName());
        person.addValue("firstName", m.firstName());
        person.addValue("lastName", m.lastName());
        if (m.honorific() != null && !m.honorific().isBlank()) {
            person.addValue("title", m.honorific());
        }
        person.addValue("country", "lu");
        person.addValue("nationality", "lu");
        person.addValue("sourceUrl", m.pageUrl() != null ? m.pageUrl() : sourceUrl);
        dataset.addEntity(person);

        // 2. Occupancies vers chaque position rattachée
        for (String func : m.functions()) {
            FtmEntity pos = positions.get(func);
            if (pos == null) {
                continue;
            }

            String occId = dataset.buildEntityId("occ", m.slug(), pos.getId());
            FtmEntity occ = dataset.newEntity("Occupancy", occId);
            occ.addValue("holder", person.getId());
            occ.addValue("post", pos.getId());
            occ.addValue("description", func);
            occ.addValue("status", m.status());
            if (m.startDate() != null) {
                occ.addValue("startDate", m.startDate().toString());
            }
            if (m.endDate() != null) {
                occ.addValue("endDate", m.endDate().toString());
            }
            occ.addValue("sourceUrl", m.pageUrl() != null ? m.pageUrl() : sourceUrl);

            dataset.addEntity(occ);
        }
    }

    private static String formatLastName(String raw) {
        if (raw == null) return "";
        String[] parts = raw.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(" ");
            if (p.length() > 1 && p.toUpperCase(Locale.ROOT).equals(p)) {
                sb.append(p.charAt(0)).append(p.substring(1).toLowerCase(Locale.ROOT));
            } else {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    static boolean isEligible(LocalDate endDate, LocalDate oldestEndDate) {
        if (endDate == null) {
            return true;
        }
        if (oldestEndDate == null) {
            return true;
        }
        return !endDate.isBefore(oldestEndDate);
    }

    static String resolveSourceUrl(ParserContext ctx) {
        if (ctx != null && ctx.parameters() != null) {
            String url = ctx.parameters().get("sourceUrl");
            if (url == null || url.isBlank()) {
                url = ctx.parameters().get("url");
            }
            if (url == null || url.isBlank()) {
                url = ctx.parameters().get("sourceLocation");
            }
            if (url != null && !url.isBlank()) {
                return url.trim();
            }
        }
        return DEFAULT_SOURCE_URL;
    }

    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "un fichier HTML téléchargé par le core (HTTP_FILE).");
        }
        return ctx.inputFile();
    }

    static LocalDate oldestEndDate(Map<String, String> parameters, LocalDate now) {
        int years = parseYearsAfterEnd(parameters);
        return years > 0 ? now.minusYears(years) : null;
    }

    private static int parseYearsAfterEnd(Map<String, String> parameters) {
        if (parameters == null) {
            return 5;
        }
        String val = parameters.get("yearsAfterEnd");
        if (val == null || val.isBlank()) {
            return 5;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String slug(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        String ascii = normalized.replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "item" : slug;
    }

    /**
     * Données d'un magistrat judiciaire extrait.
     */
    record MagistrateData(
            String slug,
            String fullName,
            String firstName,
            String lastName,
            String honorific,
            List<String> functions,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String pageUrl) {
    }
}
