package com.swiftcheck.aml.plugins.lupep.cssf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>lu_pep_cssf</b> – Commission de Surveillance du Secteur Financier (cssf.lu).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-i</b> (Dirigeants des autorités de régulation,
 * JO C/2023/724), fonction <b>Direction de la CSSF</b> (Directeur général et Directeurs).
 *
 * <p>L'extraction est <b>100 % dynamique</b> sans liste ni nom en dur dans le code :
 * analyse de la section « La Direction » de la page officielle de gouvernance de la CSSF.
 *
 * <p>L'URL source est résolue dynamiquement depuis le {@link ParserContext} (paramètres {@code sourceUrl},
 * {@code url} ou {@code sourceLocation}), avec repli par défaut sur {@value #DEFAULT_SOURCE_URL}.
 */
@Extension
public class LuPepCssfParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepCssfParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-cssf";

    /** URL source officielle par défaut : page de gouvernance / direction de la CSSF. */
    public static final String DEFAULT_SOURCE_URL = "https://www.cssf.lu/fr/gouvernance/";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    // Intitulé et clé de la Position officielle
    static final String POS_KEY = "director";
    static final String POS_NAME = "Directeur général ou directeur de la Commission de Surveillance du Secteur Financier";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        log.info("Analyse dynamique de la Commission de Surveillance du Secteur Financier (sourceUrl : {})", sourceUrl);

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
     * Lit le fichier HTML source et génère les entités FollowTheMoney dynamiquement.
     */
    void convert(Path htmlFile, String sourceUrl, LocalDate oldestEndDate, FtmDatasetBuilder dataset) throws Exception {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<CssfOfficialRecord> list = extractOfficials(html, sourceUrl);

        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant extrait de la CSSF depuis " + htmlFile);
        }

        // 1. Déclarer la Position officielle une seule fois
        FtmEntity position = dataset.newEntity("Position", POS_KEY);
        position.addValue("name", POS_NAME);
        position.addValue("country", "lu");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.financial");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        Set<String> createdPersonIds = new HashSet<>();

        for (CssfOfficialRecord off : list) {
            String personId = slugify(off.fullName);

            if (createdPersonIds.add(personId)) {
                FtmEntity person = dataset.newEntity("Person", personId);
                person.addValue("name", off.fullName);
                if (off.firstName != null && !off.firstName.isBlank()) {
                    person.addValue("firstName", off.firstName);
                }
                if (off.lastName != null && !off.lastName.isBlank()) {
                    person.addValue("lastName", off.lastName);
                }
                person.addValue("country", "lu");
                person.addValue("sourceUrl", sourceUrl);
                dataset.addEntity(person);
            }

            FtmEntity occupancy = dataset.newEntity("Occupancy", personId, POS_KEY);
            occupancy.addValue("holder", personId);
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            if (off.roleDescription != null && !off.roleDescription.isBlank()) {
                occupancy.addValue("description", off.roleDescription);
            }
            dataset.addEntity(occupancy);
        }
    }

    /**
     * Analyse le contenu HTML et extrait dynamiquement les membres de la Direction.
     */
    static List<CssfOfficialRecord> extractOfficials(String html, String sourceUrl) {
        if (html == null || html.isBlank()) {
            return Collections.emptyList();
        }

        List<CssfOfficialRecord> result = new ArrayList<>();

        // 1. Localiser la section "La Direction" ou "Executive Board" jusqu'au titre suivant
        Pattern sectionPattern = Pattern.compile("(?si)<h[1-6][^>]*>\\s*(?:La\\s+)?(?:Direction|Executive\\s+Board)\\s*</h[1-6]>(.*?)(?:<h[1-2]|$|Organisation\\s+des\\s+d[ée]partements)");
        Matcher sM = sectionPattern.matcher(html);
        String directionHtml = sM.find() ? sM.group(1) : html;

        // 2. Détection par cartes de membres : <h3...>Nom</h3...> suivi de <p...>Rôle</p...>
        Pattern cardPattern = Pattern.compile("(?si)<h[3-6][^>]*>\\s*([\\p{L}\\s\\-]+?)\\s*</h[3-6]>\\s*<p[^>]*>\\s*(Direct(?:eur|rice)(?:\\s+g[ée]n[ée]ral[e]?)?)\\s*</p>");
        Matcher cardM = cardPattern.matcher(directionHtml);
        while (cardM.find()) {
            String name = cleanExtractedText(cardM.group(1));
            String role = cleanExtractedText(cardM.group(2));
            if (isValidPersonName(name)) {
                addOfficial(name, role, result);
            }
        }

        // 3. Détection inverse : <p...>Rôle</p...> suivi de <h3...>Nom</h3...>
        if (result.isEmpty()) {
            Pattern invCardPattern = Pattern.compile("(?si)<p[^>]*>\\s*(Direct(?:eur|rice)(?:\\s+g[ée]n[ée]ral[e]?)?)\\s*</p>\\s*<h[3-6][^>]*>\\s*([\\p{L}\\s\\-]+?)\\s*</h[3-6]>");
            Matcher invM = invCardPattern.matcher(directionHtml);
            while (invM.find()) {
                String role = cleanExtractedText(invM.group(1));
                String name = cleanExtractedText(invM.group(2));
                if (isValidPersonName(name)) {
                    addOfficial(name, role, result);
                }
            }
        }

        // 4. Détection par lignes ou éléments de liste : "Nom - Directeur" ou "Nom, Directeur général"
        if (result.isEmpty()) {
            Pattern linePattern = Pattern.compile("(?im)^\\s*(?:<li[^>]*>)?\\s*(?:<strong>)?\\s*([\\p{L}\\s\\-]+?)\\s*(?:</strong>)?\\s*[–—\\-:,]\\s*(Direct(?:eur|rice)(?:\\s+g[ée]n[ée]ral[e]?)?)(?:</li>|$)");
            Matcher lineM = linePattern.matcher(directionHtml);
            while (lineM.find()) {
                String name = cleanExtractedText(lineM.group(1));
                String role = cleanExtractedText(lineM.group(2));
                if (isValidPersonName(name)) {
                    addOfficial(name, role, result);
                }
            }
        }

        // 5. Fallback par analyse séquentielle de texte nettoyé
        if (result.isEmpty()) {
            String clean = directionHtml.replaceAll("(?i)<br\\s*/?>", "\n")
                    .replaceAll("(?i)</(?:p|div|li|tr|h[1-6])>", "\n")
                    .replace("&eacute;", "é")
                    .replace("&egrave;", "è")
                    .replace("&agrave;", "à")
                    .replace("&nbsp;", " ")
                    .replace("&rsquo;", "'")
                    .replace("&#39;", "'")
                    .replaceAll("<[^>]+>", "");

            String[] lines = clean.split("\\r?\\n");
            String previousName = null;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                if (isCssfDirectionRole(trimmed) && previousName != null) {
                    addOfficial(previousName, trimmed, result);
                    previousName = null;
                } else if (isValidPersonName(trimmed)) {
                    previousName = trimmed;
                } else {
                    previousName = null;
                }
            }
        }

        return result;
    }

    private static boolean isCssfDirectionRole(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals("directeur")
                || lower.equals("directrice")
                || lower.equals("directeur général")
                || lower.equals("directrice générale")
                || lower.startsWith("directeur")
                || lower.startsWith("directrice");
    }

    private static String cleanExtractedText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isValidPersonName(String name) {
        if (name == null || name.isBlank() || name.length() < 3) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("direction") || lower.contains("conseil") || lower.contains("cssf")
                || lower.contains("gouvernance") || lower.contains("@") || lower.contains("administration")) {
            return false;
        }
        return name.contains(" ") && name.matches("[\\p{L}\\s\\-]+");
    }

    private static void addOfficial(String fullName, String role, List<CssfOfficialRecord> list) {
        String cleanedName = fullName.replaceAll("\\s+", " ").trim();
        // Éviter d'ajouter deux fois la même personne si plusieurs blocs matchent
        if (list.stream().anyMatch(r -> r.fullName.equalsIgnoreCase(cleanedName))) {
            return;
        }
        String[] parts = splitFullName(cleanedName);
        CssfOfficialRecord rec = new CssfOfficialRecord();
        rec.fullName = cleanedName;
        rec.firstName = parts[0];
        rec.lastName = parts[1];
        rec.roleDescription = role;
        list.add(rec);
    }

    private static String[] splitFullName(String fullName) {
        String cleaned = fullName.trim().replaceAll("\\s+", " ");
        String[] words = cleaned.split(" ");
        if (words.length == 1) {
            return new String[] { "", words[0] };
        }
        if (words.length == 2) {
            return new String[] { words[0], words[1] };
        }
        StringBuilder first = new StringBuilder(words[0]);
        StringBuilder last = new StringBuilder();
        for (int i = 1; i < words.length; i++) {
            if (last.length() > 0) last.append(' ');
            last.append(words[i]);
        }
        return new String[] { first.toString(), last.toString() };
    }

    static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    static String resolveSourceUrl(ParserContext ctx) {
        if (ctx != null && ctx.parameters() != null) {
            String u = ctx.parameters().get("sourceUrl");
            if (u == null || u.isBlank()) u = ctx.parameters().get("url");
            if (u == null || u.isBlank()) u = ctx.parameters().get("sourceLocation");
            if (u != null && !u.isBlank()) return u.trim();
        }
        return DEFAULT_SOURCE_URL;
    }

    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() != null) {
            return ctx.inputFile();
        }
        throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName());
    }

    private static LocalDate oldestEndDate(Map<String, String> parameters, LocalDate now) {
        if (parameters == null) {
            return null;
        }
        String y = parameters.get("yearsAfterEnd");
        if (y == null || y.isBlank()) {
            return null;
        }
        try {
            int years = Integer.parseInt(y.trim());
            return now.minusYears(years);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static class CssfOfficialRecord {
        String fullName;
        String firstName;
        String lastName;
        String roleDescription;
    }
}
