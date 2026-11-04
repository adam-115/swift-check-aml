package com.swiftcheck.aml.plugins.depep.federalgovernment;

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
 * <b>de_pep_federal_government</b> – Gouvernement fédéral d'Allemagne (Bundesregierung, Bundeskabinett ;
 * catégorie DE-a ; GwG § 1 (12) a) ; JO C/2023/724).
 *
 * <p>Source : composition officielle du cabinet fédéral sur bundesregierung.de.
 */
@Extension
public class DePepFederalGovernmentParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepFederalGovernmentParser.class);

    static final String ID_PREFIX = "de-pep-federal-government";
    static final String TOPIC = "role.pep";

    static final String DEFAULT_URL = "https://www.bundesregierung.de/breg-de/bundesregierung/bundeskabinett";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Gouvernement fédéral)";
    static final String PHOTO_LICENCE = "© Bundesregierung";

    private static final Pattern CABINET_TEASER = Pattern.compile("(?si)<li[^>]*class=\"[^\"]*bpa-teaser-wrapper[^\"]*\"[^>]*>(.*?)</li>\\s*(?=<li[^>]*class=\"[^\"]*bpa-teaser-wrapper\"|</ul>|$)");

    private static final Pattern LINK = Pattern.compile("(?i)<a[^>]+href=[\"']([^\"']*/bundeskabinett/([^\"']+))[\"'][^>]*class=[\"']bpa-link[\"']");
    private static final Pattern NAME = Pattern.compile("(?si)<span class=\"bpa-teaser-title-text-inner\">\\s*([^<]+?)\\s*</span>");
    private static final Pattern PHOTO = Pattern.compile("(?i)<img[^>]+src=[\"']([^\"']+)[\"']");
    private static final Pattern CABINET_ITEM = Pattern.compile("(?si)<li[^>]*class=\"[^\"]*bpa-teaser-cabinet-item[^\"]*\"[^>]*>\\s*<a[^>]*>(.*?)</a>");

    record Member(String slug, String name, String role, String pageUrl, String photo) {
    }

    record Report(int totalMembers, int ministers) {
        String message() {
            return String.format("%d membres du cabinet fédéral extraits de bundesregierung.de.", totalMembers);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String html = loadHtml(ctx, p);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<Member> members = extractMembers(html);

        for (Member m : members) {
            write(m, dataset);
        }

        dataset.writeNdjsonFile(ctx.outputFile());
        Report report = new Report(members.size(), members.size());
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
        Matcher m = CABINET_TEASER.matcher(html);
        while (m.find()) {
            String block = m.group(1);

            Matcher linkM = LINK.matcher(block);
            if (!linkM.find()) {
                continue;
            }
            String rawUrl = linkM.group(1);
            String slug = linkM.group(2);
            String pageUrl = rawUrl.startsWith("http") ? rawUrl : "https://www.bundesregierung.de" + rawUrl;

            Matcher nameM = NAME.matcher(block);
            String name = nameM.find() ? Html.text(nameM.group(1)) : slug.replace('-', ' ');

            String photo = null;
            Matcher photoM = PHOTO.matcher(block);
            if (photoM.find()) {
                String src = photoM.group(1);
                photo = src.startsWith("http") ? src : "https://www.bundesregierung.de" + src;
            }

            String role = "Mitglied des Bundeskabinetts";
            Matcher roleM = CABINET_ITEM.matcher(block);
            while (roleM.find()) {
                String itemText = Html.text(roleM.group(1));
                if (!itemText.equalsIgnoreCase("Lebenslauf") && !itemText.isBlank()) {
                    role = itemText;
                    break;
                }
            }

            list.add(new Member(slug, cleanName(name), role, pageUrl, photo));
        }
        return list;
    }

    private static void write(Member m, FtmDatasetBuilder dataset) {
        boolean isChancellor = isChancellor(m.role());
        String posName = isChancellor ? "Chancelier fédéral (Bundeskanzler)"
                : "Ministre fédéral ou ministre d'État (Bundesminister, Staatsminister)";

        FtmEntity person = dataset.newEntity("Person", m.slug());
        person.addValue("name", m.name());
        String[] parts = m.name().split("\\s+", 2);
        person.addValue("firstName", parts[0]);
        if (parts.length > 1) person.addValue("lastName", parts[1]);
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", m.role());
        person.addValue("sourceUrl", m.pageUrl());
        dataset.addEntity(person);

        if (m.photo() != null) {
            dataset.addMedia(person.getId(), m.photo(), m.pageUrl(), PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", isChancellor ? "chancellor" : "minister", m.slug());
        position.addValue("name", posName);
        position.addValue("country", "de");
        position.addValue("organization", "Gouvernement fédéral");
        position.addValue("topics", "gov.national");
        position.addValue("topics", isChancellor ? "gov.headofgov" : "gov.executive");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.slug(), "cabinet");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", m.role());
        occupancy.addValue("sourceUrl", m.pageUrl());
        dataset.addEntity(occupancy);
    }

    static boolean isChancellor(String role) {
        if (role == null) return false;
        String r = role.toLowerCase();
        return r.contains("bundeskanzler") && !r.contains("chefin") && !r.contains("chef des");
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
            Files.writeString(ctx.rawDir().resolve("bundeskabinett.html"), resp.body(), StandardCharsets.UTF_8);
        }
        return resp.body();
    }
}
