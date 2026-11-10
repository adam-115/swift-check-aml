package com.swiftcheck.aml.plugins.ropep.bnr;

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
 * <b>ro_pep_central_bank</b> – Membres du conseil d'administration de la Banque Nationale de Roumanie (Banca Națională a României - BNR).
 *
 * <p>Couvre la catégorie <b>RO-e</b>, fonction <b>Membre de la direction de la banque centrale ou de la Cour des comptes</b> (fonction 739)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 *
 * <p>Extraction réseau directe depuis le portail officiel de la BNR (endpoint des blocs dynamiques du Conseil d'administration).
 */
@Extension
public class RoPepCentralBankParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepCentralBankParser.class);

    static final String ID_PREFIX = "ro-pep-bnr";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String BASE_URL = "https://www.bnr.ro";
    static final String BLOCKS_URL = "https://www.bnr.ro/blocks";
    static final String PAGE_SLUG = "1190-consiliul-de-administratie";
    static final String BLOCK_ID = "1682";
    static final String HTML_FILE = "bnr-board.html";
    static final String POSITION_NAME = "Membru în Consiliul de administrație al Băncii Naționale a României";

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "<div class=\"change-box box-slide\">[\\s\\S]*?<a[^>]*href=\"([^\"]*)\"[^>]*>\\s*<h4[^>]*>(.*?)</h4>[\\s\\S]*?<p class=\"gold\">([\\s\\S]*?)</p>[\\s\\S]*?(?:<img[^>]+src=\"([^\"]+)\")?[\\s\\S]*?</div>\\s*</div>",
            Pattern.CASE_INSENSITIVE
    );

    record BnrMember(String fullName, String rawName, String role, String profileUrl, String photoUrl) {}

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
                String.format(Locale.ROOT, "%d membres du conseil d'administration de la BNR extraits directement depuis le portail officiel.", count)
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
                        BASE_URL + "/" + PAGE_SLUG));
            }
        }
        return records;
    }

    void download(HttpClient client, Path target) throws IOException, InterruptedException {
        String postBody = "bid=" + BLOCK_ID + "&currentSlug=" + PAGE_SLUG;
        HttpRequest request = HttpRequest.newBuilder(URI.create(BLOCKS_URL))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Roumanie - BNR)")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", BASE_URL + "/" + PAGE_SLUG)
                .POST(HttpRequest.BodyPublishers.ofString(postBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " lors du téléchargement du bloc BNR " + BLOCK_ID);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[ro_pep_central_bank] Conseil d'administration BNR téléchargé ({} caractères)", response.body().length());
    }

    public int convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<BnrMember> members = extractMembers(html);
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre trouvé dans le document BNR : " + htmlFile);
        }

        FtmEntity position = dataset.newEntity("Position", "bnr-board");
        position.addValue("name", POSITION_NAME);
        position.addValue("country", COUNTRY);
        position.addValue("organization", "Banca Națională a României");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.financial");
        dataset.addEntity(position);

        for (BnrMember m : members) {
            FtmEntity person = dataset.newEntity("Person", m.fullName());
            person.addValue("name", m.fullName());
            if (!m.fullName().equals(m.rawName())) {
                person.addValue("alias", m.rawName());
            }
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            person.addValue("position", m.role() + " – BNR");
            person.addValue("notes", m.role() + " – Consiliul de administrație al Băncii Naționale a României");
            person.addValue("sourceUrl", m.profileUrl());
            dataset.addEntity(person);

            if (m.photoUrl() != null && !m.photoUrl().isBlank()) {
                dataset.addMedia(person.getId(), m.photoUrl(), m.profileUrl(), "© Banca Națională a României");
            }

            FtmEntity occ = dataset.newEntity("Occupancy", m.fullName(), "bnr-board");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", m.role());
            occ.addValue("sourceUrl", m.profileUrl());
            dataset.addEntity(occ);
        }

        return members.size();
    }

    static List<BnrMember> extractMembers(String html) {
        Map<String, BnrMember> members = new LinkedHashMap<>();
        Matcher matcher = CARD_PATTERN.matcher(html);
        while (matcher.find()) {
            String profileHref = matcher.group(1).trim();
            String rawName = cleanText(matcher.group(2));
            String rawRole = cleanText(matcher.group(3));
            String photoHref = matcher.group(4);

            String cleanName = cleanTitle(rawName);
            if (cleanName.isEmpty()) continue;

            String profileUrl = profileHref.isEmpty() ? BASE_URL + "/" + PAGE_SLUG : (profileHref.startsWith("http") ? profileHref : BASE_URL + profileHref);
            String photoUrl = (photoHref != null && !photoHref.isBlank()) ? (photoHref.startsWith("http") ? photoHref : BASE_URL + photoHref) : null;

            members.putIfAbsent(cleanName.toLowerCase(Locale.ROOT), new BnrMember(
                    cleanName,
                    rawName,
                    rawRole,
                    profileUrl,
                    photoUrl
            ));
        }
        return new ArrayList<>(members.values());
    }

    static String cleanTitle(String name) {
        if (name == null) return "";
        return name
                .replaceAll("(?i)^Academician\\s+", "")
                .replaceAll("(?i)^Prof\\.\\s*univ\\.\\s*dr\\.\\s*", "")
                .replaceAll("(?i)^Prof\\.\\s*dr\\.\\s*", "")
                .replaceAll("(?i),\\s*CFA$", "")
                .trim();
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
