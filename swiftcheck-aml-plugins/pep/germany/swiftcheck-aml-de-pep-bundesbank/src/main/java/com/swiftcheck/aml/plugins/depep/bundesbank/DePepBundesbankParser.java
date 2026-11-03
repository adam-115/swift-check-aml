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
import java.util.List;
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
        Map<String, String> p = ctx.parameters();
        String url = p.getOrDefault("url", DEFAULT_URL).trim();
        log.info("Téléchargement du directoire de la Bundesbank : {}", url);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SwiftCheck-AML/1.0")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Erreur HTTP " + response.statusCode() + " lors de l'accès à " + url);
        }
        return response.body();
    }

    static List<Member> extractMembers(String html) {
        List<Member> members = new ArrayList<>();
        Matcher cardMatcher = CARD_PATTERN.matcher(html);
        while (cardMatcher.find()) {
            String card = cardMatcher.group(1);
            Matcher headMatcher = HEADING_PATTERN.matcher(card);
            if (!headMatcher.find()) {
                continue;
            }
            String profileUrl = headMatcher.group(1);
            String rawName = headMatcher.group(2).trim();
            if (rawName.equalsIgnoreCase("Vorstand") || rawName.contains("Bundesbank") && !rawName.contains("Dr.") && !rawName.contains(" ")) {
                continue;
            }

            String title = null;
            String cleanName = rawName;
            Matcher titleMatcher = ACADEMIC_TITLE.matcher(rawName);
            if (titleMatcher.find()) {
                title = titleMatcher.group(0).trim();
                cleanName = rawName.substring(titleMatcher.end()).trim();
            }

            String role = null;
            Matcher roleMatcher = ROLE_PATTERN.matcher(card);
            if (roleMatcher.find()) {
                role = roleMatcher.group(1).trim();
            }
            if (role == null) {
                if (card.contains("Präsidentin") || card.contains("Präsident")) {
                    role = card.contains("Vizepräsident") ? "Vizepräsidentin der Deutschen Bundesbank" : "Präsident der Deutschen Bundesbank";
                } else {
                    role = "Mitglied des Vorstands der Deutschen Bundesbank";
                }
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

        // Fallback si la structure des cards n'a pas matché (ex: liste simple de h3 ou liens)
        if (members.isEmpty()) {
            Matcher headMatcher = HEADING_PATTERN.matcher(html);
            while (headMatcher.find()) {
                String rawName = headMatcher.group(2).trim();
                if (isProbableBoardMember(rawName)) {
                    String title = null;
                    String cleanName = rawName;
                    Matcher tm = ACADEMIC_TITLE.matcher(rawName);
                    if (tm.find()) {
                        title = tm.group(0).trim();
                        cleanName = rawName.substring(tm.end()).trim();
                    }
                    members.add(new Member(rawName, title, cleanName, "Mitglied des Vorstands der Deutschen Bundesbank", null, headMatcher.group(1)));
                }
            }
        }

        return members;
    }

    private static boolean isProbableBoardMember(String name) {
        return (name.contains("Nagel") || name.contains("Mauderer") || name.contains("Theurer")
                || name.contains("Lienenkämper") || name.contains("Buch") || name.contains("Wuermeling")
                || name.startsWith("Dr.") || name.startsWith("Prof.")) && !name.contains("Bundesbank");
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
