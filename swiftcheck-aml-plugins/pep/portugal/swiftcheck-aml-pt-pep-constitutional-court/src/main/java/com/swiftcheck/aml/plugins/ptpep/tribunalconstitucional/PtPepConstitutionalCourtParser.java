package com.swiftcheck.aml.plugins.ptpep.tribunalconstitucional;

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
 * <b>pt_pep_constitutional_court</b> – Juges du Tribunal constitutionnel du Portugal (Tribunal Constitucional).
 *
 * <p>Couvre la catégorie <b>PT-c</b>, fonction <b>Juge du Tribunal constitutionnel</b> (fonction 253)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Extraction réseau directe depuis la composition officielle du Plénier du Tribunal Constitucional.
 */
@Extension
public class PtPepConstitutionalCourtParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepConstitutionalCourtParser.class);

    static final String ID_PREFIX = "pt-pep-constitutional-court";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String SOURCE_URL = "https://www.tribunalconstitucional.pt/tc/juizes01.html";
    static final String HTML_FILE = "tc-plenario.html";
    static final String BASE_URL = "https://www.tribunalconstitucional.pt";
    static final String PHOTO_LICENCE = "© Tribunal Constitucional de Portugal";

    private static final Pattern OVERLAY_PATTERN = Pattern.compile(
            "<div class=\"overlayjuiztopo\">[\\s\\S]*?<img[^>]+src=\"([^\"]+)\"[\\s\\S]*?<div class=\"overlayjuiztitles\">\\s*<h3>(.*?)</h3>\\s*<span class=\"description\">(?:<span class=\"desctitle\"></span>)?(.*?)</span>[\\s\\S]*?<div class=\"overlayjuizbody\">([\\s\\S]*?)</div>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "desde\\s+([a-zA-ZçÇ]+)\\s+de\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE
    );

    record JudgeRecord(
            String fullName,
            String firstName,
            String lastName,
            String role,
            String fullRole,
            String startDate,
            String photoUrl,
            String bio
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
                String.format(Locale.ROOT, "%d juges du Tribunal constitutionnel du Portugal extraits directement depuis la source officielle.", count)
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
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Portugal - tribunalconstitucional.pt)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pt-PT,pt;q=0.9,en;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " lors du téléchargement de " + SOURCE_URL);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[pt_pep_constitutional_court] Page Plenário téléchargée ({} caractères)", response.body().length());
    }

    public int convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<JudgeRecord> judges = extractJudges(html);
        if (judges.isEmpty()) {
            throw new IllegalStateException("Aucun juge trouvé dans : " + htmlFile);
        }

        FtmEntity positionGeneral = dataset.newEntity("Position", "tc-juiz-conselheiro");
        positionGeneral.addValue("name", "Juiz do Tribunal Constitucional de Portugal");
        positionGeneral.addValue("country", COUNTRY);
        positionGeneral.addValue("organization", "Tribunal Constitucional de Portugal");
        positionGeneral.addValue("topics", "gov.judicial");
        positionGeneral.addValue("topics", "gov.national");
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
            person.addValue("nationality", COUNTRY);
            person.addValue("position", j.role() + " – Tribunal Constitucional");
            person.addValue("notes", j.fullRole());
            person.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(person);

            if (j.photoUrl() != null && !j.photoUrl().isBlank()) {
                dataset.addMedia(person.getId(), j.photoUrl(), SOURCE_URL, PHOTO_LICENCE);
            }

            FtmEntity occ = dataset.newEntity("Occupancy", j.fullName(), "tc-juiz-conselheiro");
            occ.addValue("holder", person.getId());
            occ.addValue("post", positionGeneral.getId());
            occ.addValue("status", "current");
            occ.addValue("description", j.role());
            if (j.startDate() != null && !j.startDate().isBlank()) {
                occ.addValue("startDate", j.startDate());
            }
            occ.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(occ);
        }

        return judges.size();
    }

    static List<JudgeRecord> extractJudges(String html) {
        Map<String, JudgeRecord> map = new LinkedHashMap<>();
        Matcher m = OVERLAY_PATTERN.matcher(html);
        while (m.find()) {
            String photoHref = cleanText(m.group(1));
            String fullName = cleanText(m.group(2));
            String desc = cleanText(m.group(3));
            String bio = cleanText(m.group(4));

            if (fullName.isEmpty()) continue;

            String photoUrl = photoHref.startsWith("http") ? photoHref : BASE_URL + (photoHref.startsWith("/") ? "" : "/") + photoHref;
            String[] names = splitFirstLast(fullName);

            String role = "Juiz Conselheiro";
            if (desc.toLowerCase(Locale.ROOT).contains("presidente") && !desc.toLowerCase(Locale.ROOT).contains("vice-presidente")) {
                role = "Presidente do Tribunal Constitucional";
            } else if (desc.toLowerCase(Locale.ROOT).contains("vice-presidente")) {
                role = "Vice-Presidente do Tribunal Constitucional";
            } else if (desc.toLowerCase(Locale.ROOT).contains("juíza") || desc.toLowerCase(Locale.ROOT).contains("juiza")) {
                role = "Juíza Conselheira do Tribunal Constitucional";
            } else if (desc.toLowerCase(Locale.ROOT).contains("juiz")) {
                role = "Juiz Conselheiro do Tribunal Constitucional";
            }

            String startDate = parseStartDate(desc);

            map.putIfAbsent(fullName.toLowerCase(Locale.ROOT), new JudgeRecord(
                    fullName,
                    names[0],
                    names[1],
                    role,
                    desc,
                    startDate,
                    photoUrl,
                    bio
            ));
        }
        return new ArrayList<>(map.values());
    }

    static String parseStartDate(String desc) {
        if (desc == null) return null;
        Matcher m = DATE_PATTERN.matcher(desc);
        if (m.find()) {
            String monthName = m.group(1).toLowerCase(Locale.ROOT);
            String year = m.group(2);
            String month = monthToNum(monthName);
            if (month != null) {
                return year + "-" + month;
            }
            return year;
        }
        return null;
    }

    static String monthToNum(String monthName) {
        return switch (monthName) {
            case "janeiro" -> "01";
            case "fevereiro" -> "02";
            case "março", "marco" -> "03";
            case "abril" -> "04";
            case "maio" -> "05";
            case "junho" -> "06";
            case "julho" -> "07";
            case "agosto" -> "08";
            case "setembro" -> "09";
            case "outubro" -> "10";
            case "novembro" -> "11";
            case "dezembro" -> "12";
            default -> null;
        };
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
                .replace("&ccedil;", "ç")
                .replace("&Ccedil;", "Ç")
                .replace("&otilde;", "õ")
                .replace("&Otilde;", "Õ")
                .replace("&atilde;", "ã")
                .replace("&Atilde;", "Ã")
                .replace("&eacute;", "é")
                .replace("&Eacute;", "É")
                .replace("&iacute;", "í")
                .replace("&Iacute;", "Í")
                .replace("&oacute;", "ó")
                .replace("&Oacute;", "Ó")
                .replace("&uacute;", "ú")
                .replace("&Uacute;", "Ú")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
