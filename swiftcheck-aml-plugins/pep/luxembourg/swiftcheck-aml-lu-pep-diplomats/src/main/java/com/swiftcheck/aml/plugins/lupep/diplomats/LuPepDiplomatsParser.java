package com.swiftcheck.aml.plugins.lupep.diplomats;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
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
 * <b>lu_pep_diplomats</b> – Missions diplomatiques et consulaires (mae.gouvernement.lu).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-f</b> (Ambassadeurs, chargés d'affaires et officiers supérieurs,
 * Directive (UE) 2015/849 art. 3 point 9 f), fonction <b>Ambassadeur ou chargé d'affaires</b>
 * du Ministère des Affaires étrangères et européennes du Luxembourg.
 *
 * <p>L'extraction est <b>100 % dynamique</b> sans liste ni nom en dur dans le code :
 * analyse du portail officiel du réseau diplomatique luxembourgeois et des pages officielles d'équipe
 * de chaque ambassade.
 *
 * <p>L'URL source est résolue dynamiquement depuis le {@link ParserContext} (paramètres {@code sourceUrl},
 * {@code url} ou {@code sourceLocation}), avec repli par défaut sur {@value #DEFAULT_SOURCE_URL}.
 */
@Extension
public class LuPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepDiplomatsParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-diplomats";

    /** URL source officielle par défaut : missions diplomatiques luxembourgeoises. */
    public static final String DEFAULT_SOURCE_URL =
            "https://mae.gouvernement.lu/fr/missions-diplomatiques/missions-diplomatiques-et-consulaires-luxembourgeoises.html";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    // Intitulé et clé de la Position officielle
    static final String POS_KEY = "ambassadeur-ou-charge-affaires";
    static final String POS_NAME = "Ambassadeur ou chargé d'affaires du Grand-Duché de Luxembourg";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        log.info("Analyse dynamique des missions diplomatiques du Luxembourg (sourceUrl : {})", sourceUrl);

        convert(sourceFile(ctx), sourceUrl, ctx, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        convert(sourceFile(ctx), sourceUrl, ctx, dataset);

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
    void convert(Path htmlFile, String sourceUrl, ParserContext ctx, FtmDatasetBuilder dataset) throws Exception {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<DiplomatRecord> list = extractDiplomats(html, htmlFile, ctx);

        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun ambassadeur extrait depuis " + htmlFile);
        }

        // 1. Déclarer la Position officielle une seule fois
        FtmEntity position = dataset.newEntity("Position", POS_KEY);
        position.addValue("name", POS_NAME);
        position.addValue("country", "lu");
        position.addValue("organization", "Ministère des Affaires étrangères et européennes");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.diplomatic");
        dataset.addEntity(position);

        Set<String> createdPersonIds = new HashSet<>();

        for (DiplomatRecord dip : list) {
            String personId = slugify(dip.fullName);

            if (createdPersonIds.add(personId)) {
                FtmEntity person = dataset.newEntity("Person", personId);
                person.addValue("name", dip.fullName);
                if (dip.firstName != null && !dip.firstName.isBlank()) {
                    person.addValue("firstName", dip.firstName);
                }
                if (dip.lastName != null && !dip.lastName.isBlank()) {
                    person.addValue("lastName", dip.lastName);
                }
                person.addValue("country", "lu");
                if (dip.sourceUrl != null && !dip.sourceUrl.isBlank()) {
                    person.addValue("sourceUrl", dip.sourceUrl);
                } else {
                    person.addValue("sourceUrl", sourceUrl);
                }
                dataset.addEntity(person);
            }

            String occKey = personId + "-" + slugify(dip.embassyHost != null ? dip.embassyHost : "mission");
            FtmEntity occupancy = dataset.newEntity("Occupancy", occKey);
            occupancy.addValue("holder", personId);
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            if (dip.roleDescription != null && !dip.roleDescription.isBlank()) {
                occupancy.addValue("description", dip.roleDescription);
            }
            dataset.addEntity(occupancy);
        }
    }

    /**
     * Extrait la liste des diplomates à partir de la page principale des missions
     * et des pages d'équipe des ambassades (locales ou téléchargées).
     */
    List<DiplomatRecord> extractDiplomats(String mainHtml, Path mainFile, ParserContext ctx) {
        List<DiplomatRecord> result = new ArrayList<>();
        if (mainHtml == null || mainHtml.isBlank()) {
            return result;
        }

        // Extraire tous les sites d'ambassades uniques depuis la page principale
        Pattern p = Pattern.compile("https://([a-z0-9\\-\\.]+)\\.mae\\.lu/([a-z]{2})\\.html");
        Matcher m = p.matcher(mainHtml);

        Set<String> processedHosts = new HashSet<>();

        // Vérifier si un sous-dossier 'embassies' existe à côté du fichier principal (mode test/offline)
        Path localEmbassiesDir = null;
        if (mainFile != null && mainFile.getParent() != null) {
            Path candidate = mainFile.getParent().resolve("embassies");
            if (Files.isDirectory(candidate)) {
                localEmbassiesDir = candidate;
            }
        }
        if (localEmbassiesDir == null && ctx != null && ctx.rawDir() != null) {
            Path candidate = ctx.rawDir().resolve("embassies");
            if (Files.isDirectory(candidate)) {
                localEmbassiesDir = candidate;
            }
        }

        boolean localOnly = (localEmbassiesDir != null);

        while (m.find()) {
            String host = m.group(1);
            String lang = m.group(2);

            if (!processedHosts.add(host)) {
                continue;
            }

            // Ignorer les sites génériques non bilatéraux si nécessaire
            DiplomatRecord rec = null;

            // 1. Tenter la lecture locale
            if (localEmbassiesDir != null) {
                Path localFile = localEmbassiesDir.resolve(host + ".html");
                if (Files.exists(localFile)) {
                    try {
                        String teamHtml = Files.readString(localFile, StandardCharsets.UTF_8);
                        rec = parseEmbassyTeamHtml(host, teamHtml, "https://" + host + ".mae.lu/" + lang + "/ambassades/team.html");
                    } catch (Exception e) {
                        log.debug("Erreur lecture fichier local pour {} : {}", host, e.getMessage());
                    }
                }
            }

            // 2. Tenter le téléchargement HTTP si non trouvé localement et qu'on n'est pas en mode fixture locale
            if (rec == null && !localOnly) {
                rec = fetchAndParseEmbassy(host, lang);
            }

            if (rec != null && rec.fullName != null && !rec.fullName.isBlank()) {
                result.add(rec);
            }
        }

        return result;
    }

    /**
     * Télécharge la page de l'ambassade et en extrait l'ambassadeur ou chargé d'affaires.
     */
    DiplomatRecord fetchAndParseEmbassy(String host, String lang) {
        String[] candidateUrls = new String[] {
            "https://" + host + ".mae.lu/" + lang + "/ambassades/equipe.html",
            "https://" + host + ".mae.lu/" + lang + "/ambassades/team.html",
            "https://" + host + ".mae.lu/fr/ambassades/equipe.html",
            "https://" + host + ".mae.lu/en/ambassades/team.html",
            "https://" + host + ".mae.lu/de/ambassades/team.html"
        };

        for (String url : candidateUrls) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(4))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SwiftCheck-AML/1.0")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200 && response.body() != null) {
                    DiplomatRecord rec = parseEmbassyTeamHtml(host, response.body(), url);
                    if (rec != null) {
                        return rec;
                    }
                }
            } catch (Exception e) {
                // Essayer l'URL suivante
            }
        }
        return null;
    }

    /**
     * Analyse le HTML d'une page d'équipe d'ambassade pour en extraire le chef de mission.
     */
    static DiplomatRecord parseEmbassyTeamHtml(String host, String html, String pageUrl) {
        if (html == null || html.isBlank()) {
            return null;
        }

        // Stratégie 1 : Balise <meta name="description" content="..."> (format Adobe AEM standard)
        Pattern metaPattern = Pattern.compile("(?is)<meta\\s+name=\"description\"\\s+content=\"([^\"]+)\"");
        Matcher metaM = metaPattern.matcher(html);
        if (metaM.find()) {
            String content = metaM.group(1).trim();
            String[] lines = content.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (isDiplomatTitle(line)) {
                    String nameLine = (i > 0) ? lines[i - 1].trim() : "";
                    String cleanedName = cleanDiplomatName(nameLine);
                    if (isValidName(cleanedName)) {
                        return createRecord(cleanedName, line, host, pageUrl);
                    }
                }
            }
        }

        // Stratégie 2a : Tableau organigramme à 3 colonnes : Prénom, Nom, Fonction (ex: Berlin)
        Pattern table3Pattern = Pattern.compile("(?is)<td[^>]*>\\s*([\\p{L}\\s\\.\\-]+?)\\s*</td>\\s*<td[^>]*>\\s*([\\p{L}\\s\\.\\-]+?)\\s*</td>\\s*<td[^>]*>\\s*([^<]*?(?:Ambassad|Botschafter|charg[ée]|Ambassador)[^<]*?)\\s*</td>");
        Matcher t3m = table3Pattern.matcher(html);
        if (t3m.find()) {
            String first = cleanDiplomatName(t3m.group(1));
            String last = cleanDiplomatName(t3m.group(2));
            String title = t3m.group(3).replaceAll("<[^>]+>", "").trim();
            String full = (first + " " + last).trim();
            if (isValidName(full)) {
                return createRecord(full, title, host, pageUrl);
            }
        }

        // Stratégie 2b : Tableau organigramme à 2 colonnes : Nom complet, Fonction
        Pattern tablePattern = Pattern.compile("(?is)<td[^>]*>\\s*([\\p{L}\\s\\.\\-]+?)\\s*</td>\\s*<td[^>]*>\\s*([^<]*?(?:Ambassad|Botschafter|charg[ée]|Ambassador)[^<]*?)\\s*</td>");
        Matcher tm = tablePattern.matcher(html);
        if (tm.find()) {
            String cleanedName = cleanDiplomatName(tm.group(1));
            String title = tm.group(2).replaceAll("<[^>]+>", "").trim();
            if (isValidName(cleanedName)) {
                return createRecord(cleanedName, title, host, pageUrl);
            }
        }

        // Stratégie 3 : Détection générale par bloc H3/P
        Pattern blockPattern = Pattern.compile("(?is)(?:<p|<h[2-6]|<li)[^>]*>\\s*(?:H\\.E\\.\\s+)?(?:S\\.E\\.\\s+)?(?:M\\.|Mme|Mr\\.?|Ms\\.?|Frau|Herr)?\\s*([\\p{L}\\s\\.\\-]+?)\\s*</(?:p|h[2-6]|li)>\\s*(?:<p|<h[2-6]|<li)[^>]*>\\s*([^<]*?(?:Ambassad|Botschafter|charg[ée]|Ambassador)[^<]*?)\\s*</(?:p|h[2-6]|li)>");
        Matcher bm = blockPattern.matcher(html);
        if (bm.find()) {
            String cleanedName = cleanDiplomatName(bm.group(1));
            String title = bm.group(2).replaceAll("<[^>]+>", "").trim();
            if (isValidName(cleanedName)) {
                return createRecord(cleanedName, title, host, pageUrl);
            }
        }

        return null;
    }

    private static boolean isDiplomatTitle(String s) {
        if (s == null || s.isBlank()) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("ambassad")
                || lower.contains("ambassador")
                || lower.contains("botschafter")
                || lower.contains("charg")
                || lower.contains("représentant")
                || lower.contains("representant");
    }

    private static boolean isValidName(String name) {
        if (name == null || name.isBlank() || name.length() < 3) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("ministère") || lower.contains("embassy") || lower.contains("ambassade")
                || lower.contains("gouvernement") || lower.contains("équipe") || lower.contains("team")
                || lower.contains("rubrique") || lower.contains("footer")) {
            return false;
        }
        return name.contains(" ");
    }

    static String cleanDiplomatName(String raw) {
        if (raw == null) return "";
        return raw.replace('’', '\'').replace('‘', '\'')
                .replaceAll("(?i)^(?:H\\.E\\.?\\s+)?(?:S\\.E\\.?\\s+)?(?:M\\.|Mme|Mr\\.?|Ms\\.?|Frau|Herr|His Excellency|Monsieur|Madame)\\s+", "")
                .replaceAll("(?i)^L['’]Ambassadeur\\s*:\\s*", "")
                .replaceAll("(?i)^Ambassador(?:-designate)?\\s*:\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static DiplomatRecord createRecord(String name, String role, String host, String pageUrl) {
        DiplomatRecord rec = new DiplomatRecord();
        rec.fullName = name;
        String[] parts = splitFullName(name);
        rec.firstName = parts[0];
        rec.lastName = parts[1];
        rec.roleDescription = formatRoleDescription(role, host);
        rec.embassyHost = host;
        rec.sourceUrl = pageUrl;
        return rec;
    }

    private static String formatRoleDescription(String role, String host) {
        String cleaned = role.replace('’', '\'').replace('‘', '\'').replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim();
        }
        if (cleaned.equalsIgnoreCase("Ambassadeur") || cleaned.equalsIgnoreCase("Ambassador")) {
            return "Ambassadeur du Luxembourg (" + host + ")";
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

    static class DiplomatRecord {
        String fullName;
        String firstName;
        String lastName;
        String roleDescription;
        String embassyHost;
        String sourceUrl;
    }
}
