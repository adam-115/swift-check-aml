package com.swiftcheck.aml.plugins.ptpep.supremecourts;

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
 * <b>pt_pep_supreme_courts</b> – Magistrats du Supremo Tribunal de Justiça (STJ).
 *
 * <p>Couvre la catégorie <b>PT-c</b>, fonction <b>Juge conseiller de la Cour suprême de justice et de la Cour suprême administrative</b> (fonction 254)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Extraction réseau directe depuis la structure officielle du Supremo Tribunal de Justiça (stj.pt).
 */
@Extension
public class PtPepSupremeCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepSupremeCourtsParser.class);

    static final String ID_PREFIX = "pt-pep-supreme-courts";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String SOURCE_URL = "https://www.stj.pt/estrutura/";
    static final String HTML_FILE = "stj-estrutura.html";

    private static final Pattern PRESIDENCY_PATTERN = Pattern.compile(
            "<div class=\"text-presidencia[^\"]*\">\\s*<h3>(.*?)</h3>\\s*<p>(.*?)</p>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SECTIONS_PATTERN = Pattern.compile(
            "<tr>\\s*<td>([\\s\\S]*?)</td>\\s*<td>[\\s\\S]*?<span class=\"span-nome\">(.*?)</span>",
            Pattern.CASE_INSENSITIVE
    );

    record JudgeRecord(
            String fullName,
            String firstName,
            String lastName,
            String role,
            String positionTitle
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
                String.format(Locale.ROOT, "%d magistrats du Supremo Tribunal de Justiça du Portugal extraits directement depuis la source officielle.", count)
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
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Portugal - stj.pt)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pt-PT,pt;q=0.9,en;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " lors du téléchargement de " + SOURCE_URL);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[pt_pep_supreme_courts] Page Estrutura STJ téléchargée ({} caractères)", response.body().length());
    }

    public int convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<JudgeRecord> judges = extractJudges(html);
        if (judges.isEmpty()) {
            throw new IllegalStateException("Aucun juge trouvé dans : " + htmlFile);
        }

        FtmEntity positionGeneral = dataset.newEntity("Position", "stj-juiz-conselheiro");
        positionGeneral.addValue("name", "Juiz Conselheiro do Supremo Tribunal de Justiça");
        positionGeneral.addValue("country", COUNTRY);
        positionGeneral.addValue("organization", "Supremo Tribunal de Justiça de Portugal");
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
            person.addValue("position", j.positionTitle());
            person.addValue("notes", j.role() + " – Supremo Tribunal de Justiça");
            person.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", j.fullName(), "stj-juiz-conselheiro");
            occ.addValue("holder", person.getId());
            occ.addValue("post", positionGeneral.getId());
            occ.addValue("status", "current");
            occ.addValue("description", j.role());
            occ.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(occ);
        }

        return judges.size();
    }

    static List<JudgeRecord> extractJudges(String html) {
        Map<String, JudgeRecord> map = new LinkedHashMap<>();

        // 1. Presidência
        Matcher mPres = PRESIDENCY_PATTERN.matcher(html);
        while (mPres.find()) {
            String role = cleanText(mPres.group(1));
            String name = cleanText(mPres.group(2));
            if (name.isEmpty()) continue;

            String[] fl = splitFirstLast(name);
            String title = role + " do Supremo Tribunal de Justiça";
            map.put(name.toLowerCase(Locale.ROOT), new JudgeRecord(
                    name,
                    fl[0],
                    fl[1],
                    role,
                    title
            ));
        }

        // 2. Secções
        Matcher mSec = SECTIONS_PATTERN.matcher(html);
        while (mSec.find()) {
            String rawRole = cleanText(mSec.group(1));
            String name = cleanText(mSec.group(2));
            if (name.isEmpty()) continue;

            String role = rawRole.contains("Presidente de Secção")
                    ? "Presidente de Secção | Juiz Conselheiro"
                    : "Juiz Conselheiro";

            String key = name.toLowerCase(Locale.ROOT);
            if (!map.containsKey(key)) {
                String[] fl = splitFirstLast(name);
                String title = role + " do Supremo Tribunal de Justiça";
                map.put(key, new JudgeRecord(
                        name,
                        fl[0],
                        fl[1],
                        role,
                        title
                ));
            }
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
