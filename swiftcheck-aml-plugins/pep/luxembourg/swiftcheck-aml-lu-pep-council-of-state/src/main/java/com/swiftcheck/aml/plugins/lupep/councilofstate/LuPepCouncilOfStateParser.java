package com.swiftcheck.aml.plugins.lupep.councilofstate;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
 * <b>lu_pep_council_of_state</b> – membres du Conseil d'État du Luxembourg (catégorie b : Parlementaires
 * et Conseil d'État, liste officielle JO C/2023/724).
 *
 * <p>Le Conseil d'État luxembourgeois comprend 21 conseillers d'État en fonction, nommés par le Grand-Duc.
 * Le mandat est limité à 12 ans maximum ou jusqu'à l'âge de 72 ans.
 *
 * <p>Ce parser extrait :
 * <ul>
 *   <li>Les conseillers actuellement en fonction (statut {@code current}) ;</li>
 *   <li>Les anciens conseillers récents démissionnaires ou remplacés (statut {@code ended}),
 *       filtrés selon le paramètre {@code yearsAfterEnd} (5 ans par défaut).</li>
 * </ul>
 *
 * <p>Pour chaque conseiller, il produit trois entités FollowTheMoney :
 * <ul>
 *   <li>{@code Person} : nom, prénom, pays (lu), date de naissance si présente, topic {@code role.pep} ;</li>
 *   <li>{@code Position} : fonction institutionnelle (Président, Vice-Président, Membre du Conseil d'État),
 *       topics {@code gov.national} et {@code gov.legislative} ;</li>
 *   <li>{@code Occupancy} : dates d'effet ({@code startDate}, {@code endDate}) et statut ({@code current} ou {@code ended}).</li>
 * </ul>
 */
@Extension
public class LuPepCouncilOfStateParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepCouncilOfStateParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-council-of-state";

    /** URL source par défaut si non renseignée dans les paramètres du ParserContext. */
    static final String DEFAULT_SOURCE_URL =
            "https://conseil-etat.public.lu/fr/conseil-d-etat/historique/membresdepuis1857.html";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    /** Durée maximale légale d'un mandat en années. */
    private static final int MAX_MANDATE_YEARS = 12;

    /** Pattern pour extraire chaque item <li> de membre dans la liste ordonnée. */
    private static final Pattern LI_PATTERN = Pattern.compile("(?si)<li[^>]*>(.*?)</li>");

    /** Pattern pour extraire les balises <b>...</b> du nom au début de l'item. */
    private static final Pattern BOLD_PATTERN = Pattern.compile("(?si)<b[^>]*>(.*?)</b>");

    /** Pattern pour la date de naissance : né(e) le DD.MM.YYYY ou DD-MM-YYYY */
    private static final Pattern BIRTH_DATE_PATTERN = Pattern.compile(
            "(?i)n[ée]e?\\s+le\\s+(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})");

    /** Pattern pour la date de nomination : nommé(e) [le] DD.MM.YYYY */
    private static final Pattern NOMINATION_DATE_PATTERN = Pattern.compile(
            "(?i)nomm[ée]e?\\s+(?:le\\s+)?(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})");

    /** Pattern pour la date d'assermentation : assermenté(e) le DD.MM.YYYY */
    private static final Pattern OATH_DATE_PATTERN = Pattern.compile(
            "(?i)asserment[ée]e?\\s+(?:le\\s+)?(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})");

    /** Pattern pour démission : démissionné(e) / démissionnaire [à partir du | le] DD.MM.YYYY */
    private static final Pattern RESIGNATION_PATTERN = Pattern.compile(
            "(?i)(?:d[ée]missionn[ée]e?(?:\\s+sur\\s+sa\\s+demande)?|d[ée]missionnaire)\\s+(?:[àa]\\s+partir\\s+du\\s+|le\\s+)?(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})");

    /** Pattern pour remplacement : remplacé(e) par ... le DD.MM.YYYY */
    private static final Pattern REPLACED_PATTERN = Pattern.compile(
            "(?i)remplac[ée]e?\\s+par\\s+.*?le\\s+(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})");

    /** Pattern pour intervalle historique : - DD.MM.YYYY. */
    private static final Pattern INTERVAL_END_PATTERN = Pattern.compile(
            "(?i)-\\s*(\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})\\.");

    private static final DateTimeFormatter DATE_FMT_DOT = DateTimeFormatter.ofPattern("d.M.yyyy", Locale.FRANCE);
    private static final DateTimeFormatter DATE_FMT_DASH = DateTimeFormatter.ofPattern("d-M-yyyy", Locale.FRANCE);
    private static final DateTimeFormatter DATE_FMT_SLASH = DateTimeFormatter.ofPattern("d/M/yyyy", Locale.FRANCE);

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        String sourceUrl = resolveSourceUrl(ctx);

        log.info("Analyse du Conseil d'État luxembourgeois (rétention : {} ans, URL : {})",
                parseYearsAfterEnd(ctx.parameters()), sourceUrl);

        convert(sourceFile(ctx), oldestEndDate, sourceUrl, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

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
     * Lit le fichier HTML et convertit chaque membre en entités FollowTheMoney.
     */
    void convert(Path html, LocalDate oldestEndDate, String sourceUrl, FtmDatasetBuilder dataset) throws Exception {
        List<CouncilMember> members = extractMembers(html, oldestEndDate);
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre du Conseil d'État extrait : la structure de la page a probablement changé.");
        }

        for (CouncilMember m : members) {
            convertMember(m, sourceUrl, dataset);
        }

        log.info("Conseil d'État : {} membres retenus et convertis", members.size());
    }

    /** Convertit un conseiller en entités Person, Position et Occupancy. */
    private static void convertMember(CouncilMember m, String sourceUrl, FtmDatasetBuilder dataset) {
        String personSlug = slug(m.fullName);
        FtmEntity person = dataset.newEntity("Person", personSlug);
        person.addValue("name", m.displayName);
        if (!m.displayName.equals(m.fullName)) {
            person.addValue("name", m.fullName); // Nom Prénom comme alias
        }
        person.addValue("firstName", m.firstName);
        person.addValue("lastName", m.lastName);
        if (m.birthDate != null) {
            person.addValue("birthDate", m.birthDate.toString());
        }
        person.addValue("country", "lu");
        person.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(person);

        String positionSlug = slug(m.positionTitle);
        FtmEntity position = dataset.newEntity("Position", positionSlug);
        position.addValue("name", m.positionTitle);
        position.addValue("country", "lu");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        String occDate = m.startDate != null ? m.startDate.toString()
                : (m.endDate != null ? m.endDate.toString() : "mandate");
        String occupancySlug = personSlug + "-" + positionSlug + "-" + occDate;
        FtmEntity occupancy = dataset.newEntity("Occupancy", occupancySlug);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        if (m.startDate != null) {
            occupancy.addValue("startDate", m.startDate.toString());
        }
        if (m.endDate != null) {
            occupancy.addValue("endDate", m.endDate.toString());
        }
        occupancy.addValue("status", m.status);
        occupancy.addValue("sourceUrl", sourceUrl);
        dataset.addEntity(occupancy);
    }

    /**
     * Extrait les membres depuis la page HTML, en filtrant selon la date de fin de mandat.
     */
    static List<CouncilMember> extractMembers(Path html, LocalDate oldestEndDate) throws IOException {
        String content = Files.readString(html, StandardCharsets.UTF_8);
        Matcher liMatcher = LI_PATTERN.matcher(content);

        Map<String, CouncilMember> membersById = new LinkedHashMap<>();
        LocalDate now = LocalDate.now();
        LocalDate termLimitCutoff = now.minusYears(MAX_MANDATE_YEARS);

        while (liMatcher.find()) {
            String liHtml = liMatcher.group(1);

            // Extraire le nom depuis les balises <b>
            Matcher bMatcher = BOLD_PATTERN.matcher(liHtml);
            StringBuilder nameBuilder = new StringBuilder();
            while (bMatcher.find()) {
                String boldText = stripHtml(bMatcher.group(1)).trim();
                if (!boldText.isEmpty()) {
                    if (nameBuilder.length() > 0) {
                        nameBuilder.append(" ");
                    }
                    nameBuilder.append(boldText);
                }
            }

            String rawName = nameBuilder.toString().replaceAll("[,;:]+$", "").trim();
            if (rawName.isEmpty()) {
                continue;
            }

            String bodyText = stripHtml(liHtml).trim();

            LocalDate birthDate = extractDate(bodyText, BIRTH_DATE_PATTERN);
            LocalDate startDate = extractDate(bodyText, NOMINATION_DATE_PATTERN);
            if (startDate == null) {
                startDate = extractDate(bodyText, OATH_DATE_PATTERN);
            }

            LocalDate endDate = extractDate(bodyText, RESIGNATION_PATTERN);
            if (endDate == null) {
                endDate = extractDate(bodyText, REPLACED_PATTERN);
            }
            if (endDate == null) {
                endDate = extractDate(bodyText, INTERVAL_END_PATTERN);
            }

            // Détermination du statut : si aucune démission/remplacement/fin n'est mentionnée
            // et que la nomination est contemporaine (depuis 2012), le conseiller est en fonction.
            String status;
            if (endDate != null) {
                status = "ended";
            } else if (startDate != null && startDate.getYear() >= 2012) {
                status = "current";
            } else {
                status = "ended";
                if (startDate != null) {
                    endDate = startDate.plusYears(MAX_MANDATE_YEARS);
                }
            }

            // Filtrage par ancienneté pour les anciens membres
            if ("ended".equals(status) && oldestEndDate != null) {
                if (endDate != null && endDate.isBefore(oldestEndDate)) {
                    continue;
                }
                if (endDate == null && startDate != null && startDate.plusYears(MAX_MANDATE_YEARS).isBefore(oldestEndDate)) {
                    continue;
                }
            }

            // Détermination du rôle institutionnel :
            // Pour un membre en fonction, on distingue le Président et les Vice-Présidents.
            String positionTitle;
            if ("current".equals(status)) {
                if (Pattern.compile("(?i)nomm[ée]\\s+Pr[ée]sident\\s+le").matcher(bodyText).find()) {
                    positionTitle = "Président du Conseil d'État";
                } else if (Pattern.compile("(?i)nomm[ée]\\s+Vice-Pr[ée]sident\\s+le").matcher(bodyText).find()) {
                    positionTitle = "Vice-Président du Conseil d'État";
                } else {
                    positionTitle = "Membre du Conseil d'État";
                }
            } else {
                positionTitle = "Membre du Conseil d'État";
            }

            ParsedName parsedName = parseName(rawName);

            CouncilMember member = new CouncilMember(
                    rawName,
                    parsedName.displayName,
                    parsedName.firstName,
                    parsedName.lastName,
                    birthDate,
                    startDate,
                    endDate,
                    status,
                    positionTitle
            );

            membersById.put(member.fullName, member);
        }

        return new ArrayList<>(membersById.values());
    }

    /** Découpe un nom sous forme "Nom Prénom" ou "Particule Nom Prénom". */
    static ParsedName parseName(String raw) {
        String clean = raw.replaceAll("\\s+", " ").trim();
        clean = clean.replaceAll(",.*$", "").trim();

        String[] parts = clean.split(" ");
        if (parts.length <= 1) {
            return new ParsedName(clean, clean, clean, clean);
        }

        String lastName;
        String firstName;

        if (parts.length >= 3 && ("Du".equalsIgnoreCase(parts[0]) || "Von".equalsIgnoreCase(parts[0]) || "Van".equalsIgnoreCase(parts[0]))) {
            lastName = parts[0] + " " + parts[1];
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(parts[i]);
            }
            firstName = sb.toString();
        } else if (parts.length >= 4 && "De".equalsIgnoreCase(parts[0]) && "la".equalsIgnoreCase(parts[1])) {
            lastName = parts[0] + " " + parts[1] + " " + parts[2];
            StringBuilder sb = new StringBuilder();
            for (int i = 3; i < parts.length; i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(parts[i]);
            }
            firstName = sb.toString();
        } else {
            lastName = parts[0];
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(parts[i]);
            }
            firstName = sb.toString();
        }

        String displayName = joinNonBlank(" ", firstName, lastName);
        return new ParsedName(clean, displayName, firstName, lastName);
    }

    record ParsedName(String fullName, String displayName, String firstName, String lastName) {}

    record CouncilMember(
            String fullName,
            String displayName,
            String firstName,
            String lastName,
            LocalDate birthDate,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String positionTitle
    ) {}

    private static LocalDate extractDate(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            return parseDate(m.group(1));
        }
        return null;
    }

    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.trim();
        try {
            if (clean.contains(".")) {
                return LocalDate.parse(clean, DATE_FMT_DOT);
            } else if (clean.contains("-")) {
                return LocalDate.parse(clean, DATE_FMT_DASH);
            } else if (clean.contains("/")) {
                return LocalDate.parse(clean, DATE_FMT_SLASH);
            }
        } catch (DateTimeParseException ignored) {
            // Continuer
        }
        return null;
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

    /**
     * Résout dynamiquement l'URL source depuis le contexte (paramètre « sourceUrl », « url », « sourceLocation »
     * ou URL officielle de repli).
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

    /** Génère un slug normalisé pour l'identifiant FollowTheMoney. */
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
}
