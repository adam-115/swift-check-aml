package com.swiftcheck.aml.plugins.be.pep.constitutionalcourt;

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
 * Parser PEP Belgique – Cour constitutionnelle
 * ({@code https://fr.const-court.be/court/presentation/organization}).
 *
 * <p>Cible légale : Loi du 18 septembre 2017, art. 4, 28°, d) et Annexe IV –
 * « Juge à la Cour constitutionnelle » (catégorie {@code BE-d}).</p>
 *
 * <p>Extrait exclusivement la section {@code #org-judges} (les 12 juges en fonction,
 * dont le président francophone et le président néerlandophone) en excluant les
 * référendaires, greffiers, personnel administratif et juges émérites.</p>
 */
@Extension
public class BePepConstitutionalCourtParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepConstitutionalCourtParser.class);

    static final String DEFAULT_URL = "https://fr.const-court.be/court/presentation/organization";
    static final String FILE_NAME = "const-court-organization.html";

    static final String ID_PREFIX = "be-pep-constitutional-court";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";

    static final String POS_JUDGE = "Juge à la Cour constitutionnelle de Belgique";
    static final String POS_PRESIDENT = "Président de la Cour constitutionnelle de Belgique";

    private static final Pattern MEMBER_CARD_PATTERN = Pattern.compile(
            "id=\"memberName-[^\"]+\"[^>]*>([^<]+)</p>[\\s\\S]*?id=\"memberJob-[^\"]+\"[^>]*>([^<]+)</p>",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path sourceFile = null;
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String existingHtml = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            if (!parseJudges(existingHtml).isEmpty()) {
                sourceFile = ctx.inputFile();
            } else {
                log.warn("[be_pep_constitutional_court] Le fichier d'entrée {} ne contient aucun juge, téléchargement direct de {}",
                        ctx.inputFile(), DEFAULT_URL);
            }
        }
        if (sourceFile == null) {
            Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
            String url = p.getOrDefault("url", DEFAULT_URL).trim();
            if (url.isBlank() || url.endsWith(".be/organization")) {
                url = DEFAULT_URL;
            }
            sourceFile = downloadDirect(url, ctx.rawDir().resolve(FILE_NAME));
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<JudgeRecord> judges = convert(sourceFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(judges.size() + " juges de la Cour constitutionnelle extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Path sourceFile = resolveSourceFile(ctx);
        if (parseJudges(Files.readString(sourceFile, StandardCharsets.UTF_8)).isEmpty()) {
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
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-BE,fr;q=0.9")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " sur " + url);
        }
        Files.writeString(target, response.body(), StandardCharsets.UTF_8);
        log.info("[be_pep_constitutional_court] Page téléchargée : {} ({} caractères)", FILE_NAME, response.body().length());
        return target;
    }

    List<JudgeRecord> convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<JudgeRecord> judges = parseJudges(html);
        if (judges.isEmpty()) {
            throw new IllegalStateException("Aucun juge de la Cour constitutionnelle extrait depuis " + htmlFile);
        }
        for (JudgeRecord rec : judges) {
            writeRecord(rec, dataset);
        }
        return judges;
    }

    static List<JudgeRecord> parseJudges(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        int start = html.indexOf("id=\"org-judges\"");
        int end = html.indexOf("id=\"org-referendars\"");
        String section = (start >= 0 && end > start)
                ? html.substring(start, end)
                : (start >= 0 ? html.substring(start) : html);

        Matcher m = MEMBER_CARD_PATTERN.matcher(section);
        Map<String, JudgeRecord> bySlug = new LinkedHashMap<>();

        while (m.find()) {
            String fullName = cleanText(m.group(1));
            String roleRaw = cleanText(m.group(2));
            if (fullName.isEmpty()) continue;

            String[] fl = splitFirstLast(fullName);
            boolean isPresident = roleRaw.toLowerCase(Locale.ROOT).contains("président")
                    || roleRaw.toLowerCase(Locale.ROOT).contains("president");

            String langGroup = null;
            if (roleRaw.contains("(FR)")) {
                langGroup = "Groupe linguistique français";
            } else if (roleRaw.contains("(NL)")) {
                langGroup = "Groupe linguistique néerlandais";
            }

            bySlug.put(fullName.toLowerCase(Locale.ROOT), new JudgeRecord(
                    fullName,
                    fl[0],
                    fl[1],
                    fullName,
                    roleRaw,
                    isPresident,
                    langGroup
            ));
        }

        return new ArrayList<>(bySlug.values());
    }

    static void writeRecord(JudgeRecord rec, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", "judge", rec.fullName());
        person.addValue("name", rec.fullName());
        person.addValue("firstName", rec.firstName());
        person.addValue("lastName", rec.lastName());
        person.addValue("country", COUNTRY);
        person.addValue("position", POS_JUDGE);
        person.addValue("sourceUrl", DEFAULT_URL);

        if (rec.president()) {
            person.addValue("position", POS_PRESIDENT);
        }
        if (rec.languageGroup() != null) {
            person.addValue("notes", rec.roleRaw() + " – " + rec.languageGroup());
        } else {
            person.addValue("notes", rec.roleRaw());
        }
        dataset.addEntity(person);

        FtmEntity judgePos = dataset.newEntity("Position", POS_JUDGE);
        judgePos.addValue("name", POS_JUDGE);
        judgePos.addValue("country", COUNTRY);
        judgePos.addValue("organization", "Cour constitutionnelle de Belgique");
        judgePos.addValue("topics", "gov.national");
        judgePos.addValue("topics", "gov.judicial");
        dataset.addEntity(judgePos);

        FtmEntity judgeOcc = dataset.newEntity("Occupancy", rec.fullName(), POS_JUDGE);
        judgeOcc.addValue("holder", person.getId());
        judgeOcc.addValue("post", judgePos.getId());
        judgeOcc.addValue("status", "current");
        judgeOcc.addValue("description", rec.roleRaw());
        judgeOcc.addValue("sourceUrl", DEFAULT_URL);
        dataset.addEntity(judgeOcc);

        if (rec.president()) {
            FtmEntity presPos = dataset.newEntity("Position", POS_PRESIDENT);
            presPos.addValue("name", POS_PRESIDENT);
            presPos.addValue("country", COUNTRY);
            presPos.addValue("organization", "Cour constitutionnelle de Belgique");
            presPos.addValue("topics", "gov.national");
            presPos.addValue("topics", "gov.judicial");
            dataset.addEntity(presPos);

            FtmEntity presOcc = dataset.newEntity("Occupancy", rec.fullName(), POS_PRESIDENT);
            presOcc.addValue("holder", person.getId());
            presOcc.addValue("post", presPos.getId());
            presOcc.addValue("status", "current");
            presOcc.addValue("description", rec.roleRaw());
            presOcc.addValue("sourceUrl", DEFAULT_URL);
            dataset.addEntity(presOcc);
        }
    }

    private static Path resolveSourceFile(ParserContext ctx) throws IOException {
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            return ctx.inputFile();
        }
        return resolveHtmlFile(ctx.rawDir());
    }

    static Path resolveHtmlFile(Path rawDir) throws IOException {
        Path direct = rawDir.resolve(FILE_NAME);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        try (var stream = Files.list(rawDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Aucun fichier HTML trouvé dans " + rawDir));
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

    public record JudgeRecord(
            String slug,
            String firstName,
            String lastName,
            String fullName,
            String roleRaw,
            boolean president,
            String languageGroup
    ) {}
}
