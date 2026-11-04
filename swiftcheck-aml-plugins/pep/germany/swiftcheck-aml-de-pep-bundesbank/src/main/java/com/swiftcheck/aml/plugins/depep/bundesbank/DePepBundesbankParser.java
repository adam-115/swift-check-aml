package com.swiftcheck.aml.plugins.depep.bundesbank;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <b>de_pep_bundesbank</b> – Directoire de la Banque fédérale d'Allemagne (Deutsche Bundesbank).
 * <p>
 * Catégorie <b>DE-f</b> (§ 1 (12) f) GwG ; liste JO C/2023/724).
 * Couvre le Président, la Vice-Présidente et les membres du directoire (Vorstand).
 */
@Extension
public class DePepBundesbankParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepBundesbankParser.class);

    static final String ID_PREFIX = "de-pep-bundesbank";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_URL = "https://www.bundesbank.de/de/bundesbank/organisation/vorstand";
    static final String SITE = "https://www.bundesbank.de";

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?si)<(?:div|li|article)[^>]*class=\"[^\"]*(?:teaser|card|member|portrait|person)[^\"]*\"[^>]*>(.*?)(?=<div[^>]*class=\"[^\"]*(?:teaser|card|member|portrait|person)|<li[^>]*class=\"[^\"]*(?:teaser|card|member|portrait|person)|</(?:body|ul|main)>|$)");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "(?si)<h[2-4][^>]*>\\s*(?:<a[^>]*href=\"([^\"]*)\"[^>]*>)?\\s*([^<]+?)\\s*(?:</a>)?\\s*</h[2-4]>");

    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(?si)<(?:p|div|span)[^>]*class=\"[^\"]*(?:role|text|subline|subtitle|function|position|description)[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</(?:p|div|span)>");

    private static final Pattern IMG_PATTERN = Pattern.compile(
            "(?si)<img[^>]*\\ssrc=\"([^\"]+)\"[^>]*>");

    private static final Pattern VORSTAND_LINK = Pattern.compile(
            "(?si)<a\\s+[^>]*href=\"(/de/bundesbank/organisation/vorstand/[^\"]+)\"[^>]*>([\\s\\S]*?)</a>");

    private static final Pattern VORSTAND_H2_BLOCK = Pattern.compile(
            "(?si)<h[2-4][^>]*>\\s*<a\\s+[^>]*href=\"(/de/bundesbank/organisation/vorstand/[^\"]+)\"[^>]*>([\\s\\S]*?)</a>\\s*</h[2-4]>\\s*(?:<p[^>]*>([\\s\\S]*?)</p>)?");

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    record Member(String fullName, String title, String cleanName, String role, String photoUrl, String profileUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String html = loadHtml(ctx);
        List<Member> members = extractMembers(html);
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre du directoire trouvé dans la page de la Bundesbank.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(members, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                members.size() + " membres du directoire de la Deutsche Bundesbank extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        String html = loadHtml(ctx);
        List<Member> members = extractMembers(html);
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (Member m : members) {
            records.add(new SourceRecord(
                    ids.buildEntityId(m.cleanName()),
                    "Person",
                    m.fullName(),
                    m.role() != null ? m.role() : "Mitglied des Vorstands"
            ));
        }
        return records;
    }

    private String loadHtml(ParserContext ctx) throws Exception {
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            return Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
        }
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.getOrDefault("url", DEFAULT_URL).trim();

        String html = null;
        try {
            log.info("Téléchargement du directoire de la Bundesbank : {}", url);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Allemagne - Bundesbank)")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
                html = response.body();
            }
        } catch (Exception e) {
            log.warn("Impossible de télécharger {} : {}", url, e.getMessage());
        }

        if (html == null) {
            throw new IllegalStateException("Impossible de charger la page du directoire de la Bundesbank.");
        }

        if (ctx.rawDir() != null) {
            Files.createDirectories(ctx.rawDir());
            Files.writeString(ctx.rawDir().resolve("bundesbank_vorstand.html"), html, StandardCharsets.UTF_8);
        }

        return html;
    }

    static List<Member> extractMembers(String html) {
        List<Member> members = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();

        // 1. Détection via les blocs h2/h3 de présentation Bundesbank CMS
        Matcher h2Matcher = VORSTAND_H2_BLOCK.matcher(html);
        while (h2Matcher.find()) {
            String profileUrl = h2Matcher.group(1);
            String rawName = h2Matcher.group(2).replaceAll("<[^>]+>", "").trim();
            String roleHtml = h2Matcher.group(3) != null ? h2Matcher.group(3).replaceAll("<[^>]+>", " ").trim() : "";

            if (rawName.isBlank() || rawName.equalsIgnoreCase("Vorstand") || rawName.contains("Geschäftsverteilung")) {
                continue;
            }

            String title = null;
            String cleanName = rawName;
            Matcher tm = ACADEMIC_TITLE.matcher(rawName);
            if (tm.find()) {
                title = tm.group(0).trim();
                cleanName = rawName.substring(tm.end()).trim();
            }

            if (!seenNames.add(cleanName.toLowerCase())) {
                continue;
            }

            String role = determineRole(rawName, roleHtml);
            members.add(new Member(rawName, title, cleanName, role, null, profileUrl));
        }

        // 2. Détection via CARD_PATTERN classique si pas trouvé en h2
        if (members.isEmpty()) {
            Matcher cardMatcher = CARD_PATTERN.matcher(html);
            while (cardMatcher.find()) {
                String card = cardMatcher.group(1);
                Matcher headMatcher = HEADING_PATTERN.matcher(card);
                if (!headMatcher.find()) {
                    continue;
                }
                String profileUrl = headMatcher.group(1);
                String rawName = headMatcher.group(2).replaceAll("<[^>]+>", "").trim();
                if (rawName.isBlank() || rawName.equalsIgnoreCase("Vorstand") || rawName.contains("Bundesbank") && !rawName.contains("Dr.") && !rawName.contains(" ")) {
                    continue;
                }

                String title = null;
                String cleanName = rawName;
                Matcher titleMatcher = ACADEMIC_TITLE.matcher(rawName);
                if (titleMatcher.find()) {
                    title = titleMatcher.group(0).trim();
                    cleanName = rawName.substring(titleMatcher.end()).trim();
                }

                if (!seenNames.add(cleanName.toLowerCase())) {
                    continue;
                }

                String role = null;
                Matcher roleMatcher = ROLE_PATTERN.matcher(card);
                if (roleMatcher.find()) {
                    role = roleMatcher.group(1).trim();
                }
                if (role == null) {
                    role = determineRole(rawName, card);
                }

                String photoUrl = null;
                Matcher imgMatcher = IMG_PATTERN.matcher(card);
                if (imgMatcher.find()) {
                    photoUrl = imgMatcher.group(1);
                    if (photoUrl.startsWith("/")) {
                        photoUrl = SITE + photoUrl;
                    }
                }

                members.add(new Member(rawName, title, cleanName, role, photoUrl, profileUrl));
            }
        }

        // 3. Fallback direct via les liens du Vorstand
        if (members.isEmpty()) {
            Matcher linkMatcher = VORSTAND_LINK.matcher(html);
            while (linkMatcher.find()) {
                String profileUrl = linkMatcher.group(1);
                String rawName = linkMatcher.group(2).replaceAll("<[^>]+>", "").trim();
                if (rawName.isBlank() || rawName.equalsIgnoreCase("Vorstand") || rawName.contains("Geschäftsverteilung")
                        || rawName.length() < 3 || rawName.contains("Downloads")) {
                    continue;
                }
                String title = null;
                String cleanName = rawName;
                Matcher tm = ACADEMIC_TITLE.matcher(rawName);
                if (tm.find()) {
                    title = tm.group(0).trim();
                    cleanName = rawName.substring(tm.end()).trim();
                }
                if (seenNames.add(cleanName.toLowerCase())) {
                    String role = determineRole(rawName, "");
                    members.add(new Member(rawName, title, cleanName, role, null, profileUrl));
                }
            }
        }

        return members;
    }

    private static String determineRole(String name, String context) {
        String combined = (name + " " + context).toLowerCase();
        if (combined.contains("präsidentin") || combined.contains("praesidentin")) {
            return combined.contains("vize") ? "Vizepräsidentin der Deutschen Bundesbank" : "Präsidentin der Deutschen Bundesbank";
        }
        if (combined.contains("präsident") || combined.contains("praesident")) {
            return combined.contains("vize") ? "Vizepräsident der Deutschen Bundesbank" : "Präsident der Deutschen Bundesbank";
        }
        if (name.contains("Nagel")) {
            return "Präsident der Deutschen Bundesbank";
        }
        if (name.contains("Mauderer")) {
            return "Vizepräsidentin der Deutschen Bundesbank";
        }
        return "Mitglied des Vorstands der Deutschen Bundesbank";
    }

    private void convert(List<Member> members, FtmDatasetBuilder dataset) {
        FtmEntity generalPosition = dataset.newEntity("Position", "board-member");
        generalPosition.addValue("name", "Mitglied des Vorstands der Deutschen Bundesbank");
        generalPosition.addValue("country", "de");
        generalPosition.addValue("organization", "Deutsche Bundesbank");
        generalPosition.addValue("topics", "gov.national");
        generalPosition.addValue("topics", "gov.financial");
        dataset.addEntity(generalPosition);

        for (Member m : members) {
            FtmEntity person = dataset.newEntity("Person", m.cleanName());
            person.addValue("name", m.cleanName());
            person.addValue("title", m.title());
            String[] parts = m.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            person.addValue("position", m.role());
            if (m.photoUrl() != null) {
                person.addValue("imageUrl", m.photoUrl());
            }
            if (m.profileUrl() != null) {
                String fullProfile = m.profileUrl().startsWith("http") ? m.profileUrl() : SITE + m.profileUrl();
                person.addValue("sourceUrl", fullProfile);
            }
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", m.cleanName(), "vorstand");
            occ.addValue("holder", person.getId());
            occ.addValue("post", generalPosition.getId());
            occ.addValue("status", "current");
            occ.addValue("description", m.role());
            dataset.addEntity(occ);
        }
    }
}
