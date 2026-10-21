package com.swiftcheck.aml.plugins.lupep.ilr;

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
 * <b>lu_pep_ilr</b> – Institut Luxembourgeois de Régulation (ilr.lu).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-i</b> (Dirigeants des autorités de régulation,
 * JO C/2023/724), fonction <b>Direction de l'ILR</b> (Directeur, Directeur adjoint, Directrice adjointe).
 *
 * <p>L'extraction est <b>100 % dynamique</b> sans liste ni nom en dur dans le code :
 * analyse de la section « La Direction » de la page officielle d'équipe de l'ILR.
 *
 * <p>L'URL source est résolue dynamiquement depuis le {@link ParserContext} (paramètres {@code sourceUrl},
 * {@code url} ou {@code sourceLocation}), avec repli par défaut sur {@value #DEFAULT_SOURCE_URL}.
 */
@Extension
public class LuPepIlrParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepIlrParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-ilr";

    /** URL source officielle par défaut : page de l'équipe / direction de l'ILR. */
    public static final String DEFAULT_SOURCE_URL = "https://www.ilr.lu/a-propos/equipe/";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    // Intitulé et clé de la Position officielle
    static final String POS_KEY = "director";
    static final String POS_NAME = "Directeur ou directeur adjoint de l'Institut Luxembourgeois de Régulation";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        log.info("Analyse dynamique de l'Institut Luxembourgeois de Régulation (sourceUrl : {})", sourceUrl);

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
        List<IlrOfficialRecord> list = extractOfficials(html, sourceUrl);

        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun membre de la direction extrait de l'ILR depuis " + htmlFile);
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

        for (IlrOfficialRecord off : list) {
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
    static List<IlrOfficialRecord> extractOfficials(String html, String sourceUrl) {
        if (html == null || html.isBlank()) {
            return Collections.emptyList();
        }

        List<IlrOfficialRecord> result = new ArrayList<>();

        // 1. Localiser la section "La Direction" jusqu'à la section suivante (ex. "Secrétariat de Direction")
        Pattern sectionPattern = Pattern.compile("(?si)<h[1-6][^>]*>\\s*La\\s+Direction\\s*</h[1-6]>(.*?)(?:<h[1-6]|$)");
        Matcher sM = sectionPattern.matcher(html);
        String directionHtml = sM.find() ? sM.group(1) : html;

        // 2. Nettoyage du bloc Direction
        String clean = directionHtml.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(?:p|div|li|tr|h[1-6])>", "\n")
                .replace("&eacute;", "é")
                .replace("&egrave;", "è")
                .replace("&agrave;", "à")
                .replace("&nbsp;", " ")
                .replace("&rsquo;", "'")
                .replace("&#39;", "'")
                .replaceAll("<[^>]+>", "");

        // 3. Détection par blocs de lignes consécutives : Titre (Directeur / Directrice...) suivi du Nom
        String[] lines = clean.split("\\r?\\n");
        String currentRole = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Détection d'un intitulé de fonction de direction
            if (isDirectionRole(trimmed)) {
                currentRole = trimmed;
                continue;
            }

            // Si un rôle de direction précède, la ligne suivante contient le nom de la personne
            if (currentRole != null) {
                if (isValidPersonName(trimmed)) {
                    addOfficial(trimmed, currentRole, result);
                }
                currentRole = null;
            }
        }

        // 4. Fallback générique : recherche directe par regex d'un rôle suivi du nom
        if (result.isEmpty()) {
            Pattern fallbackPattern = Pattern.compile("(?i)(Directeur(?:\\s+adjoint)?|Directrice(?:\\s+adjointe)?)\\s*[:\\-]?\\s*([\\p{L}\\s\\-]+)");
            Matcher fbM = fallbackPattern.matcher(clean);
            while (fbM.find()) {
                String role = fbM.group(1).trim();
                String name = fbM.group(2).trim();
                if (isValidPersonName(name)) {
                    addOfficial(name, role, result);
                }
            }
        }

        return result;
    }

    private static boolean isDirectionRole(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals("directeur")
                || lower.equals("directrice")
                || lower.equals("directeur adjoint")
                || lower.equals("directrice adjointe")
                || lower.startsWith("directeur ")
                || lower.startsWith("directrice ");
    }

    private static boolean isValidPersonName(String name) {
        if (name == null || name.isBlank() || name.length() < 3) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        // Exclure téléphones, adresses, mentions administratives
        if (lower.startsWith("tél") || lower.startsWith("tel") || lower.contains("@") || lower.contains("secrétariat") || lower.contains("institut")) {
            return false;
        }
        // Un nom de personne doit comporter au moins prénom + nom
        return name.contains(" ") && name.matches("[\\p{L}\\s\\-]+");
    }

    private static void addOfficial(String fullName, String role, List<IlrOfficialRecord> list) {
        String cleanedName = fullName.replaceAll("\\s+", " ").trim();
        String[] parts = splitFullName(cleanedName);
        IlrOfficialRecord rec = new IlrOfficialRecord();
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

    static class IlrOfficialRecord {
        String fullName;
        String firstName;
        String lastName;
        String roleDescription;
    }
}
