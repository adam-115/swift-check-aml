package com.swiftcheck.aml.plugins.lupep.centralbank;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * <b>lu_pep_central_bank</b> – Banque centrale du Luxembourg (bcl.lu).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-e</b> (Cour des comptes et Banque centrale,
 * Directive (UE) 2015/849, art. 3, point 9 e)), fonctions <b>Direction et Conseil de la BCL</b>
 * (JO C/2023/724).
 *
 * <p>L'extraction est <b>100 % dynamique</b> sans liste ni nom en dur dans le code :
 * <ul>
 *   <li><b>La Direction</b> : Directeur général (Gouverneur) et Directeurs.</li>
 *   <li><b>Le Conseil</b> : Président du Conseil et membres du Conseil.</li>
 * </ul>
 *
 * <p>L'URL source est résolue dynamiquement depuis le {@link ParserContext} (paramètres {@code sourceUrl},
 * {@code url} ou {@code sourceLocation}), avec repli par défaut sur {@value #DEFAULT_SOURCE_URL}.
 */
@Extension
public class LuPepCentralBankParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepCentralBankParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-central-bank";

    /** URL source officielle par défaut : page des organes statutaires de la BCL. */
    public static final String DEFAULT_SOURCE_URL = "https://www.bcl.lu/fr/apropos/organisation/organes_statutaires/";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    // Intitulés et clés des Positions officielles
    static final String POS_DIR_KEY = "direction";
    static final String POS_DIR_NAME = "Directeur général ou directeur de la Banque centrale du Luxembourg";

    static final String POS_COUNCIL_KEY = "council";
    static final String POS_COUNCIL_NAME = "Membre du Conseil de la Banque centrale du Luxembourg";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        log.info("Analyse dynamique de la Banque centrale du Luxembourg (sourceUrl : {})", sourceUrl);

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
        List<BankOfficialRecord> list = extractOfficials(html, sourceUrl);

        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant ou membre extrait de la Banque centrale depuis " + htmlFile);
        }

        // 1. Déclarer les deux Positions officielles une seule fois pour éviter les doublons
        FtmEntity posDirection = dataset.newEntity("Position", POS_DIR_KEY);
        posDirection.addValue("name", POS_DIR_NAME);
        posDirection.addValue("country", "lu");
        posDirection.addValue("topics", "gov.national");
        posDirection.addValue("topics", "gov.financial");
        dataset.addEntity(posDirection);

        FtmEntity posCouncil = dataset.newEntity("Position", POS_COUNCIL_KEY);
        posCouncil.addValue("name", POS_COUNCIL_NAME);
        posCouncil.addValue("country", "lu");
        posCouncil.addValue("topics", "gov.national");
        posCouncil.addValue("topics", "gov.financial");
        dataset.addEntity(posCouncil);

        Set<String> createdPersonIds = new HashSet<>();

        for (BankOfficialRecord off : list) {
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

            // Attribuer le bon mandat Occupancy
            String targetPositionId = "DIRECTION".equalsIgnoreCase(off.organType) ? posDirection.getId() : posCouncil.getId();
            String occKey = "DIRECTION".equalsIgnoreCase(off.organType) ? POS_DIR_KEY : POS_COUNCIL_KEY;

            FtmEntity occupancy = dataset.newEntity("Occupancy", personId, occKey);
            occupancy.addValue("holder", personId);
            occupancy.addValue("post", targetPositionId);
            occupancy.addValue("status", "current");
            if (off.roleDescription != null && !off.roleDescription.isBlank()) {
                occupancy.addValue("description", off.roleDescription);
            }
            dataset.addEntity(occupancy);
        }
    }

    /**
     * Analyse le contenu HTML et extrait dynamiquement les membres de la Direction et du Conseil.
     */
    static List<BankOfficialRecord> extractOfficials(String html, String sourceUrl) {
        if (html == null || html.isBlank()) {
            return Collections.emptyList();
        }

        List<BankOfficialRecord> result = new ArrayList<>();

        // Nettoyage préalable du balisage : préserver les sauts de ligne pour chaque bloc
        String clean = html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(?:p|div|li|tr|h[1-6])>", "\n")
                .replace("&eacute;", "é")
                .replace("&Eacute;", "É")
                .replace("&egrave;", "è")
                .replace("&agrave;", "à")
                .replace("&nbsp;", " ")
                .replace("&rsquo;", "'")
                .replace("&#39;", "'")
                .replaceAll("<[^>]+>", "");

        // 1. Direction : Directeur général
        Pattern dirGenPattern = Pattern.compile("(?im)^\\s*Directeur\\s+g[ée]n[ée]ral\\s*:\\s*([\\p{L}\\s\\-]+?)$");
        Matcher dgM = dirGenPattern.matcher(clean);
        if (dgM.find()) {
            String name = cleanExtractedName(dgM.group(1));
            if (isValidName(name)) {
                addOfficial(name, "Directeur général", "DIRECTION", result);
            }
        }

        // 2. Direction : Directeurs
        Pattern dirsPattern = Pattern.compile("(?im)^\\s*Directeurs\\s*:\\s*([\\p{L}\\s\\-,]+?)$");
        Matcher dirsM = dirsPattern.matcher(clean);
        if (dirsM.find()) {
            String rawDirs = dirsM.group(1);
            String[] names = rawDirs.split("(?i)\\s+et\\s+|,");
            for (String n : names) {
                String name = cleanExtractedName(n);
                if (isValidName(name)) {
                    addOfficial(name, "Directeur", "DIRECTION", result);
                }
            }
        }

        // 3. Conseil : Président
        Pattern presPattern = Pattern.compile("(?im)^\\s*Pr[ée]sident\\s*:\\s*([\\p{L}\\s\\-]+?)$");
        Matcher pM = presPattern.matcher(clean);
        if (pM.find()) {
            String name = cleanExtractedName(pM.group(1));
            if (isValidName(name)) {
                addOfficial(name, "Président du Conseil", "COUNCIL", result);
            }
        }

        // 4. Conseil : Membres
        Pattern memPattern = Pattern.compile("(?im)^\\s*Membres\\s*:\\s*([\\p{L}\\s\\-,;]+?)(?:\\.|$|\n)");
        Matcher mM = memPattern.matcher(clean);
        if (mM.find()) {
            String rawMembres = mM.group(1);
            String[] members = rawMembres.split("[,;]");
            for (String m : members) {
                String name = cleanExtractedName(m);
                if (isValidName(name)) {
                    addOfficial(name, "Membre du Conseil", "COUNCIL", result);
                }
            }
        }

        // 5. Fallback générique si le format venait à changer (liste à puces li)
        if (result.isEmpty()) {
            Pattern liPattern = Pattern.compile("(?si)<li[^>]*>([\\p{L}\\s\\-]+?)(?:,|–|-|\\()?(.*?)</li>");
            Matcher liM = liPattern.matcher(html);
            while (liM.find()) {
                String name = cleanExtractedName(liM.group(1));
                String role = cleanExtractedName(liM.group(2));
                if (isValidName(name)) {
                    addOfficial(name, role.isBlank() ? "Membre de la BCL" : role, "COUNCIL", result);
                }
            }
        }

        return result;
    }

    private static void addOfficial(String fullName, String role, String organType, List<BankOfficialRecord> list) {
        String[] parts = splitFullName(fullName);
        BankOfficialRecord rec = new BankOfficialRecord();
        rec.fullName = parts[0] + " " + parts[1];
        rec.firstName = parts[0];
        rec.lastName = parts[1];
        rec.roleDescription = role;
        rec.organType = organType;
        list.add(rec);
    }

    private static String cleanExtractedName(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("<[^>]+>", " ")
                .replaceAll("[^A-Za-zÀ-ÿ\\-\\s]", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static boolean isValidName(String name) {
        if (name == null || name.isBlank() || name.length() < 3) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("banque") || lower.contains("statutaire") || lower.contains("organes")) {
            return false;
        }
        return name.contains(" ");
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

    static class BankOfficialRecord {
        String fullName;
        String firstName;
        String lastName;
        String roleDescription;
        String organType; // "DIRECTION" ou "COUNCIL"
    }
}
