package com.swiftcheck.aml.plugins.lupep.army;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 * <b>lu_pep_army</b> – Armée luxembourgeoise (armee.lu / armee.public.lu).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-f</b> (Ambassadeurs, chargés d'affaires et officiers supérieurs,
 * Directive (UE) 2015/849 art. 3 point 9 f), fonction <b>Officier supérieur de l'Armée</b>
 * (Chef d'état-major de l'Armée, Chef d'état-major adjoint, Commandant des Forces).
 *
 * <p>L'extraction est <b>100 % dynamique</b> sans liste ni nom en dur dans le code :
 * analyse de la page officielle du personnel-clé de l'Armée luxembourgeoise.
 *
 * <p>L'URL source est résolue dynamiquement depuis le {@link ParserContext} (paramètres {@code sourceUrl},
 * {@code url} ou {@code sourceLocation}), avec repli par défaut sur {@value #DEFAULT_SOURCE_URL}.
 */
@Extension
public class LuPepArmyParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepArmyParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-army";

    /** URL source officielle par défaut : page personnel-clé de l'Armée luxembourgeoise. */
    public static final String DEFAULT_SOURCE_URL =
            "https://armee.public.lu/fr/armee-luxembourgeoise/personnel/personnel-cle.html";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    // Intitulé et clé de la Position officielle
    static final String POS_KEY = "officier-superieur-armee";
    static final String POS_NAME = "Officier supérieur de l'Armée luxembourgeoise";

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        log.info("Analyse dynamique de l'Armée luxembourgeoise (sourceUrl : {})", sourceUrl);

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
        List<ArmyOfficerRecord> list = extractOfficers(html, LocalDate.now(), oldestEndDate);

        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun officier supérieur extrait de l'Armée depuis " + htmlFile);
        }

        // 1. Déclarer la Position officielle une seule fois
        FtmEntity position = dataset.newEntity("Position", POS_KEY);
        position.addValue("name", POS_NAME);
        position.addValue("country", "lu");
        position.addValue("organization", "Armée luxembourgeoise");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.security");
        dataset.addEntity(position);

        Set<String> createdPersonIds = new HashSet<>();

        for (ArmyOfficerRecord off : list) {
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

            String occKey = personId + "-" + slugify(off.roleDescription) + "-"
                    + (off.startDate != null ? off.startDate.toString() : "current");
            FtmEntity occupancy = dataset.newEntity("Occupancy", occKey);
            occupancy.addValue("holder", personId);
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", off.status);
            if (off.roleDescription != null && !off.roleDescription.isBlank()) {
                occupancy.addValue("description", off.roleDescription);
            }
            if (off.startDate != null) {
                occupancy.addValue("startDate", off.startDate.toString());
            }
            if (off.endDate != null) {
                occupancy.addValue("endDate", off.endDate.toString());
            }
            dataset.addEntity(occupancy);
        }
    }

    /**
     * Analyse le contenu HTML et extrait dynamiquement les officiers supérieurs clés.
     */
    static List<ArmyOfficerRecord> extractOfficers(String html, LocalDate now, LocalDate oldestEndDate) {
        if (html == null || html.isBlank()) {
            return Collections.emptyList();
        }

        List<ArmyOfficerRecord> result = new ArrayList<>();

        // Les 3 grilles d'officiers clés par fonction de commandement :
        // 0: CEMA (Chef d'état-major de l'Armée)
        // 1: ACEM (Chef d'état-major adjoint de l'Armée)
        // 2: ComdF (Commandant des Forces)
        String[] defaultRoles = new String[] {
            "Chef d'état-major de l'Armée",
            "Chef d'état-major adjoint de l'Armée",
            "Commandant des Forces"
        };

        Pattern gridPattern = Pattern.compile("(?is)<ul class=\"cmp-grid[^\"]*\"[^>]*>(.*?)</ul>");
        Matcher gridMatcher = gridPattern.matcher(html);

        Pattern itemPattern = Pattern.compile("(?is)<li class=\"cmp-keyfiguregrid__item\">(.*?)</li>");
        Pattern titlePattern = Pattern.compile("(?is)<p class=\"cmp-keyfiguregrid__item-title\">\\s*(.*?)\\s*</p>");
        Pattern rankPattern = Pattern.compile("(?is)<span class=\"cmp-keyfiguregrid__item-unit\">\\s*(.*?)\\s*</span>");
        Pattern descPattern = Pattern.compile("(?is)<div class=\"cmp-keyfiguregrid__item-desc\">\\s*<p>\\s*(.*?)\\s*</p>");

        int gridIndex = 0;
        while (gridMatcher.find() && gridIndex < defaultRoles.length) {
            String gridHtml = gridMatcher.group(1);
            String role = defaultRoles[gridIndex];
            gridIndex++;

            Matcher itemMatcher = itemPattern.matcher(gridHtml);
            while (itemMatcher.find()) {
                String itemHtml = itemMatcher.group(1);

                String dateStr = "";
                Matcher tm = titlePattern.matcher(itemHtml);
                if (tm.find()) {
                    dateStr = tm.group(1).replaceAll("<[^>]+>", "").trim();
                }

                String rank = "";
                Matcher rm = rankPattern.matcher(itemHtml);
                if (rm.find()) {
                    rank = rm.group(1).replaceAll("<[^>]+>", "").trim();
                }

                String rawName = "";
                Matcher dm = descPattern.matcher(itemHtml);
                if (dm.find()) {
                    rawName = dm.group(1).replaceAll("<[^>]+>", "").trim();
                }

                if (rawName.isBlank()) {
                    continue;
                }

                LocalDate start = null;
                LocalDate end = null;
                if (!dateStr.isBlank()) {
                    String[] parts = dateStr.split("[-–—]");
                    if (parts.length == 1) {
                        start = parseDate(parts[0].trim());
                    } else if (parts.length >= 2) {
                        start = parseDate(parts[0].trim());
                        end = parseDate(parts[1].trim());
                    }
                }

                // Filtrage rétention PEP
                if (oldestEndDate != null && end != null && end.isBefore(oldestEndDate)) {
                    continue;
                }

                String status = (end == null || !end.isBefore(now)) ? "current" : "ended";

                ArmyOfficerRecord rec = new ArmyOfficerRecord();
                rec.roleDescription = role;
                rec.rank = rank;
                rec.fullName = formatFullName(rawName);
                String[] split = splitFullName(rec.fullName);
                rec.firstName = split[0];
                rec.lastName = split[1];
                rec.startDate = start;
                rec.endDate = end;
                rec.status = status;
                result.add(rec);
            }
        }

        // Fallback si la page a une structure modifiée : extraction directe des sections H3
        if (result.isEmpty()) {
            Pattern h3Pattern = Pattern.compile("(?is)<h3>\\s*(?:(?:Général|General|Colonel|Major)\\s+)?([\\p{L}\\s\\-]+?)\\s*<br>\\s*<span>?([\\p{L}\\s'’\\-]+?)</span>?\\s*</h3>");
            Matcher h3M = h3Pattern.matcher(html);
            while (h3M.find()) {
                String name = h3M.group(1).replaceAll("<[^>]+>", "").trim();
                String role = h3M.group(2).replaceAll("<[^>]+>", "").trim();
                if (!name.isBlank() && !role.isBlank()) {
                    ArmyOfficerRecord rec = new ArmyOfficerRecord();
                    rec.roleDescription = role;
                    rec.fullName = formatFullName(name);
                    String[] split = splitFullName(rec.fullName);
                    rec.firstName = split[0];
                    rec.lastName = split[1];
                    rec.status = "current";
                    result.add(rec);
                }
            }
        }

        return result;
    }

    static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim(), DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    static String formatFullName(String raw) {
        String cleaned = raw.replaceAll("\\s+", " ").trim();
        // Convertit "ROBINET Claude" en "Claude Robinet"
        String[] parts = cleaned.split(" ");
        if (parts.length == 2 && parts[0].equals(parts[0].toUpperCase(Locale.ROOT))
                && !parts[1].equals(parts[1].toUpperCase(Locale.ROOT))) {
            return parts[1] + " " + parts[0];
        }
        return cleaned;
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
            if (u != null && !u.isBlank()) {
                String trimmed = u.trim();
                // Si l'URL fournie est simplement le domaine d'accueil, rediriger vers la page du personnel-clé
                if ("https://www.armee.lu".equalsIgnoreCase(trimmed)
                        || "https://armee.lu".equalsIgnoreCase(trimmed)
                        || "https://armee.public.lu".equalsIgnoreCase(trimmed)
                        || "https://armee.public.lu/fr.html".equalsIgnoreCase(trimmed)) {
                    return DEFAULT_SOURCE_URL;
                }
                return trimmed;
            }
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

    static class ArmyOfficerRecord {
        String fullName;
        String firstName;
        String lastName;
        String rank;
        String roleDescription;
        LocalDate startDate;
        LocalDate endDate;
        String status;
    }
}
