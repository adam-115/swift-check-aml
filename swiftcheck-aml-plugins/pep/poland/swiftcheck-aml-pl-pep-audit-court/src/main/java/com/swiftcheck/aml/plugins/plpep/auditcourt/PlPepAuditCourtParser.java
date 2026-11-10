package com.swiftcheck.aml.plugins.plpep.auditcourt;

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
import java.util.HashMap;
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
 * <b>pl_pep_audit_court</b> – Dirigeants de la Cour des comptes polonaise (Najwyższa Izba Kontroli - NIK).
 *
 * <p>Couvre la catégorie <b>PL-e</b>, fonction <b>Dirigeant de la Cour des comptes (NIK)</b> (fonction 491)
 * (Loi du 1er mars 2018, art. 2 ust. 2 pkt 11 ; JO C/2023/724).
 *
 * <p>Extraction réseau directe depuis la composition officielle du Kolegium et du Kierownictwo de la NIK :
 * {@code https://www.nik.gov.pl/o-nik/kolegium-nik/czlonkowie-kolegium-nik.html}
 */
@Extension
public class PlPepAuditCourtParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PlPepAuditCourtParser.class);

    static final String ID_PREFIX = "pl-pep-audit-court";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pl";
    static final String SOURCE_URL = "https://www.nik.gov.pl/o-nik/kolegium-nik/czlonkowie-kolegium-nik.html";
    static final String KIEROWNICTWO_URL = "https://www.nik.gov.pl/o-nik/kierownictwo-nik/";
    static final String HTML_FILE = "nik-kolegium.html";
    static final String BASE_URL = "https://www.nik.gov.pl";
    static final String ORG_NAME = "Najwyższa Izba Kontroli (NIK)";

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "<h2[^>]*>(.*?)</h2>[\\s\\S]*?<ul>([\\s\\S]*?)</ul>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LI_PATTERN = Pattern.compile(
            "<li[^>]*>([\\s\\S]*?)</li>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STRONG_PATTERN = Pattern.compile(
            "<strong[^>]*>([\\s\\S]*?)</strong>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STRIP_TAGS_PATTERN = Pattern.compile("<[^>]+>");

    public record OfficialRecord(
            String section,
            String rawName,
            String cleanName,
            String role,
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
                String.format(Locale.ROOT, "%d dirigeants et membres du Collège de la Cour des comptes de Pologne (NIK) indexés.", count)
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
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pl,en-US;q=0.7,en;q=0.3")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " lors du téléchargement de " + SOURCE_URL);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[pl_pep_audit_court] Page Kolegium NIK téléchargée ({} caractères)", response.body().length());
    }

    public int convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<OfficialRecord> officials = extractOfficials(html);
        if (officials.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant trouvé dans : " + htmlFile);
        }

        // Photos connues du Kierownictwo NIK
        Map<String, String> photos = new HashMap<>();
        photos.put("Mariusz Haładyj", BASE_URL + "/plik/id,31409.jpg");
        photos.put("Jacek Kozłowski", BASE_URL + "/plik/id,28622.jpg");
        photos.put("Katarzyna Budnikowska", BASE_URL + "/plik/id,31617.jpg");
        photos.put("Sylweriusz Królak", BASE_URL + "/plik/id,31618.jpg");
        photos.put("Małgorzata Szczepańska", BASE_URL + "/plik/id,31619.jpg");

        int count = 0;
        for (OfficialRecord o : officials) {
            String cleanName = o.cleanName();
            if (cleanName.isEmpty()) {
                continue;
            }

            FtmEntity person = dataset.newEntity("Person", cleanName);
            person.addValue("name", cleanName);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", o.role());
            person.addValue("sourceUrl", SOURCE_URL);

            String photo = photos.get(cleanName);
            if (photo != null) {
                dataset.addMedia(person.getId(), photo, SOURCE_URL, "© Najwyższa Izba Kontroli (NIK)");
            }

            dataset.addEntity(person);

            String posSlug = o.role().toLowerCase(Locale.ROOT)
                    .replaceAll("[ąćęłńóśźż]", "x")
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (posSlug.isEmpty()) {
                posSlug = "czlonek-kolegium-nik";
            }

            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", o.role());
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", ORG_NAME);
            pos.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(pos);

            FtmEntity occupancy = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", pos.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", o.role());
            dataset.addEntity(occupancy);

            count++;
        }
        return count;
    }

    public List<OfficialRecord> extractOfficials(String html) {
        List<OfficialRecord> results = new ArrayList<>();
        Matcher sectionMatcher = SECTION_PATTERN.matcher(html);

        while (sectionMatcher.find()) {
            String sectionTitle = cleanText(sectionMatcher.group(1));
            String ulContent = sectionMatcher.group(2);

            Matcher liMatcher = LI_PATTERN.matcher(ulContent);
            while (liMatcher.find()) {
                String rawLi = liMatcher.group(1);

                Matcher strongMatcher = STRONG_PATTERN.matcher(rawLi);
                String name = "";
                String role = "";

                if (strongMatcher.find()) {
                    name = cleanText(strongMatcher.group(1));
                    String rest = rawLi.substring(0, strongMatcher.start()) + rawLi.substring(strongMatcher.end());
                    role = cleanText(rest).replaceAll("^[,–\\-\\s]+", "").trim();
                } else {
                    String full = cleanText(rawLi);
                    String[] parts = full.split("[,–\\-]", 2);
                    name = parts[0].trim();
                    role = parts.length > 1 ? parts[1].trim() : "";
                }

                String cleanName = name.replaceAll("^(prof\\.\\s+|dr\\s+|hab\\.\\s+)+", "").trim();

                if (role.isEmpty()) {
                    if (sectionTitle.toLowerCase(Locale.ROOT).contains("nauk prawnych")) {
                        role = "Członek Kolegium NIK (przedstawiciel nauk prawnych i ekonomicznych)";
                    } else if (sectionTitle.toLowerCase(Locale.ROOT).contains("jednostek")) {
                        role = "Członek Kolegium NIK";
                    } else {
                        role = "Członek Kolegium NIK";
                    }
                }

                results.add(new OfficialRecord(sectionTitle, name, cleanName, role, null));
            }
        }
        return results;
    }

    private static String cleanText(String s) {
        if (s == null) {
            return "";
        }
        return STRIP_TAGS_PATTERN.matcher(s)
                .replaceAll(" ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
