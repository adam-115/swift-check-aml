package com.swiftcheck.aml.plugins.lupep.governmentformer;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>lu_pep_government_former</b> – anciens membres du gouvernement du Luxembourg (catégorie a : Premier ministre,
 * ministres et secrétaires d'État ayant exercé des fonctions gouvernementales selon l'article 76 de la Constitution).
 *
 * <p>Source : page officielle « Anciens membres du gouvernement » sur
 * {@code https://gouvernement.lu/fr/gouvernement/anciens-membres-gouvernement.html}.
 * Type {@code HTTP_FILE} (typologie T5 - page web) : le core télécharge la page web à l'adresse configurée
 * dans le parser ; ce plugin lit {@code ctx.inputFile()} et écrit le NDJSON.
 *
 * <p>La page présente un accordéon des membres sous la section « Par membres du gouvernement » :
 * <pre>{@code
 * <details class="cmp-accordion__item">
 *   <summary class="cmp-accordion__summary">
 *     <h3 class="cmp-accordion__header">ARENDT Guy</h3>
 *   </summary>
 *   <div class="cmp-accordion__panel">
 *     <ul>
 *       <li><a ...>Secrétaire d’État à la Culture, du 18.12.2015 au 05.12.2018</a></li>
 *     </ul>
 *   </div>
 * </details>
 * }</pre>
 *
 * <p>Pour chaque personne et chaque mandat gouvernemental, trois entités FollowTheMoney :
 * <ul>
 *   <li>{@code Person} : prénom, nom, pays (lu), topic {@code role.pep} ;</li>
 *   <li>{@code Position} : intitulé du portefeuille ministériel, topics {@code gov.national} et {@code gov.executive} ;</li>
 *   <li>{@code Occupancy} : dates d'effet ({@code startDate}, {@code endDate}) et statut ({@code ended} ou {@code current}).</li>
 * </ul>
 *
 * <p>Paramètre du parser (JSON, facultatif) :
 * {@code {"yearsAfterEnd": "5"}} (garde par défaut les mandats terminés il y a 5 ans ou moins ; {@code "0"} pour tous).
 */
@Extension
public class LuPepGovernmentFormerParser implements Iparser {

    /** Préfixe par défaut des identifiants FtM : les alertes y sont rattachées. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-government-former";

    /** URL source par défaut si non renseignée dans les paramètres de ParserContext. */
    static final String DEFAULT_SOURCE_URL = "https://gouvernement.lu/fr/gouvernement/anciens-membres-gouvernement.html";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    /** Expression pour isoler chaque bloc membre dans l'accordéon HTML. */
    private static final Pattern ACCORDION_ITEM = Pattern.compile(
            "(?s)<details[^>]*class=\"[^\"]*cmp-accordion__item[^\"]*\"[^>]*>.*?"
            + "<h3[^>]*class=\"[^\"]*cmp-accordion__header[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</h3>(.*?)</details>");

    /** Expression pour extraire chaque élément de mandat de la liste ou du paragraphe HTML. */
    private static final Pattern MANDATE_ITEM = Pattern.compile("(?s)<(?:li|p)[^>]*>(.*?)</(?:li|p)>");

    /** Motif pour un mandat terminé : "... [,] du <debut> au <fin>". */
    private static final Pattern ENDED_MANDATE = Pattern.compile(
            "^(.*?)(?:,\\s*|\\s+)du\\s+(\\d{1,2}(?:\\.|\\s+[^\\s\\d]+\\s+)\\d{1,2}(?:\\.|\\s+)?\\d{4})\\s+au\\s+(\\d{1,2}(?:\\.|\\s+[^\\s\\d]+\\s+)\\d{1,2}(?:\\.|\\s+)?\\d{4})$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Motif pour un mandat en cours : "... depuis le <debut>" ou "... le <debut>". */
    private static final Pattern CURRENT_MANDATE = Pattern.compile(
            "^(.*?)\\s+(?:depuis\\s+le|le)\\s+(\\d{1,2}\\.\\d{1,2}\\.\\d{4})$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Correspondance des mois français pour les dates écrites en toutes lettres. */
    private static final Map<String, String> FRENCH_MONTHS = Map.ofEntries(
            Map.entry("janvier", "01"),
            Map.entry("février", "02"),
            Map.entry("fevrier", "02"),
            Map.entry("mars", "03"),
            Map.entry("avril", "04"),
            Map.entry("mai", "05"),
            Map.entry("juin", "06"),
            Map.entry("juillet", "07"),
            Map.entry("août", "08"),
            Map.entry("aout", "08"),
            Map.entry("septembre", "09"),
            Map.entry("octobre", "10"),
            Map.entry("novembre", "11"),
            Map.entry("décembre", "12"),
            Map.entry("decembre", "12"));

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        String sourceUrl = resolveSourceUrl(ctx);
        convert(sourceFile(ctx), oldestEndDate, sourceUrl, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    /** Test du parser : chaque ancien membre, avec l'ID et le nom que Yente doit indexer. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        String sourceUrl = resolveSourceUrl(ctx);
        convert(sourceFile(ctx), oldestEndDate, sourceUrl, dataset);
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
     * Lit le fichier HTML et convertit chaque membre et ses mandats en entités FollowTheMoney.
     *
     * @param html          chemin du fichier HTML téléchargé
     * @param oldestEndDate date de fin minimale pour inclure un mandat ({@code null} pour tout garder)
     * @param sourceUrl     adresse de la source issue de ParserContext
     * @param dataset       constructeur du jeu de données FtM
     * @throws IllegalStateException si aucun membre n'est trouvé
     */
    void convert(Path html, LocalDate oldestEndDate, String sourceUrl, FtmDatasetBuilder dataset) throws Exception {
        String content = Files.readString(html, StandardCharsets.UTF_8);
        Map<String, MemberData> members = parseMembers(content);
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun ancien membre du gouvernement trouvé : la structure de la page "
                    + "a probablement changé.");
        }

        int retainedMembers = 0;
        for (MemberData member : members.values()) {
            List<MandateData> validMandates = new ArrayList<>();
            for (MandateData mandate : member.mandates) {
                if (oldestEndDate == null || mandate.endDate == null || !mandate.endDate.isBefore(oldestEndDate)) {
                    validMandates.add(mandate);
                }
            }

            if (!validMandates.isEmpty()) {
                retainedMembers++;
                convertMember(member, validMandates, sourceUrl, dataset);
            }
        }

        if (retainedMembers == 0) {
            throw new IllegalStateException("Aucun ancien membre retenu après filtrage par date : vérifiez la période demandée.");
        }
    }

    /** Surcharge de commodité utilisant l'URL par défaut. */
    void convert(Path html, LocalDate oldestEndDate, FtmDatasetBuilder dataset) throws Exception {
        convert(html, oldestEndDate, DEFAULT_SOURCE_URL, dataset);
    }

    /** Convertit une personne et l'ensemble de ses mandats valides. */
    private static void convertMember(MemberData member, List<MandateData> mandates, String sourceUrl, FtmDatasetBuilder dataset) {
        String personSlug = slug(member.fullName);
        FtmEntity person = dataset.newEntity("Person", personSlug);
        person.addValue("name", joinNonBlank(" ", member.firstName, member.lastName));
        person.addValue("firstName", member.firstName);
        person.addValue("lastName", member.lastName);
        person.addValue("country", "lu");
        person.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(person);

        for (MandateData mandate : mandates) {
            String positionSlug = slug(mandate.function);
            FtmEntity position = dataset.newEntity("Position", positionSlug);
            position.addValue("name", capitalize(mandate.function));
            position.addValue("country", "lu");
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.executive");
            dataset.addEntity(position);

            String occupancyKey = mandate.startDate != null ? mandate.startDate.toString()
                    : (mandate.endDate != null ? mandate.endDate.toString() : "mandate");
            FtmEntity occupancy = dataset.newEntity("Occupancy", personSlug, positionSlug, occupancyKey);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            if (mandate.startDate != null) {
                occupancy.addValue("startDate", mandate.startDate.toString());
            }
            if (mandate.endDate != null) {
                occupancy.addValue("endDate", mandate.endDate.toString());
            }
            occupancy.addValue("status", mandate.isCurrent ? "current" : "ended");
            occupancy.addValue("sourceUrl", sourceUrl);
            dataset.addEntity(occupancy);
        }
    }

    /**
     * Extrait l'URL source depuis le contexte (paramètre « sourceUrl », « url » ou URL par défaut).
     */
    static String resolveSourceUrl(ParserContext ctx) {
        if (ctx != null && ctx.parameters() != null) {
            String url = ctx.parameters().get("sourceUrl");
            if (url == null || url.isBlank()) {
                url = ctx.parameters().get("url");
            }
            if (url != null && !url.isBlank()) {
                return url.trim();
            }
        }
        return DEFAULT_SOURCE_URL;
    }

    /** Analyse la page HTML et extrait tous les membres avec leurs mandats. */
    static Map<String, MemberData> parseMembers(String html) {
        Map<String, MemberData> members = new LinkedHashMap<>();
        Matcher itemMatcher = ACCORDION_ITEM.matcher(html);
        while (itemMatcher.find()) {
            String rawHeader = cleanText(itemMatcher.group(1));
            String panelHtml = itemMatcher.group(2);
            if (rawHeader.isBlank()) {
                continue;
            }

            String[] nameParts = splitHeaderName(rawHeader);
            MemberData member = new MemberData(rawHeader, nameParts[0], nameParts[1]);

            Matcher mandateMatcher = MANDATE_ITEM.matcher(panelHtml);
            while (mandateMatcher.find()) {
                String line = cleanText(mandateMatcher.group(1));
                if (line.isBlank()) {
                    continue;
                }
                MandateData mandate = parseMandateLine(line);
                if (mandate != null) {
                    member.mandates.add(mandate);
                }
            }

            if (!member.mandates.isEmpty()) {
                members.put(rawHeader, member);
            }
        }
        return members;
    }

    /**
     * Analyse une ligne de mandat pour en extraire la fonction, les dates et le statut.
     */
    static MandateData parseMandateLine(String line) {
        Matcher endedMatcher = ENDED_MANDATE.matcher(line);
        if (endedMatcher.find()) {
            String function = endedMatcher.group(1).trim();
            LocalDate start = parseDate(endedMatcher.group(2));
            LocalDate end = parseDate(endedMatcher.group(3));
            return new MandateData(cleanFunction(function), start, end, false);
        }

        Matcher currentMatcher = CURRENT_MANDATE.matcher(line);
        if (currentMatcher.find()) {
            String function = currentMatcher.group(1).trim();
            LocalDate start = parseDate(currentMatcher.group(2));
            return new MandateData(cleanFunction(function), start, null, true);
        }

        // Ligne sans motif de date reconnu : on garde la fonction avec des dates indéterminées
        return new MandateData(cleanFunction(line), null, null, false);
    }

    /** Parse une date au format "DD.MM.YYYY" ou "D mois YYYY". */
    static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text.trim();
        Matcher dotDate = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$").matcher(cleaned);
        if (dotDate.matches()) {
            int day = Integer.parseInt(dotDate.group(1));
            int month = Integer.parseInt(dotDate.group(2));
            int year = Integer.parseInt(dotDate.group(3));
            return LocalDate.of(year, month, day);
        }

        Matcher textDate = Pattern.compile("^(\\d{1,2})\\s+([^\\s\\d]+)\\s+(\\d{4})$").matcher(cleaned);
        if (textDate.matches()) {
            int day = Integer.parseInt(textDate.group(1));
            String monthName = normalizeKey(textDate.group(2));
            int year = Integer.parseInt(textDate.group(3));
            String monthStr = FRENCH_MONTHS.get(monthName);
            if (monthStr != null) {
                return LocalDate.of(year, Integer.parseInt(monthStr), day);
            }
        }
        return null;
    }

    /**
     * Découpe « ARENDT Guy » ou « HENNICOT-SCHOEPGES Erna » :
     * les mots tout en majuscules forment le nom de famille, le reste est le prénom.
     *
     * @return [prénom, nom]
     */
    static String[] splitHeaderName(String header) {
        String[] words = header.trim().split("\\s+");
        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();
        boolean hasUpper = false;
        for (String word : words) {
            String alphaOnly = word.replaceAll("[^\\p{L}]", "");
            if (alphaOnly.length() > 1 && alphaOnly.equals(alphaOnly.toUpperCase(Locale.FRENCH))) {
                hasUpper = true;
            }
        }

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            String alphaOnly = word.replaceAll("[^\\p{L}]", "");
            boolean isLast = hasUpper
                    ? (alphaOnly.length() > 1 && alphaOnly.equals(alphaOnly.toUpperCase(Locale.FRENCH)))
                    : (i == 0); // par défaut premier mot si pas de majuscules
            StringBuilder target = isLast ? last : first;
            if (target.length() > 0) {
                target.append(' ');
            }
            target.append(word);
        }
        return new String[] { first.length() == 0 ? null : first.toString(), last.toString() };
    }

    /** Nettoie le HTML, décode les entités et normalise les espaces. */
    static String cleanText(String htmlSnippet) {
        String withoutTags = htmlSnippet.replaceAll("<[^>]+>", " ");
        String decoded = withoutTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        return decoded.replaceAll("\\s+", " ").trim();
    }

    /** Nettoie la ponctuation superflue en fin d'intitulé de fonction. */
    private static String cleanFunction(String function) {
        String cleaned = function.trim();
        while (cleaned.endsWith(",") || cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    /** Génère un slug normalisé sans accents ni caractères spéciaux. */
    static String slug(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text.trim().toLowerCase(Locale.FRENCH), Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static String normalizeKey(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text.trim().toLowerCase(Locale.FRENCH), Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
    }

    private static String capitalize(String text) {
        String t = text.trim();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Date limite d'après le paramètre {@code yearsAfterEnd} (5 ans par défaut ; 0 pour tout garder). */
    static LocalDate oldestEndDate(Map<String, String> parameters, LocalDate today) {
        String years = parameters.getOrDefault("yearsAfterEnd", "5").trim();
        try {
            int y = Integer.parseInt(years);
            return y <= 0 ? null : today.minusYears(y);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre yearsAfterEnd : nombre entier attendu, reçu « " + years + " »");
        }
    }

    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            return ctx.inputFile();
        }
        throw new IllegalStateException("Fichier source introuvable : aucun fichier d'entrée fourni.");
    }

    /** Données d'un membre extrait de la page. */
    static final class MemberData {
        final String fullName;
        final String firstName;
        final String lastName;
        final List<MandateData> mandates = new ArrayList<>();

        MemberData(String fullName, String firstName, String lastName) {
            this.fullName = fullName;
            this.firstName = firstName;
            this.lastName = lastName;
        }
    }

    /** Données d'un mandat gouvernemental. */
    static final class MandateData {
        final String function;
        final LocalDate startDate;
        final LocalDate endDate;
        final boolean isCurrent;

        MandateData(String function, LocalDate startDate, LocalDate endDate, boolean isCurrent) {
            this.function = function;
            this.startDate = startDate;
            this.endDate = endDate;
            this.isCurrent = isCurrent;
        }
    }
}
