package com.swiftcheck.aml.plugins.ptpep.prosecution;

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
 * <b>pt_pep_prosecution</b> – Procureur Général de la République et Conseil Supérieur du Ministère Public (CSMP) au Portugal.
 *
 * <p>Couvre la catégorie <b>PT-c</b>, fonction <b>Conseils supérieurs de la magistrature, procureur général et Conseil supérieur du ministère public</b> (fonction 256)
 * (Lei n.º 83/2017, art. 2.º, n.º 1, al. cc) ; JO C/2023/724).
 *
 * <p>Extraction réseau directe depuis la composition officielle du Plénier du CSMP sur le portail officiel du Ministère Public (ministeriopublico.pt).
 */
@Extension
public class PtPepProsecutionParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepProsecutionParser.class);

    static final String ID_PREFIX = "pt-pep-prosecution";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "pt";
    static final String SOURCE_URL = "https://www.ministeriopublico.pt/pagina/composicao-do-conselho-superior-do-ministerio-publico?mid=3342";
    static final String HTML_FILE = "csmp-composicao.html";

    record OfficialRecord(
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
                String.format(Locale.ROOT, "%d dirigeants du Ministère Public et du CSMP du Portugal extraits directement depuis la source officielle.", count)
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
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Portugal - ministeriopublico.pt)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pt-PT,pt;q=0.9,en;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " lors du téléchargement de " + SOURCE_URL);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[pt_pep_prosecution] Page Composição CSMP téléchargée ({} caractères)", response.body().length());
    }

    public int convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<OfficialRecord> officials = extractOfficials(html);
        if (officials.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant trouvé dans : " + htmlFile);
        }

        FtmEntity positionGeneral = dataset.newEntity("Position", "csmp-membro");
        positionGeneral.addValue("name", "Membro do Conselho Superior do Ministério Público");
        positionGeneral.addValue("country", COUNTRY);
        positionGeneral.addValue("organization", "Conselho Superior do Ministério Público de Portugal");
        positionGeneral.addValue("topics", "gov.judicial");
        positionGeneral.addValue("topics", "gov.national");
        dataset.addEntity(positionGeneral);

        for (OfficialRecord o : officials) {
            FtmEntity person = dataset.newEntity("Person", o.fullName());
            person.addValue("name", o.fullName());
            if (!o.firstName().isEmpty()) {
                person.addValue("firstName", o.firstName());
            }
            if (!o.lastName().isEmpty()) {
                person.addValue("lastName", o.lastName());
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", o.positionTitle());
            person.addValue("notes", o.role() + " – Ministério Público de Portugal");
            person.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", o.fullName(), "csmp-membro");
            occ.addValue("holder", person.getId());
            occ.addValue("post", positionGeneral.getId());
            occ.addValue("status", "current");
            occ.addValue("description", o.role());
            occ.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(occ);
        }

        return officials.size();
    }

    static List<OfficialRecord> extractOfficials(String html) {
        Map<String, OfficialRecord> map = new LinkedHashMap<>();

        int startIdx = html.indexOf("PLENÁRIO:");
        if (startIdx < 0) return List.of();

        int endIdx = html.indexOf("Próxima Sessão", startIdx);
        if (endIdx < 0) endIdx = html.length();

        String section = html.substring(startIdx, endIdx);
        String text = section
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace('\u200B', ' ')
                .replace('\u00A0', ' ');

        String[] lines = text.split("\n");
        String currentRole = "Vogal do CSMP";

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("___") || line.startsWith("(artigo") || line.startsWith("(")) {
                continue;
            }

            if (line.contains("Presidente:") || line.contains("Procurador-Geral da República")) {
                currentRole = "Procurador-Geral da República / Presidente do CSMP";
                continue;
            } else if (line.contains("Procuradores-Gerais Regionais")) {
                currentRole = "Procurador-Geral Regional";
                continue;
            } else if (line.contains("Procurador-Geral-Adjunto")) {
                currentRole = "Procurador-Geral-Adjunto";
                continue;
            } else if (line.contains("Procuradores da República")) {
                currentRole = "Procurador da República / Vogal do CSMP";
                continue;
            } else if (line.contains("Membros eleitos pela Assembleia da República")) {
                currentRole = "Vogal do CSMP eleito pela Assembleia da República";
                continue;
            } else if (line.contains("Membros designados")) {
                currentRole = "Vogal do CSMP designado pela Ministra da Justiça";
                continue;
            } else if (line.contains("PLENÁRIO:") || line.contains("Vogais:")) {
                continue;
            }

            String name = cleanName(line);
            if (name.length() < 3 || name.startsWith("[")) {
                continue;
            }

            String[] fl = splitFirstLast(name);
            String title = currentRole.contains("Procurador-Geral da República")
                    ? "Procurador-Geral da República de Portugal"
                    : currentRole;

            map.putIfAbsent(name.toLowerCase(Locale.ROOT), new OfficialRecord(
                    name,
                    fl[0],
                    fl[1],
                    currentRole,
                    title
            ));
        }

        return new ArrayList<>(map.values());
    }

    static String cleanName(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("[\\u200B\\u00A0]", " ")
                .replaceAll("(?i)^Conselheiro\\s+", "")
                .replaceAll("(?i)^Dr\\.?ª?\\s+", "")
                .replaceAll("(?i)^Professora\\s+Doutora\\s+", "")
                .replaceAll("(?i)^Professor\\s+Doutor\\s+", "")
                .replaceAll("\\s*\\(.*?\\)\\s*", " ")
                .replaceAll("\\s*–\\s*Membro.*$", "")
                .replaceAll("\\s*-\\s*Membro.*$", "")
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
}
