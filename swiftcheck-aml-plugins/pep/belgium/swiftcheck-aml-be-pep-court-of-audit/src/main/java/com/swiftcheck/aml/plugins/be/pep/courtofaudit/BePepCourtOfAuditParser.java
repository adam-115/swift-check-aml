package com.swiftcheck.aml.plugins.be.pep.courtofaudit;

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
 * Parser PEP Belgique – Cour des comptes
 * ({@code https://www.ccrek.be/fr/about-page/organisation}).
 *
 * <p>Cible légale : Loi du 18 septembre 2017, art. 4, 28°, e) et Annexe IV –
 * « Premier président, président ou conseiller à la Cour des comptes » (catégorie {@code BE-e}).</p>
 *
 * <p>Extrait les deux chambres (Chambre française et Chambre néerlandaise) en retenant
 * le Premier président / Première présidente, le Président / la Présidente et les
 * 8 conseillers, tout en excluant les greffiers.</p>
 */
@Extension
public class BePepCourtOfAuditParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepCourtOfAuditParser.class);

    static final String DEFAULT_URL = "https://www.ccrek.be/fr/about-page/organisation";
    static final String FILE_NAME = "court-of-audit-organisation.html";

    static final String ID_PREFIX = "be-pep-court-of-audit";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";

    private static final Pattern CHAMBER_BLOCK_PATTERN = Pattern.compile(
            "<h3>\\s*(Chambre[^<]+)</h3>([\\s\\S]*?)(?=<h3>\\s*Chambre|<div class=\"field field--name-field-body)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ROLE_SECTION_PATTERN = Pattern.compile(
            "<h4[^>]*class=\"[^\"]*field--label[^\"]*\"[^>]*>([^<]+)</h4>\\s*<ul[^>]*>([\\s\\S]*?)(?:</ul>|<ul>\\s*</div>)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LI_ITEM_PATTERN = Pattern.compile(
            "<li[^>]*>([^<]+)</li>",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path sourceFile = null;
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String existingHtml = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            if (!parseMagistrates(existingHtml).isEmpty()) {
                sourceFile = ctx.inputFile();
            } else {
                log.warn("[be_pep_court_of_audit] Le fichier d'entrée {} ne contient aucun magistrat (challenge anti-bot ou ancienne URL), téléchargement direct de {}",
                        ctx.inputFile(), DEFAULT_URL);
            }
        }
        if (sourceFile == null) {
            Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
            String url = p.getOrDefault("url", DEFAULT_URL).trim();
            if (url.isBlank() || url.contains("a-propos-de-nous")) {
                url = DEFAULT_URL;
            }
            sourceFile = downloadDirect(url, ctx.rawDir().resolve(FILE_NAME));
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<MagistrateRecord> records = convert(sourceFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(records.size() + " magistrats de la Cour des comptes extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Path sourceFile = resolveSourceFile(ctx);
        if (parseMagistrates(Files.readString(sourceFile, StandardCharsets.UTF_8)).isEmpty()) {
            Path fallback = ctx.rawDir().resolve(FILE_NAME);
            if (Files.isRegularFile(fallback)) {
                sourceFile = fallback;
            }
        }
        convert(sourceFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    private Path downloadDirect(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SwiftCheck-AML/1.0")
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " sur " + url);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[be_pep_court_of_audit] Page téléchargée : {} ({} caractères)", FILE_NAME, response.body().length());
        return target;
    }

    List<MagistrateRecord> convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<MagistrateRecord> magistrates = parseMagistrates(html);
        if (magistrates.isEmpty()) {
            throw new IllegalStateException("Aucun magistrat de la Cour des comptes extrait depuis " + htmlFile);
        }
        for (MagistrateRecord rec : magistrates) {
            writeRecord(rec, dataset);
        }
        return magistrates;
    }

    static List<MagistrateRecord> parseMagistrates(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Matcher chamberMatcher = CHAMBER_BLOCK_PATTERN.matcher(html);
        Map<String, MagistrateRecord> bySlug = new LinkedHashMap<>();

        while (chamberMatcher.find()) {
            String chamber = cleanText(chamberMatcher.group(1));
            String chamberContent = chamberMatcher.group(2);

            Matcher roleMatcher = ROLE_SECTION_PATTERN.matcher(chamberContent);
            while (roleMatcher.find()) {
                String sectionHeading = cleanText(roleMatcher.group(1));
                String listHtml = roleMatcher.group(2);

                String roleLabel = normalizeRoleLabel(sectionHeading);
                if (roleLabel == null) {
                    continue;
                }

                Matcher liMatcher = LI_ITEM_PATTERN.matcher(listHtml);
                while (liMatcher.find()) {
                    String rawEntry = cleanText(liMatcher.group(1));
                    String fullName = stripCivility(rawEntry);
                    if (fullName.isEmpty()) continue;

                    String[] fl = splitFirstLast(fullName);
                    bySlug.put(fullName.toLowerCase(Locale.ROOT), new MagistrateRecord(
                            fullName,
                            fl[0],
                            fl[1],
                            fullName,
                            roleLabel,
                            chamber
                    ));
                }
            }
        }
        return new ArrayList<>(bySlug.values());
    }

    private static String normalizeRoleLabel(String heading) {
        if (heading == null) return null;
        String lower = heading.toLowerCase(Locale.ROOT);
        if (lower.contains("greff")) {
            return null;
        }
        if (lower.contains("premi") && lower.contains("président")) {
            return "Premier président de la Cour des comptes";
        }
        if (lower.contains("président") || lower.contains("president")) {
            return "Président de la Cour des comptes";
        }
        if (lower.contains("conseiller")) {
            return "Conseiller à la Cour des comptes";
        }
        return null;
    }

    private static String stripCivility(String raw) {
        if (raw == null) return "";
        return raw.replaceFirst("(?i)^(?:M\\.|Mme|Mevr\\.|Dhr\\.|Mr\\.?)\\s+", "").trim();
    }

    static void writeRecord(MagistrateRecord rec, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", "magistrate", rec.fullName());
        person.addValue("name", rec.fullName());
        person.addValue("firstName", rec.firstName());
        person.addValue("lastName", rec.lastName());
        person.addValue("country", COUNTRY);
        person.addValue("position", rec.roleLabel());
        person.addValue("notes", rec.roleLabel() + " (" + rec.chamber() + ")");
        person.addValue("sourceUrl", DEFAULT_URL);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", rec.roleLabel());
        position.addValue("name", rec.roleLabel());
        position.addValue("country", COUNTRY);
        position.addValue("organization", "Cour des comptes de Belgique");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.financial");
        dataset.addEntity(position);

        FtmEntity occ = dataset.newEntity("Occupancy", rec.fullName(), rec.roleLabel());
        occ.addValue("holder", person.getId());
        occ.addValue("post", position.getId());
        occ.addValue("status", "current");
        occ.addValue("description", rec.roleLabel() + " – " + rec.chamber());
        occ.addValue("sourceUrl", DEFAULT_URL);
        dataset.addEntity(occ);
    }

    private static Path resolveSourceFile(ParserContext ctx) throws IOException {
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            return ctx.inputFile();
        }
        Path direct = ctx.rawDir().resolve(FILE_NAME);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        try (var stream = Files.list(ctx.rawDir())) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Aucun fichier HTML trouvé dans " + ctx.rawDir()));
        }
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

    public record MagistrateRecord(
            String slug,
            String firstName,
            String lastName,
            String fullName,
            String roleLabel,
            String chamber
    ) {}
}
