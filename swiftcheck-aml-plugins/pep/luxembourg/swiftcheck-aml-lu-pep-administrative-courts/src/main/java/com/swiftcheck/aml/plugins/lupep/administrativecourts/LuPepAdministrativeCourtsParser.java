package com.swiftcheck.aml.plugins.lupep.administrativecourts;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.io.InputStream;
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

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
 * <b>lu_pep_administrative_courts</b> – Juridictions administratives du Luxembourg (Cour administrative).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-d</b> (Membres des hautes juridictions,
 * Directive (UE) 2015/849, art. 3, point 9 d)), fonction <b>Membre de la Cour administrative</b>
 * (JO C/2023/724).
 *
 * <p>L'extraction est <b>100 % dynamique</b> sans liste ni nom en dur dans le code :
 * <ul>
 *   <li>Format PDF officiel des convocations d'audiences (ex. {@code Cour.pdf}) : extrait les
 *       magistrats composant la chambre et les conseillers rapporteurs.</li>
 *   <li>Format HTML officiel (portail {@code justice.public.lu} ou {@code ja.public.lu}) : extrait
 *       les magistrats membres de la juridiction depuis les rubriques de composition ou annuaires.</li>
 * </ul>
 *
 * <p>L'URL source est résolue dynamiquement depuis le {@link ParserContext} (paramètres {@code sourceUrl},
 * {@code url} ou {@code sourceLocation}), avec repli par défaut sur {@value #DEFAULT_SOURCE_URL}.
 */
@Extension
public class LuPepAdministrativeCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepAdministrativeCourtsParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-administrative-courts";

    /** URL source officielle par défaut : rôle et convocations d'audience de la Cour administrative. */
    public static final String DEFAULT_SOURCE_URL = "https://justice.public.lu/downloads.jurad/Cour.pdf";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    /** Intitulé officiel de la fonction LU-d. */
    static final String POS_NAME = "Membre de la Cour administrative";
    static final String POS_ID_KEY = "member";

    /** Pattern regex pour extraire les magistrats des convocations PDF ("Composition : ..."). */
    private static final Pattern COMPOSITION_PATTERN = Pattern.compile("(?i)Composition\\s*:\\s*([^\r\n]+)");

    /** Pattern regex pour extraire les rapporteurs des convocations PDF ("Rapporteur : ..."). */
    private static final Pattern RAPPORTEUR_PATTERN = Pattern.compile("(?i)Rapporteur\\s*:\\s*([A-Za-zÀ-ÿ\\-\\s]+)");

    /** Pattern regex pour extraire les magistrats dans du HTML ("Monsieur/Madame Prénom NOM, fonction"). */
    private static final Pattern HTML_PERSON_PATTERN = Pattern.compile(
            "(?i)(?:Monsieur|Madame|M\\.|Mme)\\s+([A-Za-zÀ-ÿ\\-\\s]+?)(?:,|\\bet\\b|<|$)");

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        log.info("Analyse dynamique de la Cour administrative (sourceUrl : {})", sourceUrl);

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
     * Lit le fichier source (PDF ou HTML) et génère les entités FollowTheMoney dynamiquement.
     */
    void convert(Path sourceFile, String sourceUrl, LocalDate oldestEndDate, FtmDatasetBuilder dataset) throws Exception {
        List<MagistrateRecord> list = extractMagistrates(sourceFile, sourceUrl);

        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun magistrat extrait depuis " + sourceFile);
        }

        // Créer l'entité Position officielle une seule fois pour éviter les doublons
        FtmEntity position = dataset.newEntity("Position", POS_ID_KEY);
        position.addValue("name", POS_NAME);
        position.addValue("country", "lu");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.judicial");
        dataset.addEntity(position);

        for (MagistrateRecord mag : list) {
            String personId = slugify(mag.fullName);
            FtmEntity person = dataset.newEntity("Person", personId);
            person.addValue("name", mag.fullName);
            if (mag.firstName != null && !mag.firstName.isBlank()) {
                person.addValue("firstName", mag.firstName);
            }
            if (mag.lastName != null && !mag.lastName.isBlank()) {
                person.addValue("lastName", mag.lastName);
            }
            if (mag.gender != null && !mag.gender.isBlank()) {
                person.addValue("gender", mag.gender);
            }
            person.addValue("country", "lu");
            person.addValue("sourceUrl", sourceUrl);
            dataset.addEntity(person);

            // Mandat Occupancy
            FtmEntity occupancy = dataset.newEntity("Occupancy", personId, POS_ID_KEY);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            if (mag.roleDescription != null && !mag.roleDescription.isBlank()) {
                occupancy.addValue("description", mag.roleDescription);
            }
            dataset.addEntity(occupancy);
        }
    }

    /**
     * Extrait les magistrats d'un fichier source en détectant automatiquement s'il s'agit d'un PDF ou d'un fichier HTML/texte.
     */
    static List<MagistrateRecord> extractMagistrates(Path sourceFile, String sourceUrl) throws IOException {
        byte[] header = new byte[8];
        try (InputStream in = Files.newInputStream(sourceFile)) {
            int read = in.read(header);
            if (read >= 4 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F') {
                return extractFromPdf(sourceFile);
            }
        }
        // Sinon, traiter comme du texte / HTML
        String content = Files.readString(sourceFile, StandardCharsets.UTF_8);
        return extractFromHtml(content);
    }

    /**
     * Extrait dynamiquement les membres de la juridiction depuis le texte extrait d'un PDF de convocations.
     */
    static List<MagistrateRecord> extractFromPdf(Path pdfFile) throws IOException {
        Map<String, MagistrateRecord> records = new LinkedHashMap<>();
        try (PDDocument doc = PDDocument.load(pdfFile.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                return Collections.emptyList();
            }

            // 1. Extraire les compositions d'audience ("Composition : NOM Prénom, NOM Prénom, ...")
            Matcher compM = COMPOSITION_PATTERN.matcher(text);
            while (compM.find()) {
                String compStr = compM.group(1);
                String[] parts = compStr.split("[,;]");
                for (String part : parts) {
                    processExtractedName(part, null, records);
                }
            }

            // 2. Extraire les rapporteurs ("Rapporteur : NOM Prénom")
            Matcher rapM = RAPPORTEUR_PATTERN.matcher(text);
            while (rapM.find()) {
                String rapStr = rapM.group(1);
                processExtractedName(rapStr, "Rapporteur", records);
            }
        }
        return new ArrayList<>(records.values());
    }

    /**
     * Extrait dynamiquement les membres depuis une page web HTML.
     */
    static List<MagistrateRecord> extractFromHtml(String html) {
        Map<String, MagistrateRecord> records = new LinkedHashMap<>();
        if (html == null || html.isBlank()) {
            return Collections.emptyList();
        }

        // Recherche de blocs ou listes de magistrats
        Matcher m = HTML_PERSON_PATTERN.matcher(html);
        while (m.find()) {
            String rawName = m.group(1);
            String prefix = m.group(0).toLowerCase(Locale.ROOT);
            String gender = null;
            if (prefix.contains("madame") || prefix.contains("mme")) {
                gender = "female";
            } else if (prefix.contains("monsieur") || prefix.contains("m.")) {
                gender = "male";
            }
            processExtractedName(rawName, null, gender, records);
        }

        // Si aucun magistrat avec civilité Monsieur/Madame, chercher pattern Composition/Rapporteur dans le texte brut
        if (records.isEmpty()) {
            String plainText = html.replaceAll("<[^>]+>", " ");
            Matcher compM = COMPOSITION_PATTERN.matcher(plainText);
            while (compM.find()) {
                String[] parts = compM.group(1).split("[,;]");
                for (String part : parts) {
                    processExtractedName(part, null, records);
                }
            }
        }

        return new ArrayList<>(records.values());
    }

    private static void processExtractedName(String rawText, String roleDesc, Map<String, MagistrateRecord> records) {
        processExtractedName(rawText, roleDesc, null, records);
    }

    private static void processExtractedName(String rawText, String roleDesc, String gender, Map<String, MagistrateRecord> records) {
        if (rawText == null) {
            return;
        }
        String cleaned = rawText.replaceAll("(?i)(?:Monsieur|Madame|Me|Maître|président|conseiller|greffier|rapporteur|\\bavocat\\b|\\bdr\\b|\\(.*\\))", " ")
                .replaceAll("[^A-Za-zÀ-ÿ\\-\\s]", " ")
                .trim()
                .replaceAll("\\s+", " ");

        if (cleaned.isBlank() || cleaned.length() < 3 || cleaned.equalsIgnoreCase("Inconnu")) {
            return;
        }

        String[] parts = cleaned.split("\\s+");
        if (parts.length < 2) {
            return;
        }

        // Identification nom / prénom selon majuscules ou ordre standard
        String firstName;
        String lastName;
        if (parts[0].equals(parts[0].toUpperCase(Locale.ROOT)) && !parts[parts.length - 1].equals(parts[parts.length - 1].toUpperCase(Locale.ROOT))) {
            // Format "DELAPORTE Francis"
            lastName = parts[0];
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(parts[i]);
            }
            firstName = sb.toString();
        } else {
            // Format "Francis Delaporte" ou tout en majuscules
            StringBuilder sbLast = new StringBuilder();
            StringBuilder sbFirst = new StringBuilder();
            for (String p : parts) {
                if (p.equals(p.toUpperCase(Locale.ROOT)) && p.length() > 1) {
                    if (sbLast.length() > 0) sbLast.append(' ');
                    sbLast.append(p);
                } else {
                    if (sbFirst.length() > 0) sbFirst.append(' ');
                    sbFirst.append(p);
                }
            }
            if (sbLast.length() > 0 && sbFirst.length() > 0) {
                lastName = sbLast.toString();
                firstName = sbFirst.toString();
            } else {
                firstName = parts[0];
                lastName = parts[parts.length - 1];
            }
        }

        // Capitalisation propre du nom complet
        String normalizedFullName = capitalizeWords(firstName) + " " + lastName.toUpperCase(Locale.ROOT);
        String key = slugify(normalizedFullName);

        if (!records.containsKey(key)) {
            MagistrateRecord rec = new MagistrateRecord();
            rec.fullName = normalizedFullName;
            rec.firstName = capitalizeWords(firstName);
            rec.lastName = lastName.toUpperCase(Locale.ROOT);
            rec.gender = gender;
            rec.roleDescription = roleDesc != null ? roleDesc : POS_NAME;
            records.put(key, rec);
        }
    }

    private static String capitalizeWords(String str) {
        if (str == null || str.isBlank()) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        for (String w : str.split("\\s+")) {
            if (!w.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) {
                    sb.append(w.substring(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return sb.toString();
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

    static class MagistrateRecord {
        String fullName;
        String firstName;
        String lastName;
        String gender;
        String roleDescription;
    }
}
