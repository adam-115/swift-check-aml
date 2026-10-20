package com.swiftcheck.aml.plugins.lupep.grandduke;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 * Parser PEP Luxembourg : Grand-Duc (Chef de l'État).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-a</b> (Chef de l'État, Premier ministre,
 * ministres et secrétaires d'État), fonction <b>Grand-Duc</b> (Directive (UE) 2015/849, art. 3, point 9 a)).
 *
 * <p>Il analyse les pages officielles du site de la Maison du Grand-Duc ({@code monarchie.lu}), notamment
 * la fiche officielle de S.A.R. le Grand-Duc régnant (actuellement S.A.R. le Grand-Duc Guillaume,
 * ayant prêté serment le 3 octobre 2025 suite à l'abdication de S.A.R. le Grand-Duc Henri).
 *
 * <p>Pour le Grand-Duc, il produit trois entités FollowTheMoney :
 * <ul>
 *   <li>{@code Person} : identifiant, nom (« Guillaume de Luxembourg »), date et lieu de naissance,
 *       pays ({@code lu}), nationalité, aliases dynastiques et topic {@code role.pep} ;</li>
 *   <li>{@code Position} : « Grand-Duc de Luxembourg » (institution : « Maison grand-ducale », LU-a) ;</li>
 *   <li>{@code Occupancy} : lien titulaire-fonction, date de prise de fonction (2025-10-03), statut ({@code current}).</li>
 * </ul>
 *
 * <p>L'URL source est résolue <b>dynamiquement</b> depuis l'objet {@link ParserContext} passé en argument
 * (paramètres {@code sourceUrl}, {@code url} ou {@code sourceLocation}), avec repli par défaut sur
 * {@value #DEFAULT_SOURCE_URL}.
 */
@Extension
public class LuPepGrandDukeParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepGrandDukeParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-grand-duke";

    /** URL source officielle par défaut vers la fiche de S.A.R. le Grand-Duc. */
    public static final String DEFAULT_SOURCE_URL =
            "https://monarchie.lu/fr/la-famille-grand-ducale/le-grand-duc";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    /** Intitulé officiel de la fonction selon le référentiel LU-a. */
    static final String POSITION_NAME = "Grand-Duc de Luxembourg";

    /** Institution de rattachement. */
    static final String INSTITUTION_NAME = "Maison grand-ducale";

    /** Pattern pour extraire le titre principal (H1). */
    private static final Pattern H1_PATTERN = Pattern.compile("(?si)<h1[^>]*>(.*?)</h1>");

    /** Pattern pour extraire l'image og:image. */
    private static final Pattern OG_IMAGE_PATTERN = Pattern.compile(
            "(?i)<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']");

    /** Pattern pour extraire la date de naissance : "Né(e) le 11 novembre 1981". */
    private static final Pattern BIRTH_DATE_PATTERN = Pattern.compile(
            "(?i)N[ée]\\s+le\\s+(\\d{1,2})\\s+([a-zA-Zûéèê]+)\\s+(\\d{4})");

    /** Pattern pour extraire le lieu de naissance : "à Luxembourg" ou "à la Maternité... à Luxembourg". */
    private static final Pattern BIRTH_PLACE_PATTERN = Pattern.compile(
            "(?i)(?:à|au)\\s+(?:la\\s+Maternit[ée]\\s+Grande-Duchesse\\s+Charlotte\\s+à\\s+([A-Za-zÀ-ÿ\\-]+)|Château\\s+de\\s+([A-Za-zÀ-ÿ\\-]+))");

    /** Mois en français vers numéro. */
    private static final Map<String, String> MONTHS_FR = Map.ofEntries(
            Map.entry("janvier", "01"),
            Map.entry("fevrier", "02"),
            Map.entry("février", "02"),
            Map.entry("mars", "03"),
            Map.entry("avril", "04"),
            Map.entry("mai", "05"),
            Map.entry("juin", "06"),
            Map.entry("juillet", "07"),
            Map.entry("aout", "08"),
            Map.entry("août", "08"),
            Map.entry("septembre", "09"),
            Map.entry("octobre", "10"),
            Map.entry("novembre", "11"),
            Map.entry("decembre", "12"),
            Map.entry("décembre", "12")
    );

    /**
     * Profils de référence des souverains contemporains de la dynastie Nassau-Weilbourg / Luxembourg.
     */
    record KnownMonarch(
            String slug,
            String fullName,
            String firstName,
            String lastName,
            String birthDate,
            String birthPlace,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            List<String> aliases) {
    }

    private static final Map<String, KnownMonarch> KNOWN_MONARCHS = Map.of(
            "guillaume", new KnownMonarch(
                    "guillaume",
                    "Guillaume de Luxembourg",
                    "Guillaume",
                    "de Luxembourg",
                    "1981-11-11",
                    "Luxembourg",
                    LocalDate.of(2025, 10, 3),
                    null,
                    "current",
                    List.of(
                            "S.A.R. le Grand-Duc Guillaume",
                            "Grand-Duc Guillaume",
                            "Guillaume Jean Joseph Marie de Nassau",
                            "Son Altesse Royale le Grand-Duc Guillaume de Luxembourg, Duc de Nassau et Prince de Bourbon-Parme"
                    )
            ),
            "henri", new KnownMonarch(
                    "henri",
                    "Henri de Luxembourg",
                    "Henri",
                    "de Luxembourg",
                    "1955-04-16",
                    "Betzdorf",
                    LocalDate.of(2000, 10, 7),
                    LocalDate.of(2025, 10, 3),
                    "ended",
                    List.of(
                            "S.A.R. le Grand-Duc Henri",
                            "Grand-Duc Henri",
                            "Henri Albert Gabriel Félix Marie Guillaume de Nassau",
                            "Son Altesse Royale le Grand-Duc Henri de Luxembourg"
                    )
            )
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        boolean includeFormer = isIncludeFormer(ctx.parameters());
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        log.info("Analyse du Grand-Duc (sourceUrl : {}, includeFormer : {})", sourceUrl, includeFormer);

        convert(sourceFile(ctx), sourceUrl, includeFormer, oldestEndDate, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        boolean includeFormer = isIncludeFormer(ctx.parameters());
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        convert(sourceFile(ctx), sourceUrl, includeFormer, oldestEndDate, dataset);

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
     * Convertit le contenu HTML téléchargé en entités FollowTheMoney.
     */
    void convert(
            Path htmlFile,
            String sourceUrl,
            boolean includeFormer,
            LocalDate oldestEndDate,
            FtmDatasetBuilder dataset) throws Exception {

        String content = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<MonarchData> monarchs = extractMonarchs(content, sourceUrl, includeFormer, oldestEndDate);

        if (monarchs.isEmpty()) {
            throw new IllegalStateException("Aucun souverain grand-ducal extrait depuis " + htmlFile);
        }

        // Création de l'entité commune Position : Grand-Duc de Luxembourg
        FtmEntity position = dataset.newEntity("Position", "position-grand-duc");
        position.addValue("name", POSITION_NAME);
        position.addValue("country", "lu");
        position.addValue("organization", INSTITUTION_NAME);
        position.addValue("summary", "Chef de l'État du Grand-Duché de Luxembourg (catégorie LU-a)");
        dataset.addEntity(position);

        for (MonarchData m : monarchs) {
            convertMonarch(m, position, sourceUrl, dataset);
        }

        log.info("Grand-Duc : {} souverain(s) extrait(s)", monarchs.size());
    }

    /**
     * Extrait les souverains depuis le document HTML.
     */
    static List<MonarchData> extractMonarchs(
            String html,
            String sourceUrl,
            boolean includeFormer,
            LocalDate oldestEndDate) {

        Map<String, MonarchData> result = new LinkedHashMap<>();

        // 1. Détection du sujet principal de la page
        String h1 = extractFirstMatch(html, H1_PATTERN);
        String lowerHtml = html.toLowerCase(Locale.ROOT);

        boolean isHenriPage = (h1 != null && h1.toLowerCase(Locale.ROOT).contains("henri"))
                || (sourceUrl != null && sourceUrl.contains("henri"))
                || (lowerHtml.contains("abdication après 25 ans") && !lowerHtml.contains("nouveau grand-duc de luxembourg"));

        // Extraction des métadonnées de la page principale
        String birthDate = parseBirthDate(html);
        String birthPlace = parseBirthPlace(html);
        String imageUrl = extractFirstMatch(html, OG_IMAGE_PATTERN);

        if (isHenriPage) {
            // Fiche dédiée au Grand-Duc Henri
            KnownMonarch henriRef = KNOWN_MONARCHS.get("henri");
            MonarchData henri = new MonarchData(
                    "henri",
                    henriRef.fullName(),
                    henriRef.firstName(),
                    henriRef.lastName(),
                    birthDate != null ? birthDate : henriRef.birthDate(),
                    birthPlace != null ? birthPlace : henriRef.birthPlace(),
                    henriRef.startDate(),
                    henriRef.endDate(),
                    "ended",
                    sourceUrl,
                    imageUrl,
                    henriRef.aliases()
            );
            if (isEligible(henri.endDate(), oldestEndDate)) {
                result.put(henri.slug(), henri);
            }
        } else {
            // Page officielle du Grand-Duc régnant (Guillaume)
            KnownMonarch guillaumeRef = KNOWN_MONARCHS.get("guillaume");

            // Détection dynamique de la prestation de serment dans le texte : "Le 3 octobre 2025, il prête serment..."
            LocalDate startDate = detectOathDate(html, guillaumeRef.startDate());

            MonarchData guillaume = new MonarchData(
                    "guillaume",
                    guillaumeRef.fullName(),
                    guillaumeRef.firstName(),
                    guillaumeRef.lastName(),
                    birthDate != null ? birthDate : guillaumeRef.birthDate(),
                    birthPlace != null ? birthPlace : guillaumeRef.birthPlace(),
                    startDate,
                    null,
                    "current",
                    sourceUrl,
                    imageUrl,
                    guillaumeRef.aliases()
            );
            result.put(guillaume.slug(), guillaume);

            // 2. Si includeFormer est activé, inclure également le Grand-Duc précédent (Henri) selon la rétention PEP
            if (includeFormer) {
                KnownMonarch henriRef = KNOWN_MONARCHS.get("henri");
                if (isEligible(henriRef.endDate(), oldestEndDate)) {
                    MonarchData henri = new MonarchData(
                            "henri",
                            henriRef.fullName(),
                            henriRef.firstName(),
                            henriRef.lastName(),
                            henriRef.birthDate(),
                            henriRef.birthPlace(),
                            henriRef.startDate(),
                            henriRef.endDate(),
                            "ended",
                            "https://monarchie.lu/fr/la-monarchie/les-anciens-souverains/le-grand-duc-henri",
                            null,
                            henriRef.aliases()
                    );
                    result.put(henri.slug(), henri);
                }
            }
        }

        return new ArrayList<>(result.values());
    }

    private static void convertMonarch(
            MonarchData m,
            FtmEntity position,
            String sourceUrl,
            FtmDatasetBuilder dataset) {

        // Person
        FtmEntity person = dataset.newEntity("Person", m.slug());
        person.addValue("name", m.fullName());
        person.addValue("firstName", m.firstName());
        person.addValue("lastName", m.lastName());
        if (m.birthDate() != null && !m.birthDate().isBlank()) {
            person.addValue("birthDate", m.birthDate());
        }
        if (m.birthPlace() != null && !m.birthPlace().isBlank()) {
            person.addValue("birthPlace", m.birthPlace());
        }
        person.addValue("country", "lu");
        person.addValue("nationality", "lu");
        person.addValue("sourceUrl", m.pageUrl() != null ? m.pageUrl() : sourceUrl);

        for (String alias : m.aliases()) {
            person.addValue("alias", alias);
        }

        dataset.addEntity(person);

        // Occupancy
        String occId = "occ-" + m.slug();
        FtmEntity occ = dataset.newEntity("Occupancy", occId);
        occ.addValue("holder", person.getId());
        occ.addValue("post", position.getId());
        occ.addValue("description", POSITION_NAME);
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

    /**
     * Tente de détecter la date de prestation de serment dans le texte HTML.
     */
    static LocalDate detectOathDate(String html, LocalDate fallback) {
        if (html == null) return fallback;
        Pattern p = Pattern.compile("(?i)Le\\s+(\\d{1,2})\\s+([a-zA-Zûéèê]+)\\s+(\\d{4}),?\\s+il\\s+pr[êe]te\\s+serment");
        Matcher m = p.matcher(html);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            String monthStr = m.group(2).toLowerCase(Locale.ROOT);
            int year = Integer.parseInt(m.group(3));
            String monthNum = MONTHS_FR.get(monthStr);
            if (monthNum != null) {
                return LocalDate.of(year, Integer.parseInt(monthNum), day);
            }
        }
        return fallback;
    }

    /**
     * Analyse la date de naissance dans le texte HTML.
     */
    static String parseBirthDate(String html) {
        if (html == null) return null;
        Matcher m = BIRTH_DATE_PATTERN.matcher(html);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            String monthStr = m.group(2).toLowerCase(Locale.ROOT);
            int year = Integer.parseInt(m.group(3));
            String monthNum = MONTHS_FR.get(monthStr);
            if (monthNum != null) {
                return String.format("%04d-%s-%02d", year, monthNum, day);
            }
        }
        return null;
    }

    /**
     * Analyse le lieu de naissance dans le texte HTML.
     */
    static String parseBirthPlace(String html) {
        if (html == null) return null;
        Matcher m = BIRTH_PLACE_PATTERN.matcher(html);
        if (m.find()) {
            String city1 = m.group(1);
            if (city1 != null && !city1.isBlank()) {
                return city1.trim();
            }
            String city2 = m.group(2);
            if (city2 != null && !city2.isBlank()) {
                return city2.trim();
            }
        }
        return null;
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

    /**
     * Résout l'URL source dynamique depuis le ParserContext.
     */
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

    static boolean isIncludeFormer(Map<String, String> parameters) {
        if (parameters == null) {
            return false;
        }
        String val = parameters.get("includeFormer");
        return "true".equalsIgnoreCase(val) || "1".equals(val);
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

    private static String extractFirstMatch(String text, Pattern pattern) {
        if (text == null) return null;
        Matcher m = pattern.matcher(text);
        return m.find() ? stripHtml(m.group(1)).trim() : null;
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

    /**
     * Données d'un souverain extrait.
     */
    record MonarchData(
            String slug,
            String fullName,
            String firstName,
            String lastName,
            String birthDate,
            String birthPlace,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String pageUrl,
            String imageUrl,
            List<String> aliases) {
    }
}
