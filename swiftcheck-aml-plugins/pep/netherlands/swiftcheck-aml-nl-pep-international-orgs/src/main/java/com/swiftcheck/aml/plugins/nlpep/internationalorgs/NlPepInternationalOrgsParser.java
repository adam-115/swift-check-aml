package com.swiftcheck.aml.plugins.nlpep.internationalorgs;

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
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>nl_pep_international_orgs</b> – Juges et dirigeants de la Cour internationale de Justice (CIJ / ICJ) siégeant à La Haye (Pays-Bas).
 *
 * <p>Couvre la catégorie <b>NL-h</b>, fonction <b>Dirigeant d'une organisation internationale</b> (fonction 390)
 * (Wwft art. 1 ; JO C/2023/724).
 *
 * <p>Extraction réseau directe depuis la composition officielle des membres en exercice de la Cour internationale de Justice (icj-cij.org).
 */
@Extension
public class NlPepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(NlPepInternationalOrgsParser.class);

    static final String ID_PREFIX = "nl-pep-international-orgs";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "nl";
    static final String BASE_URL = "https://www.icj-cij.org";
    static final String SOURCE_URL = "https://www.icj-cij.org/current-members";
    static final String HTML_FILE = "icj-members.html";
    static final String PHOTO_LICENCE = "© International Court of Justice";

    private static final Pattern MEMBER_BLOCK_PATTERN = Pattern.compile(
            "<div class=\"col-sm-3\">[\\s\\S]*?<img[^>]+src=\"([^\"]+)\"[\\s\\S]*?<div class=\"col-sm-9\">[\\s\\S]*?<h1[^>]*>[\\s\\S]*?<a[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?<h4>(.*?)</h4>[\\s\\S]*?<h5>([\\s\\S]*?)</h5>",
            Pattern.CASE_INSENSITIVE
    );

    record JudgeRecord(
            String fullName,
            String firstName,
            String lastName,
            String role,
            String nationality,
            String tenure,
            String photoUrl
    ) {}

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawHtml = ctx.rawDir().resolve(HTML_FILE);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        download(client, rawHtml);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawHtml, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d juges et hauts dirigeants de la Cour internationale de Justice à La Haye extraits directement depuis la source officielle.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawHtml = ctx.rawDir().resolve(HTML_FILE);
        if (!Files.exists(rawHtml)) {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            download(client, rawHtml);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawHtml, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        SOURCE_URL));
            }
        }
        return records;
    }

    void download(HttpClient client, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(SOURCE_URL))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Pays-Bas - icj-cij.org)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,fr;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " lors du téléchargement de " + SOURCE_URL);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[nl_pep_international_orgs] Page Current Members CIJ téléchargée ({} caractères)", response.body().length());
    }

    public int convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<JudgeRecord> judges = extractJudges(html);
        if (judges.isEmpty()) {
            throw new IllegalStateException("Aucun juge trouvé dans : " + htmlFile);
        }

        FtmEntity positionGeneral = dataset.newEntity("Position", "icj-judge");
        positionGeneral.addValue("name", "Judge of the International Court of Justice (La Haye)");
        positionGeneral.addValue("country", COUNTRY);
        positionGeneral.addValue("organization", "International Court of Justice (Cour internationale de Justice - CIJ)");
        positionGeneral.addValue("topics", "gov.igo");
        positionGeneral.addValue("topics", "gov.judicial");
        dataset.addEntity(positionGeneral);

        for (JudgeRecord j : judges) {
            FtmEntity person = dataset.newEntity("Person", j.fullName());
            person.addValue("name", j.fullName());
            if (!j.firstName().isEmpty()) {
                person.addValue("firstName", j.firstName());
            }
            if (!j.lastName().isEmpty()) {
                person.addValue("lastName", j.lastName());
            }
            person.addValue("country", COUNTRY);
            person.addValue("position", j.role() + " – International Court of Justice");
            person.addValue("notes", j.tenure() + " (" + j.nationality() + ")");
            person.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(person);

            if (j.photoUrl() != null && !j.photoUrl().isBlank()) {
                dataset.addMedia(person.getId(), j.photoUrl(), SOURCE_URL, PHOTO_LICENCE);
            }

            FtmEntity occ = dataset.newEntity("Occupancy", j.fullName(), "icj-judge");
            occ.addValue("holder", person.getId());
            occ.addValue("post", positionGeneral.getId());
            occ.addValue("status", "current");
            occ.addValue("description", j.role() + " (" + j.nationality() + ")");
            occ.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(occ);
        }

        return judges.size();
    }

    static List<JudgeRecord> extractJudges(String html) {
        Map<String, JudgeRecord> map = new LinkedHashMap<>();
        Matcher m = MEMBER_BLOCK_PATTERN.matcher(html);
        while (m.find()) {
            String photoHref = cleanText(m.group(1));
            String rawTitleName = cleanText(m.group(2)).replaceAll("\\s+", " ").trim();
            String country = cleanText(m.group(3)).trim();
            String tenure = cleanText(m.group(4)).replaceAll("\\s+", " ").trim();

            String role = "Judge of the International Court of Justice";
            String name = rawTitleName;
            if (rawTitleName.startsWith("President ")) {
                role = "President of the International Court of Justice";
                name = rawTitleName.substring("President ".length()).trim();
            } else if (rawTitleName.startsWith("Vice-President ")) {
                role = "Vice-President of the International Court of Justice";
                name = rawTitleName.substring("Vice-President ".length()).trim();
            } else if (rawTitleName.startsWith("Judge ")) {
                role = "Judge of the International Court of Justice";
                name = rawTitleName.substring("Judge ".length()).trim();
            }

            if (name.isEmpty()) continue;

            String photoUrl = photoHref.startsWith("http") ? photoHref : BASE_URL + (photoHref.startsWith("/") ? "" : "/") + photoHref;
            String[] fl = splitFirstLast(name);

            map.putIfAbsent(name.toLowerCase(Locale.ROOT), new JudgeRecord(
                    name,
                    fl[0],
                    fl[1],
                    role,
                    country,
                    tenure,
                    photoUrl
            ));
        }

        return new ArrayList<>(map.values());
    }

    static String[] splitFirstLast(String fullName) {
        String cleaned = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        int firstSpace = cleaned.indexOf(' ');
        int lastSpace = cleaned.lastIndexOf(' ');
        if (firstSpace < 0) {
            return new String[]{"", cleaned};
        }
        return new String[]{cleaned.substring(0, firstSpace).trim(), cleaned.substring(lastSpace + 1).trim()};
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
