package com.swiftcheck.aml.plugins.ptpep.stateorgans;

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
 * <b>pt_pep_state_organs</b> – Membres du Conseil d'État (Conselho de Estado) et hauts organes d'État du Portugal.
 *
 * <p>Couvre la catégorie <b>PT-ETAT</b>, fonction <b>Conseil d'État, médiateur (Provedor de Justiça), CSDN et Conseil économique et social</b> (fonction 260)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Extraction réseau directe depuis la composition officielle du Conseil d'État sur le portail de la Présidence de la République.
 */
@Extension
public class PtPepStateOrgansParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepStateOrgansParser.class);

    static final String ID_PREFIX = "pt-pep-state-organs";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String SOURCE_URL = "https://www.presidencia.pt/presidente-da-republica/as-funcoes/conselho-de-estado/";
    static final String HTML_FILE = "conselho-estado.html";

    private static final Pattern LI_PATTERN = Pattern.compile("<li>([\\s\\S]*?)</li>", Pattern.CASE_INSENSITIVE);

    record MemberRecord(
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
                String.format(Locale.ROOT, "%d membres du Conseil d'État du Portugal extraits directement depuis la présidence de la République.", count)
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
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Portugal - presidencia.pt)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pt-PT,pt;q=0.9,en;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " lors du téléchargement de " + SOURCE_URL);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[pt_pep_state_organs] Page Conselho de Estado téléchargée ({} caractères)", response.body().length());
    }

    public int convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<MemberRecord> members = extractMembers(html);
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre trouvé dans : " + htmlFile);
        }

        FtmEntity positionGeneral = dataset.newEntity("Position", "conselho-de-estado");
        positionGeneral.addValue("name", "Membro do Conselho de Estado de Portugal");
        positionGeneral.addValue("country", COUNTRY);
        positionGeneral.addValue("organization", "Conselho de Estado de Portugal");
        positionGeneral.addValue("topics", "gov.national");
        positionGeneral.addValue("topics", "gov.executive");
        dataset.addEntity(positionGeneral);

        for (MemberRecord m : members) {
            FtmEntity person = dataset.newEntity("Person", m.fullName());
            person.addValue("name", m.fullName());
            if (!m.firstName().isEmpty()) {
                person.addValue("firstName", m.firstName());
            }
            if (!m.lastName().isEmpty()) {
                person.addValue("lastName", m.lastName());
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", m.positionTitle());
            person.addValue("notes", m.role() + " – Conselho de Estado");
            person.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", m.fullName(), "conselho-de-estado");
            occ.addValue("holder", person.getId());
            occ.addValue("post", positionGeneral.getId());
            occ.addValue("status", "current");
            occ.addValue("description", m.role());
            occ.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(occ);
        }

        return members.size();
    }

    static List<MemberRecord> extractMembers(String html) {
        Map<String, MemberRecord> map = new LinkedHashMap<>();

        int tabIdx = html.indexOf("id=\"pr-tab-213230\"");
        if (tabIdx < 0) return List.of();

        int articleIdx = html.indexOf("<div class=\"article-content\">", tabIdx);
        if (articleIdx < 0) return List.of();

        int articleEnd = html.indexOf("</div>", articleIdx);
        if (articleEnd < 0) return List.of();

        String articleHtml = html.substring(articleIdx, articleEnd);

        Matcher m = LI_PATTERN.matcher(articleHtml);
        while (m.find()) {
            String raw = m.group(1).replaceAll("<br\\s*/?>", "\n");
            raw = cleanText(raw);
            String[] lines = raw.split("\n");
            List<String> trimmed = new ArrayList<>();
            for (String l : lines) {
                String t = l.trim();
                if (!t.isEmpty()) trimmed.add(t);
            }

            String name;
            String role;
            if (trimmed.size() >= 2) {
                role = trimmed.get(0);
                name = cleanName(trimmed.get(1));
            } else if (trimmed.size() == 1) {
                name = cleanName(trimmed.get(0));
                role = "Membro do Conselho de Estado";
            } else {
                continue;
            }

            if (name.isEmpty() || name.length() < 3) continue;

            String[] fl = splitFirstLast(name);
            String posTitle = role.equals("Membro do Conselho de Estado")
                    ? "Membro do Conselho de Estado"
                    : role + " / Conselheiro de Estado";

            map.putIfAbsent(name.toLowerCase(Locale.ROOT), new MemberRecord(
                    name,
                    fl[0],
                    fl[1],
                    role,
                    posTitle
            ));
        }

        return new ArrayList<>(map.values());
    }

    static String cleanName(String name) {
        if (name == null) return "";
        return name
                .replaceAll("(?i)^General\\s+", "")
                .replaceAll("(?i)^Juiz\\s+Conselheiro\\s+", "")
                .replaceAll("(?i)^Dr\\.?\\s+", "")
                .replaceAll("(?i)^Prof\\.?\\s+", "")
                .trim();
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
        return s.replaceAll("<[^>]+>", "")
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
                .trim();
    }
}
