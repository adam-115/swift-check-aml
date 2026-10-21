package com.swiftcheck.aml.plugins.lupep.caa;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
 * <b>lu_pep_caa</b> – Commissariat aux Assurances (caa.lu).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-i</b> (Dirigeants des autorités de régulation,
 * JO C/2023/724), fonction <b>Direction du Commissariat aux Assurances (CAA)</b>.
 *
 * <p>L'extraction est <b>100 % dynamique</b> sans liste ni nom en dur dans le code :
 * analyse du document officiel de composition du Comité de direction de l'autorité
 * (PDF officiel ou page HTML référençant l'organigramme de direction).
 *
 * <p>L'URL source est résolue dynamiquement depuis le {@link ParserContext} (paramètres {@code sourceUrl},
 * {@code url} ou {@code sourceLocation}), avec repli par défaut sur {@value #DEFAULT_SOURCE_URL}.
 */
@Extension
public class LuPepCaaParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepCaaParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-caa";

    /** URL source officielle par défaut : page institutionnelle du CAA. */
    public static final String DEFAULT_SOURCE_URL = "https://www.caa.lu/fr/le-caa";

    /** URL par défaut du document officiel de direction du CAA. */
    public static final String DEFAULT_PDF_URL = "https://www.caa.lu/uploads/documents/files/ComiteDeDirectionDuCAA.pdf";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    // Intitulé et clé de la Position officielle
    static final String POS_KEY = "direction";
    static final String POS_NAME = "Directeur ou membre de la direction du Commissariat aux Assurances";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        log.info("Analyse dynamique du Commissariat aux Assurances (sourceUrl : {})", sourceUrl);

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
     * Lit le fichier source (PDF du Comité de direction ou page HTML le référençant) et génère les entités FollowTheMoney.
     */
    void convert(Path sourceFile, String sourceUrl, LocalDate oldestEndDate, FtmDatasetBuilder dataset) throws Exception {
        List<CaaOfficialRecord> list = extractOfficials(sourceFile, sourceUrl);

        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant extrait du Commissariat aux Assurances depuis " + sourceFile);
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

        for (CaaOfficialRecord off : list) {
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
     * Extrait les membres du Comité de direction depuis un fichier source (PDF ou HTML).
     */
    static List<CaaOfficialRecord> extractOfficials(Path sourceFile, String sourceUrl) throws IOException {
        if (isPdfFile(sourceFile)) {
            return extractFromPdf(sourceFile);
        }

        // Si le fichier source est HTML, analyser le lien PDF ou le texte
        String content = Files.readString(sourceFile, StandardCharsets.UTF_8);

        // Vérifier si un fichier PDF adjacent ou référencé existe
        Path parent = sourceFile.getParent();
        if (parent != null) {
            Path directPdf = parent.resolve("ComiteDeDirectionDuCAA.pdf");
            if (Files.exists(directPdf)) {
                return extractFromPdf(directPdf);
            }
        }

        // Tenter l'extraction directe depuis le texte HTML s'il contient les informations
        return extractFromHtml(content);
    }

    static boolean isPdfFile(Path file) {
        if (!Files.exists(file) || Files.isDirectory(file)) {
            return false;
        }
        byte[] header = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(header);
            return read == 4 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Extrait dynamiquement les membres de la direction depuis le document PDF officiel du Comité de direction.
     */
    static List<CaaOfficialRecord> extractFromPdf(Path pdfFile) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfFile.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            return parseDirectionText(text);
        }
    }

    /**
     * Extrait dynamiquement les dirigeants depuis le texte du document de direction.
     */
    static List<CaaOfficialRecord> parseDirectionText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<CaaOfficialRecord> result = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");

        // Format type du PDF du CAA :
        // Thierry FLAMAND Président
        // Yves BAUSTERT Membre
        // Valérie SCHEEPERS Membre
        // Michèle OSWEILER Secrétaire (exclue)
        Pattern linePattern = Pattern.compile("(?i)^\\s*([\\p{L}\\s\\-]+?)\\s+(Pr[ée]sident|Directeur|Directrice|Membre)(?:\\s*|$)");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).contains("secrétaire")
                    || trimmed.toLowerCase(Locale.ROOT).contains("comité")
                    || trimmed.matches("^\\d{2}/\\d{2}/\\d{4}$")) {
                continue;
            }

            Matcher m = linePattern.matcher(trimmed);
            if (m.find()) {
                String rawName = m.group(1).trim();
                String rawRole = m.group(2).trim();

                if (isValidPersonName(rawName)) {
                    String roleDescription;
                    if (rawRole.toLowerCase(Locale.ROOT).contains("président") || rawRole.toLowerCase(Locale.ROOT).contains("president")) {
                        roleDescription = "Directeur (Président du Comité de direction)";
                    } else if (rawRole.toLowerCase(Locale.ROOT).contains("membre")) {
                        roleDescription = "Membre de la direction";
                    } else {
                        roleDescription = rawRole;
                    }
                    addOfficial(rawName, roleDescription, result);
                }
            }
        }

        return result;
    }

    /**
     * Fallback pour extraire depuis du contenu HTML.
     */
    static List<CaaOfficialRecord> extractFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return Collections.emptyList();
        }

        // Nettoyage sommaire
        String clean = html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(?:p|div|li|tr|h[1-6])>", "\n")
                .replaceAll("<[^>]+>", "");

        return parseDirectionText(clean);
    }

    private static boolean isValidPersonName(String name) {
        if (name == null || name.isBlank() || name.length() < 3) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("direction") || lower.contains("comité") || lower.contains("assurances")) {
            return false;
        }
        return name.contains(" ") && name.matches("[\\p{L}\\s\\-]+");
    }

    private static void addOfficial(String fullName, String role, List<CaaOfficialRecord> list) {
        String cleanedName = fullName.replaceAll("\\s+", " ").trim();
        String[] parts = splitFullName(cleanedName);
        CaaOfficialRecord rec = new CaaOfficialRecord();
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

    static class CaaOfficialRecord {
        String fullName;
        String firstName;
        String lastName;
        String roleDescription;
    }
}
