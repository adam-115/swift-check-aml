package com.swiftcheck.aml.plugins.be.pep.nationalbank;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * Parser PEP Belgique – Banque nationale de Belgique ({@code nbb.be}).
 *
 * <p>Cible légale : Loi du 18 septembre 2017, art. 4, 28°, e) et Annexe IV –
 * « Gouverneur, membre du Comité de direction ou du Conseil de régence de la Banque nationale »
 * (catégorie {@code BE-e}).</p>
 *
 * <p>Télécharge et analyse les deux pages officielles des organes de la BNB :
 * le Comité de direction (Gouverneur, Vice-gouverneur et Directeurs) et le
 * Conseil de régence (Président du Conseil de régence et Régents).</p>
 */
@Extension
public class BePepNationalBankParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepNationalBankParser.class);

    static final String URL_DIRECTORS =
            "https://www.nbb.be/fr/la-banque-nationale/administration-et-controle/organes-de-la-banque/comite-de-direction";
    static final String URL_REGENTS =
            "https://www.nbb.be/fr/la-banque-nationale/administration-et-controle/organes-de-la-banque/conseil-de-regence";

    static final String FILE_DIRECTORS = "nbb-comite-direction.html";
    static final String FILE_REGENTS = "nbb-conseil-regence.html";

    static final String ID_PREFIX = "be-pep-national-bank";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";
    static final String BASE_URL = "https://www.nbb.be";
    static final String PHOTO_LICENCE = "© Banque nationale de Belgique";

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?:<img[^>]+src=\"([^\"]+)\"[^>]*>[\\s\\S]{0,400}?)?"
            + "<div class=\"manual-link-item-card__label\">([\\s\\S]*?)</div>\\s*"
            + "<div class=\"manual-link-item-card__description\">([\\s\\S]*?)</div>",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String dirUrl = p.getOrDefault("comiteDirectionUrl", URL_DIRECTORS).trim();
        if (dirUrl.isBlank() || dirUrl.contains("/propos-de-la-banque-nationale/")) {
            dirUrl = URL_DIRECTORS;
        }
        String regUrl = p.getOrDefault("conseilRegenceUrl", URL_REGENTS).trim();
        if (regUrl.isBlank() || regUrl.contains("/propos-de-la-banque-nationale/")) {
            regUrl = URL_REGENTS;
        }

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        downloadPage(client, dirUrl, ctx.rawDir().resolve(FILE_DIRECTORS));
        Thread.sleep(500L);
        downloadPage(client, regUrl, ctx.rawDir().resolve(FILE_REGENTS));

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<NbbMemberRecord> records = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(records.size() + " dirigeants de la Banque nationale de Belgique extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    private void downloadPage(HttpClient client, String url, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - nbb.be)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-BE,fr;q=0.9")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " sur " + url);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[be_pep_national_bank] Page téléchargée : {} ({} caractères)",
                target.getFileName(), response.body().length());
    }

    List<NbbMemberRecord> convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Map<String, NbbMemberRecord> bySlug = new LinkedHashMap<>();

        Path dirFile = rawDir.resolve(FILE_DIRECTORS);
        if (Files.isRegularFile(dirFile)) {
            parsePage(Files.readString(dirFile, StandardCharsets.UTF_8),
                    "Comité de direction", URL_DIRECTORS, bySlug);
        }

        Path regFile = rawDir.resolve(FILE_REGENTS);
        if (Files.isRegularFile(regFile)) {
            parsePage(Files.readString(regFile, StandardCharsets.UTF_8),
                    "Conseil de régence", URL_REGENTS, bySlug);
        }

        if (bySlug.isEmpty()) {
            throw new IllegalStateException("Aucun membre de la Banque nationale extrait depuis " + rawDir);
        }

        List<NbbMemberRecord> list = new ArrayList<>(bySlug.values());
        for (NbbMemberRecord rec : list) {
            writeRecord(rec, dataset);
        }
        return list;
    }

    static void parsePage(String html, String organ, String sourceUrl, Map<String, NbbMemberRecord> into) {
        if (html == null || html.isBlank()) return;
        Matcher m = CARD_PATTERN.matcher(html);
        while (m.find()) {
            String imgSrc = m.group(1);
            String fullName = cleanText(m.group(2));
            String role = cleanText(m.group(3));

            if (fullName.isEmpty() || role.isEmpty()) continue;

            String[] fl = splitFirstLast(fullName);
            String photoUrl = absolutize(imgSrc);
            String key = fullName.toLowerCase(Locale.ROOT);

            into.putIfAbsent(key, new NbbMemberRecord(
                    fullName,
                    fl[0],
                    fl[1],
                    fullName,
                    role,
                    organ,
                    sourceUrl,
                    photoUrl
            ));
        }
    }

    static void writeRecord(NbbMemberRecord rec, FtmDatasetBuilder dataset) {
        String fullPositionLabel = rec.role() + ", Banque nationale de Belgique";

        FtmEntity person = dataset.newEntity("Person", "nbb", rec.fullName());
        person.addValue("name", rec.fullName());
        person.addValue("firstName", rec.firstName());
        person.addValue("lastName", rec.lastName());
        person.addValue("country", COUNTRY);
        person.addValue("position", fullPositionLabel);
        person.addValue("notes", rec.role() + " – " + rec.organ() + " de la Banque nationale de Belgique");
        person.addValue("sourceUrl", rec.sourceUrl());
        dataset.addEntity(person);

        if (rec.photoUrl() != null && !rec.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), rec.photoUrl(), rec.sourceUrl(), PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", fullPositionLabel);
        position.addValue("name", fullPositionLabel);
        position.addValue("country", COUNTRY);
        position.addValue("organization", "Banque nationale de Belgique");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.financial");
        dataset.addEntity(position);

        FtmEntity occ = dataset.newEntity("Occupancy", rec.fullName(), fullPositionLabel);
        occ.addValue("holder", person.getId());
        occ.addValue("post", position.getId());
        occ.addValue("status", "current");
        occ.addValue("description", rec.role() + " (" + rec.organ() + ")");
        occ.addValue("sourceUrl", rec.sourceUrl());
        dataset.addEntity(occ);
    }

    static String[] splitFirstLast(String fullName) {
        String cleaned = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        int space = cleaned.indexOf(' ');
        if (space < 0) {
            return new String[]{"", cleaned};
        }
        return new String[]{cleaned.substring(0, space).trim(), cleaned.substring(space + 1).trim()};
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

    private static String absolutize(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) return null;
        String clean = pathOrUrl.replace("&amp;", "&").trim();
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return clean;
        }
        return clean.startsWith("/") ? BASE_URL + clean : BASE_URL + "/" + clean;
    }

    public record NbbMemberRecord(
            String slug,
            String firstName,
            String lastName,
            String fullName,
            String role,
            String organ,
            String sourceUrl,
            String photoUrl
    ) {}
}
