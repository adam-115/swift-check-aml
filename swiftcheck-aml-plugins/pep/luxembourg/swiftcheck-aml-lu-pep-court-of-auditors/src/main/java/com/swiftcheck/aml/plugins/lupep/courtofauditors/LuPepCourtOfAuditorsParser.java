package com.swiftcheck.aml.plugins.lupep.courtofauditors;

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
import com.swiftcheck.aml.sdk.DateNormalizer;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>lu_pep_court_of_auditors</b> – Cour des comptes du Grand-Duché de Luxembourg (cour-des-comptes.public.lu).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-e</b> (Cour des comptes et Banque centrale,
 * Directive (UE) 2015/849, art. 3, point 9 e)), fonction <b>Membre de la Cour des comptes</b>
 * (JO C/2023/724).
 *
 * <p>L'extraction est <b>100 % dynamique</b> sans liste ni nom en dur dans le code :
 * <ul>
 *   <li>Page du Collège de la Cour ({@code /fr/cour/college.html}) : extrait les membres du Collège
 *       (Président, Vice-Président, Conseillers), leurs titres et leurs fiches biographiques.</li>
 *   <li>Fiches biographiques individuelles : extrait les noms, prénoms, dates et lieux de naissance.</li>
 * </ul>
 *
 * <p>L'URL source est résolue dynamiquement depuis le {@link ParserContext} (paramètres {@code sourceUrl},
 * {@code url} ou {@code sourceLocation}), avec repli par défaut sur {@value #DEFAULT_SOURCE_URL}.
 */
@Extension
public class LuPepCourtOfAuditorsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepCourtOfAuditorsParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-court-of-auditors";

    /** URL source officielle par défaut : Collège de la Cour des comptes. */
    public static final String DEFAULT_SOURCE_URL = "https://cour-des-comptes.public.lu/fr/cour/college.html";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    /** Intitulé officiel de la fonction LU-e. */
    static final String POS_NAME = "Membre de la Cour des comptes";
    static final String POS_ID_KEY = "member";

    /** Pattern regex pour extraire les membres sur la page du Collège : <h2><a href="...">Nom</a></h2><p><em>Rôle</em>... */
    private static final Pattern COLLEGE_MEMBER_PATTERN = Pattern.compile(
            "(?si)<h2>(?:<a\\s+[^>]*href=\"([^\"]+)\"[^>]*>)?(.*?)(?:</a>)?</h2>\\s*<p>(?:<em>(.*?)</em>)?");

    /** Pattern regex pour détecter une notice biographique unitaire (fiche individuelle). */
    private static final Pattern BIO_NAME_PATTERN = Pattern.compile("(?i)Nom\\s+([A-Za-zÀ-ÿ\\-\\s]+?)\\s+Prénom\\s+([A-Za-zÀ-ÿ\\-\\s]+)");
    private static final Pattern BIO_BIRTH_PATTERN = Pattern.compile("(?i)Date(?:\\s+et\\s+lieu)?\\s+de\\s+naissance\\s+([0-9]{1,2}\\s+[A-Za-zÀ-ÿ]+\\s+[0-9]{4})");

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        log.info("Analyse dynamique de la Cour des comptes (sourceUrl : {})", sourceUrl);

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
        List<AuditorRecord> list = extractAuditors(html, sourceUrl);

        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun membre de la Cour des comptes extrait depuis " + htmlFile);
        }

        // Créer l'entité Position officielle une seule fois pour éviter les doublons
        FtmEntity position = dataset.newEntity("Position", POS_ID_KEY);
        position.addValue("name", POS_NAME);
        position.addValue("country", "lu");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.financial");
        dataset.addEntity(position);

        for (AuditorRecord aud : list) {
            String personId = slugify(aud.fullName);
            FtmEntity person = dataset.newEntity("Person", personId);
            person.addValue("name", aud.fullName);
            if (aud.firstName != null && !aud.firstName.isBlank()) {
                person.addValue("firstName", aud.firstName);
            }
            if (aud.lastName != null && !aud.lastName.isBlank()) {
                person.addValue("lastName", aud.lastName);
            }
            if (aud.birthDate != null && !aud.birthDate.isBlank()) {
                person.addValue("birthDate", aud.birthDate);
            }
            if (aud.gender != null && !aud.gender.isBlank()) {
                person.addValue("gender", aud.gender);
            }
            person.addValue("country", "lu");
            person.addValue("sourceUrl", aud.profileUrl != null ? aud.profileUrl : sourceUrl);
            dataset.addEntity(person);

            // Mandat Occupancy
            FtmEntity occupancy = dataset.newEntity("Occupancy", personId, POS_ID_KEY);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            if (aud.roleDescription != null && !aud.roleDescription.isBlank()) {
                occupancy.addValue("description", aud.roleDescription);
            }
            dataset.addEntity(occupancy);
        }
    }

    /**
     * Extrait dynamiquement les membres de la Cour des comptes à partir du contenu HTML.
     */
    static List<AuditorRecord> extractAuditors(String html, String baseUrl) {
        if (html == null || html.isBlank()) {
            return Collections.emptyList();
        }

        Map<String, AuditorRecord> records = new LinkedHashMap<>();

        // 1. Détection de la page Collège (liste des membres sous <h2><a href="...">Nom</a></h2><p><em>Rôle</em>)
        Matcher m = COLLEGE_MEMBER_PATTERN.matcher(html);
        while (m.find()) {
            String link = m.group(1);
            String rawName = m.group(2);
            String rawRole = m.group(3);

            String cleanedName = cleanHtmlText(rawName);
            if (cleanedName.isBlank() || cleanedName.equalsIgnoreCase("Collège de la Cour") || cleanedName.length() < 3) {
                continue;
            }

            String cleanedRole = cleanHtmlText(rawRole);
            if (cleanedRole.isBlank()) {
                cleanedRole = POS_NAME;
            }

            String profileUrl = null;
            if (link != null && !link.isBlank()) {
                if (link.startsWith("http")) {
                    profileUrl = link;
                } else if (baseUrl != null && baseUrl.startsWith("http")) {
                    try {
                        java.net.URI baseUri = java.net.URI.create(baseUrl);
                        profileUrl = baseUri.resolve(link).toString();
                    } catch (Exception e) {
                        profileUrl = "https://cour-des-comptes.public.lu" + (link.startsWith("/") ? link : "/" + link);
                    }
                }
            }

            addOrUpdateAuditor(cleanedName, cleanedRole, profileUrl, null, records);
        }

        // 2. Détection d'une fiche biographique individuelle (si le fichier est une fiche unitaire)
        if (records.isEmpty()) {
            Pattern trPattern = Pattern.compile("(?si)<tr>\\s*<td>(?:<strong>)?([^<]+?)(?:</strong>)?</td>\\s*<td>(.*?)</td>\\s*</tr>");
            Matcher trM = trPattern.matcher(html);
            String lastName = null;
            String firstName = null;
            String birthRaw = null;
            while (trM.find()) {
                String label = cleanHtmlText(trM.group(1)).toLowerCase(Locale.ROOT);
                String val = cleanHtmlText(trM.group(2));
                if (label.equals("nom")) {
                    lastName = val;
                } else if (label.equals("prénom") || label.equals("prenom")) {
                    firstName = val;
                } else if (label.contains("naissance")) {
                    birthRaw = val;
                }
            }

            if (lastName != null && !lastName.isBlank() && firstName != null && !firstName.isBlank()) {
                String fullName = firstName + " " + lastName;
                String role = POS_NAME;
                Matcher h2M = Pattern.compile("(?i)<h2>([A-Za-zÀ-ÿ\\-\\s]+)</h2>").matcher(html);
                if (h2M.find()) {
                    String h2Text = cleanHtmlText(h2M.group(1));
                    if (!h2Text.isBlank() && !h2Text.toLowerCase(Locale.ROOT).contains("notice")) {
                        role = h2Text;
                    }
                }
                String birthDate = birthRaw != null ? parseFrenchDate(birthRaw) : null;
                addOrUpdateAuditor(fullName, role, baseUrl, birthDate, records);
            } else {
                // Essayer à partir de <h1>Nom</h1> et <h2>Role</h2>
                Matcher h1M = Pattern.compile("(?i)<h1[^>]*>\\s*([^<]+?)\\s*</h1>").matcher(html);
                if (h1M.find()) {
                    String fullName = cleanHtmlText(h1M.group(1));
                    if (!fullName.equalsIgnoreCase("Collège de la Cour") && fullName.contains(" ")) {
                        String role = POS_NAME;
                        Matcher h2M = Pattern.compile("(?i)<h2>([A-Za-zÀ-ÿ\\-\\s]+)</h2>").matcher(html);
                        if (h2M.find()) {
                            String h2Text = cleanHtmlText(h2M.group(1));
                            if (!h2Text.isBlank() && !h2Text.toLowerCase(Locale.ROOT).contains("notice")) {
                                role = h2Text;
                            }
                        }
                        String birthDate = null;
                        Matcher bM = BIO_BIRTH_PATTERN.matcher(html);
                        if (bM.find()) {
                            birthDate = parseFrenchDate(bM.group(1));
                        }
                        addOrUpdateAuditor(fullName, role, baseUrl, birthDate, records);
                    }
                }
            }
        }

        // 3. Fallback générique sur les listes <li>
        if (records.isEmpty()) {
            Pattern liPattern = Pattern.compile("(?si)<li[^>]*>(?:Monsieur|Madame|M\\.|Mme)?\\s*([A-Za-zÀ-ÿ\\-\\s]+?)(?:,|–|-|\\(|<)(.*?)</li>");
            Matcher liM = liPattern.matcher(html);
            while (liM.find()) {
                String name = cleanHtmlText(liM.group(1));
                String role = cleanHtmlText(liM.group(2));
                if (name.length() > 3 && name.contains(" ")) {
                    addOrUpdateAuditor(name, role.isBlank() ? POS_NAME : role, baseUrl, null, records);
                }
            }
        }

        return new ArrayList<>(records.values());
    }

    private static void addOrUpdateAuditor(String fullName, String role, String profileUrl, String birthDate, Map<String, AuditorRecord> records) {
        String[] parts = splitFullName(fullName);
        String key = slugify(parts[0] + " " + parts[1]);

        AuditorRecord rec = records.computeIfAbsent(key, k -> new AuditorRecord());
        rec.fullName = parts[0] + " " + parts[1];
        rec.firstName = parts[0];
        rec.lastName = parts[1];
        if (role != null && !role.isBlank()) {
            rec.roleDescription = role;
        }
        if (profileUrl != null) {
            rec.profileUrl = profileUrl;
        }
        if (birthDate != null) {
            rec.birthDate = birthDate;
        }
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
        // Pour les prénoms composés connus ou noms composés : séparer premier mot comme prénom
        StringBuilder first = new StringBuilder(words[0]);
        StringBuilder last = new StringBuilder();
        for (int i = 1; i < words.length; i++) {
            if (last.length() > 0) last.append(' ');
            last.append(words[i]);
        }
        return new String[] { first.toString(), last.toString() };
    }

    private static String cleanHtmlText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static final Map<String, String> MONTHS = Map.ofEntries(
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
            Map.entry("decembre", "12")
    );

    static String parseFrenchDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String iso = DateNormalizer.toIsoDate(raw);
        if (iso != null) {
            return iso;
        }
        Matcher m = Pattern.compile("(?i)(\\d{1,2})\\s+([A-Za-zÀ-ÿ]+)\\s+(\\d{4})").matcher(raw);
        if (m.find()) {
            String day = String.format("%02d", Integer.parseInt(m.group(1)));
            String monthName = m.group(2).toLowerCase(Locale.ROOT);
            String month = MONTHS.get(monthName);
            String year = m.group(3);
            if (month != null) {
                return year + "-" + month + "-" + day;
            }
        }
        return null;
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

    static class AuditorRecord {
        String fullName;
        String firstName;
        String lastName;
        String birthDate;
        String gender;
        String roleDescription;
        String profileUrl;
    }
}
