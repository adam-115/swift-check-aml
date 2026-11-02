package com.swiftcheck.aml.plugins.be.pep.councilofstate;

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
 * Parser PEP Belgique – Conseil d'État
 * ({@code https://www.raadvst-consetat.be/fr/le-conseil-detat/organisation/direction-et-gestion/}).
 *
 * <p>Cible légale : Loi du 18 septembre 2017, art. 4, 28°, d) et Annexe IV –
 * « Membre du Conseil d'État : président, conseiller, assesseur ou auditeur » (catégorie {@code BE-d}).</p>
 *
 * <p>Extrait les magistrats publiés sur les pages d'organisation du Conseil d'État
 * (Premier Président, Président, Auditeur général, Auditeur général adjoint, conseillers,
 * assesseurs, auditeurs) en excluant les fonctions purement administratives
 * (greffier en chef, administrateur, directeur d'encadrement).</p>
 */
@Extension
public class BePepCouncilOfStateParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepCouncilOfStateParser.class);

    static final String DEFAULT_URL = "https://www.raadvst-consetat.be/fr/le-conseil-detat/organisation/direction-et-gestion/";
    static final String FILE_NAME = "council-of-state-direction.html";

    static final String ID_PREFIX = "be-pep-council-of-state";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";
    static final String BASE_URL = "https://www.raadvst-consetat.be";
    static final String PHOTO_LICENCE = "© Conseil d'État de Belgique";

    private static final Pattern PERSON_CARD_PATTERN = Pattern.compile(
            "(?:<img[^>]+src=\"([^\"]+)\"[^>]*>[\\s\\S]{0,400}?)?"
            + "<h4[^>]*class=\"[^\"]*o-person__name[^\"]*\"[^>]*>([\\s\\S]*?)</h4>\\s*"
            + "<p[^>]*class=\"[^\"]*o-person__function[^\"]*\"[^>]*>([\\s\\S]*?)</p>",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path sourceFile = null;
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String existingHtml = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            if (!parseMembers(existingHtml).isEmpty()) {
                sourceFile = ctx.inputFile();
            } else {
                log.warn("[be_pep_council_of_state] Le fichier d'entrée {} ne contient aucun magistrat (ancienne URL ou redirection), téléchargement direct de {}",
                        ctx.inputFile(), DEFAULT_URL);
            }
        }
        if (sourceFile == null) {
            Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
            String url = p.getOrDefault("url", DEFAULT_URL).trim();
            if (url.isBlank() || url.contains("about_organisation_direction")) {
                url = DEFAULT_URL;
            }
            sourceFile = downloadDirect(url, ctx.rawDir().resolve(FILE_NAME));
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<MemberRecord> records = convert(sourceFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(records.size() + " hauts magistrats du Conseil d'État extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Path sourceFile = resolveSourceFile(ctx);
        if (parseMembers(Files.readString(sourceFile, StandardCharsets.UTF_8)).isEmpty()) {
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
        log.info("[be_pep_council_of_state] Page téléchargée : {} ({} caractères)", FILE_NAME, response.body().length());
        return target;
    }

    List<MemberRecord> convert(Path htmlFile, FtmDatasetBuilder dataset) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<MemberRecord> members = parseMembers(html);
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun magistrat du Conseil d'État extrait depuis " + htmlFile);
        }
        for (MemberRecord rec : members) {
            writeRecord(rec, dataset);
        }
        return members;
    }

    static List<MemberRecord> parseMembers(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Matcher m = PERSON_CARD_PATTERN.matcher(html);
        Map<String, MemberRecord> bySlug = new LinkedHashMap<>();

        while (m.find()) {
            String imgSrc = m.group(1);
            String rawName = cleanText(m.group(2));
            String function = cleanText(m.group(3));

            if (rawName.length() < 3 || "X".equalsIgnoreCase(rawName)) {
                continue;
            }
            if (!isEligibleJudicialFunction(function)) {
                continue;
            }

            String[] fl = splitNameWithUppercaseSurname(rawName);
            String firstName = fl[0];
            String lastName = fl[1];
            String displayName = (firstName.isEmpty() ? "" : firstName + " ") + lastName;
            String photoUrl = absolutize(imgSrc);

            bySlug.put(displayName.toLowerCase(Locale.ROOT), new MemberRecord(
                    displayName,
                    firstName,
                    lastName,
                    displayName.trim(),
                    rawName,
                    function,
                    photoUrl
            ));
        }
        return new ArrayList<>(bySlug.values());
    }

    static boolean isEligibleJudicialFunction(String function) {
        if (function == null || function.isBlank()) {
            return false;
        }
        String lower = function.toLowerCase(Locale.ROOT);
        if (lower.contains("greffier")
                || lower.contains("administrateur")
                || lower.contains("directeur")
                || lower.contains("personnel")
                || lower.contains("budget")) {
            return false;
        }
        return lower.contains("président")
                || lower.contains("president")
                || lower.contains("auditeur")
                || lower.contains("conseiller")
                || lower.contains("assesseur");
    }

    static void writeRecord(MemberRecord rec, FtmDatasetBuilder dataset) {
        String fullPositionLabel = rec.function() + " du Conseil d'État de Belgique";

        FtmEntity person = dataset.newEntity("Person", "member", rec.displayName());
        person.addValue("name", rec.displayName());
        person.addValue("firstName", rec.firstName());
        person.addValue("lastName", rec.lastName());
        person.addValue("country", COUNTRY);
        person.addValue("position", fullPositionLabel);
        person.addValue("notes", rec.function() + " – Conseil d'État de Belgique");
        person.addValue("sourceUrl", DEFAULT_URL);

        if (!rec.rawName().equals(rec.displayName())) {
            person.addValue("alias", rec.rawName());
        }
        dataset.addEntity(person);

        if (rec.photoUrl() != null && !rec.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), rec.photoUrl(), DEFAULT_URL, PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", fullPositionLabel);
        position.addValue("name", fullPositionLabel);
        position.addValue("country", COUNTRY);
        position.addValue("organization", "Conseil d'État de Belgique");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.judicial");
        dataset.addEntity(position);

        FtmEntity occ = dataset.newEntity("Occupancy", rec.displayName(), fullPositionLabel);
        occ.addValue("holder", person.getId());
        occ.addValue("post", position.getId());
        occ.addValue("status", "current");
        occ.addValue("description", fullPositionLabel);
        occ.addValue("sourceUrl", DEFAULT_URL);
        dataset.addEntity(occ);
    }

    /**
     * Sépare une chaîne comme {@code VAN VAERENBERGH Wilfried} ou {@code Wilfried VAN VAERENBERGH}
     * en {@code [firstName, lastName]}.
     */
    static String[] splitNameWithUppercaseSurname(String rawName) {
        String cleaned = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
        String[] tokens = cleaned.split(" ");
        if (tokens.length <= 1) {
            return new String[]{"", cleaned};
        }
        List<String> upperTokens = new ArrayList<>();
        List<String> mixedTokens = new ArrayList<>();
        for (String t : tokens) {
            String lettersOnly = t.replaceAll("[^\\p{L}]", "");
            if (lettersOnly.length() >= 2 && lettersOnly.equals(lettersOnly.toUpperCase(Locale.ROOT))) {
                upperTokens.add(t);
            } else {
                mixedTokens.add(t);
            }
        }
        if (!upperTokens.isEmpty() && !mixedTokens.isEmpty()) {
            return new String[]{String.join(" ", mixedTokens), String.join(" ", upperTokens)};
        }
        return new String[]{tokens[0], String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length))};
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

    private static final Pattern HEX_ENTITY_PATTERN = Pattern.compile("&#x([0-9a-fA-F]+);");
    private static final Pattern DEC_ENTITY_PATTERN = Pattern.compile("&#([0-9]+);");

    private static String cleanText(String s) {
        if (s == null) return "";
        String decoded = HEX_ENTITY_PATTERN.matcher(s.replaceAll("<[^>]+>", " "))
                .replaceAll(mr -> Character.toString(Integer.parseInt(mr.group(1), 16)));
        decoded = DEC_ENTITY_PATTERN.matcher(decoded)
                .replaceAll(mr -> Character.toString(Integer.parseInt(mr.group(1), 10)));
        return decoded
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
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

    public record MemberRecord(
            String slug,
            String firstName,
            String lastName,
            String displayName,
            String rawName,
            String function,
            String photoUrl
    ) {}
}
