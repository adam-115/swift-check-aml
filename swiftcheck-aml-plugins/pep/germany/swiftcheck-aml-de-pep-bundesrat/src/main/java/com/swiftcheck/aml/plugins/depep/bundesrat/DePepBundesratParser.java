package com.swiftcheck.aml.plugins.depep.bundesrat;

import java.io.IOException;
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
 * <b>de_pep_bundesrat</b> – Membres du Bundesrat et membres des gouvernements des 16 Länder (catégories DE-b et DE-a ;
 * GwG § 1 (12) a) et b) ; JO C/2023/724).
 *
 * <p>Source : liste officielle des membres sur bundesrat.de.
 */
@Extension
public class DePepBundesratParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepBundesratParser.class);

    static final String ID_PREFIX = "de-pep-bundesrat";
    static final String TOPIC = "role.pep";

    static final String DEFAULT_URL = "https://www.bundesrat.de/DE/bundesrat/mitglieder/mitglieder-node.html?param3=Alle";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Bundesrat)";

    private static final Pattern VCARD_BLOCK = Pattern.compile("(?si)<div[^>]*class=\"[^\"]*vcard[^\"]*\"[^>]*>(.*?)</li>");

    private static final Pattern PERSON_LINK = Pattern.compile("(?i)<a[^>]+href=[\"']([^\"']*/SharedDocs/personen/DE/laender/([^/]+)/([^.]+)\\.html)[\"']");
    private static final Pattern PHOTO = Pattern.compile("(?i)<img[^>]+src=[\"']([^\"']+)[\"'][^>]*class=[\"']photo[\"']");
    private static final Pattern PHOTO_TITLE = Pattern.compile("(?i)Foto:\\s*([^\"']+)");

    private static final Pattern NAME_PARTY = Pattern.compile("(?si)<h2[^>]*>\\s*<a[^>]*>\\s*([^<|]+?)(?:&nbsp;|\\s)*(?:&#x007C;|\\|)(?:&nbsp;|\\s)*<span class=\"organization-name\">([^<]+)</span>\\s*</a>\\s*</h2>");
    private static final Pattern STATE = Pattern.compile("(?si)<p class=\"state\">([^<]+)</p>");
    private static final Pattern FUNCTION = Pattern.compile("(?si)<h3[^>]*>([^<]+)</h3>");

    record Member(String slug, String landCode, String name, String party, String state, String function,
            String pageUrl, String photo, String photoCredit) {
    }

    record Report(int totalMembers, int ministerPresidents, int ministers) {
        String message() {
            return String.format("%d membres du Bundesrat extraits (dont %d ministres-présidents et %d ministres de Länder).",
                    totalMembers, ministerPresidents, ministers);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String html = loadHtml(ctx, p);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<Member> members = extractMembers(html);

        int mpCount = 0;
        int minCount = 0;
        for (Member m : members) {
            boolean isMp = isMinisterPresident(m.function());
            if (isMp) mpCount++; else minCount++;
            write(m, isMp, dataset);
        }

        dataset.writeNdjsonFile(ctx.outputFile());
        Report report = new Report(members.size(), mpCount, minCount);
        return dataset.toConversionResult().withMessage(report.message());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String html = loadHtml(ctx, p);
        List<Member> members = extractMembers(html);
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);

        List<SourceRecord> records = new ArrayList<>();
        for (Member m : members) {
            records.add(new SourceRecord(ids.buildEntityId(m.slug()), "Person", m.name(), m.pageUrl()));
        }
        return records;
    }

    static List<Member> extractMembers(String html) {
        List<Member> list = new ArrayList<>();
        Matcher m = VCARD_BLOCK.matcher(html);
        while (m.find()) {
            String block = m.group(1);
            Matcher linkM = PERSON_LINK.matcher(block);
            if (!linkM.find()) {
                continue;
            }
            String pageUrl = linkM.group(1);
            String landCode = linkM.group(2);
            String slug = linkM.group(3);

            String rawName = slug.replace('-', ' ');
            String party = null;
            Matcher nameM = NAME_PARTY.matcher(block);
            if (nameM.find()) {
                rawName = Html.text(nameM.group(1));
                party = Html.text(nameM.group(2));
            }

            Matcher stateM = STATE.matcher(block);
            String state = stateM.find() ? Html.text(stateM.group(1)) : null;

            Matcher funcM = FUNCTION.matcher(block);
            String function = funcM.find() ? Html.text(funcM.group(1)) : null;

            String photo = null;
            String photoCredit = null;
            Matcher photoM = PHOTO.matcher(block);
            if (photoM.find()) {
                photo = photoM.group(1);
                Matcher creditM = PHOTO_TITLE.matcher(block);
                if (creditM.find()) {
                    photoCredit = "Foto: " + Html.text(creditM.group(1));
                }
            }

            list.add(new Member(slug, landCode, cleanName(rawName), party, state, function, pageUrl, photo, photoCredit));
        }
        return list;
    }

    private static void write(Member m, boolean isMp, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", m.slug());
        person.addValue("name", m.name());
        if (m.party() != null) person.addValue("political", m.party());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", "Membre du Bundesrat");
        if (m.function() != null) {
            person.addValue("position", m.function());
        }
        person.addValue("sourceUrl", m.pageUrl());
        dataset.addEntity(person);

        if (m.photo() != null) {
            dataset.addMedia(person.getId(), m.photo(), m.pageUrl(), m.photoCredit() != null ? m.photoCredit() : "© Bundesrat");
        }

        // 1. Position Bundesrat
        FtmEntity posBundesrat = dataset.newEntity("Position", "bundesrat-member");
        posBundesrat.addValue("name", "Membre du Bundesrat (Mitglied des Bundesrates)");
        posBundesrat.addValue("country", "de");
        posBundesrat.addValue("organization", "Bundesrat");
        posBundesrat.addValue("topics", "gov.national");
        posBundesrat.addValue("topics", "gov.legislative");
        dataset.addEntity(posBundesrat);

        FtmEntity occBundesrat = dataset.newEntity("Occupancy", m.slug(), "bundesrat");
        occBundesrat.addValue("holder", person.getId());
        occBundesrat.addValue("post", posBundesrat.getId());
        occBundesrat.addValue("status", "current");
        occBundesrat.addValue("politicalGroup", m.party());
        occBundesrat.addValue("sourceUrl", m.pageUrl());
        dataset.addEntity(occBundesrat);

        // 2. Position Gouvernement de Land (DE-a)
        String regionalPosName = isMp ? "Ministre-président d'un Land (Ministerpräsident)"
                : "Ministre d'un gouvernement de Land (Landesminister, Senator)";
        String orgName = m.state() != null ? "Gouvernement du Land " + m.state() : "Gouvernement de Land";

        FtmEntity posLand = dataset.newEntity("Position", isMp ? "land-minister-president" : "land-minister", m.landCode());
        posLand.addValue("name", regionalPosName);
        posLand.addValue("country", "de");
        posLand.addValue("organization", orgName);
        posLand.addValue("topics", "gov.regional");
        posLand.addValue("topics", "gov.executive");
        dataset.addEntity(posLand);

        FtmEntity occLand = dataset.newEntity("Occupancy", m.slug(), "regional-exec");
        occLand.addValue("holder", person.getId());
        occLand.addValue("post", posLand.getId());
        occLand.addValue("status", "current");
        if (m.function() != null) {
            occLand.addValue("description", m.function());
        }
        occLand.addValue("sourceUrl", m.pageUrl());
        dataset.addEntity(occLand);
    }

    static boolean isMinisterPresident(String function) {
        if (function == null) return false;
        String f = function.toLowerCase();
        return f.contains("ministerpräsident") || f.contains("regierende") || f.contains("präsident des senats")
                || f.contains("bürgermeister") && (f.contains("hamburg") || f.contains("bremen") || f.contains("berlin"));
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        return name.replaceAll("(?i)\\b(Prof\\.|Dr\\.|h\\.c\\.)\\b", "").replaceAll("\\s+", " ").trim();
    }

    private String loadHtml(ParserContext ctx, Map<String, String> p) throws Exception {
        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            return Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
        }
        String url = p.getOrDefault("url", DEFAULT_URL).trim();
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " lors du téléchargement de " + url);
        }
        if (ctx.rawDir() != null) {
            Files.createDirectories(ctx.rawDir());
            Files.writeString(ctx.rawDir().resolve("bundesrat-members.html"), resp.body(), StandardCharsets.UTF_8);
        }
        return resp.body();
    }
}
