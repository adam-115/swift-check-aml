package com.swiftcheck.aml.plugins.depep.president;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
 * <b>de_pep_president</b> – Président fédéral de la République fédérale d'Allemagne (Bundespräsident ;
 * catégorie DE-a ; GwG § 1 (12) a) ; JO C/2023/724).
 *
 * <p>Source : biographie officielle du Président sur bundespraesident.de.
 */
@Extension
public class DePepPresidentParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepPresidentParser.class);

    static final String ID_PREFIX = "de-pep-president";
    static final String TOPIC = "role.pep";
    /**
     * Page biographie, adresse stable d'un mandat à l'autre. L'ancienne adresse nominative
     * (…/Bundespraesident-Frank-Walter-Steinmeier/…_node.html) renvoie depuis novembre 2026 vers un formulaire en 403.
     */
    static final String DEFAULT_URL = "https://www.bundespraesident.de/DE/bundespraesident/biographie/biographie_node.html";
    /** Libellé d'origine de la fonction (Story 2 : pas de traduction). */
    private static final String POSITION = "Bundespräsident";

    private static final Pattern OG_TITLE = Pattern.compile("(?i)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)[\"']");
    private static final Pattern OG_URL = Pattern.compile("(?i)<meta[^>]+property=[\"']og:url[\"'][^>]+content=[\"']([^\"']*)[\"']");
    /** Titre de la page : ancien gabarit (c-intro-president) ou gabarit biographie (c-article). */
    private static final Pattern H1 = Pattern.compile(
            "(?si)<h1[^>]*class=\"[^\"]*(?:c-intro-president__headline|c-article__headline)[^\"]*\"[^>]*>(.*?)</h1>");
    private static final Pattern PHOTO = Pattern.compile(
            "(?si)<div[^>]*class=\"[^\"]*(?:c-intro-president__media|c-article__media)[^\"]*\"[^>]*>.*?<img[^>]+src=[\"']([^\"']+)[\"']");
    /** « Bundespräsident Frank-Walter Steinmeier » : la fonction devant le nom est retirée. */
    private static final Pattern FUNCTION_PREFIX = Pattern.compile("^(?:Bundespräsident(?:in)?|Der Bundespräsident|Die Bundespräsidentin)\\s+");
    /** Nom plausible : deux à quatre mots à majuscule (prénoms composés admis). */
    private static final Pattern PERSON_NAME = Pattern.compile("^[A-ZÄÖÜ][\\p{L}'-]+(?:\\s+(?:von|van|de|zu|[A-ZÄÖÜ][\\p{L}'-]+)){1,3}$");
    static final String PHOTO_LICENCE = "© Bundespräsidialamt";

    private static final Pattern BIRTH = Pattern.compile("(?u)geboren\\s+(?:am\\s+\\d{1,2}\\.\\s+\\p{L}+\\s+)?(\\d{4})\\s+in\\s+([\\p{L}' -]+?)(?=[,.;]| im Kreis)");
    private static final Pattern ELECTED = Pattern.compile("(?u)(\\d{4})\\s+zum\\s+(?:\\d+\\.)?\\s*Bundespräsidenten gewählt");

    record President(String name, String firstName, String lastName, String birthDate, String birthPlace,
            String startYear, String pageUrl, String photo) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String html = loadHtml(ctx);
        President p = read(html);
        convert(p, dataset);
        // Preuve (Stories 2 et 4) : URL, date d'extraction et SHA-256 de la page lue.
        String sha = sha256(html.getBytes(StandardCharsets.UTF_8));
        dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).forEach(e -> {
            e.addValue("notes", "pep_class: A1 (Bundespräsident)");
            e.addValue("notes", "level: FEDERAL");
            e.addValue("notes", "source: " + p.pageUrl() + " ; extracted_at: "
                    + java.time.LocalDate.now(java.time.ZoneOffset.UTC) + " ; sha256: " + sha);
        });
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage("Bundespräsident : " + p.name()
                + (p.startYear() != null ? " (gewählt " + p.startYear() + ")" : "") + ".");
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        President p = read(loadHtml(ctx));
        String id = new FtmDatasetBuilder(ID_PREFIX).buildEntityId(p.name());
        return List.of(new SourceRecord(id, "Person", p.name(), p.pageUrl()));
    }

    void convert(President p, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", p.name());
        person.addValue("name", p.name());
        person.addValue("firstName", p.firstName());
        person.addValue("lastName", p.lastName());
        if (p.birthDate() != null) {
            person.addValue("birthDate", p.birthDate());
        }
        if (p.birthPlace() != null) {
            person.addValue("birthPlace", p.birthPlace());
        }
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(person);
        if (p.photo() != null) {
            dataset.addMedia(person.getId(), p.photo(), p.pageUrl(), PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", "president");
        position.addValue("name", POSITION);
        position.addValue("country", "de");
        position.addValue("organization", "Bundespräsidialamt");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.headofstate");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", p.name(), "president", p.startYear() != null ? p.startYear() : "current");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        if (p.startYear() != null) {
            occupancy.addValue("startDate", p.startYear());
        }
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", p.pageUrl());
        dataset.addEntity(occupancy);
    }

    static President read(String html) {
        String name = cleanName(first(H1, html));
        if (name == null) {
            // og:title vaut « Biographie » sur la page biographie : il n'est pris que s'il ressemble à un nom.
            name = cleanName(first(OG_TITLE, html));
        }
        if (name == null) {
            throw new IllegalStateException("Nom du Président introuvable dans la page : format inattendu.");
        }
        String[] parts = name.split("\\s+", 2);
        String firstName = parts[0];
        String lastName = parts.length > 1 ? parts[1] : null;

        String birthDate = null;
        String birthPlace = null;
        Matcher mBirth = BIRTH.matcher(html);
        if (mBirth.find()) {
            birthDate = mBirth.group(1);
            birthPlace = Html.text(mBirth.group(2));
        }

        String startYear = null;
        Matcher mElected = ELECTED.matcher(html);
        if (mElected.find()) {
            startYear = mElected.group(1);
        }

        String pageUrl = first(OG_URL, html);
        if (pageUrl == null) {
            pageUrl = "https://www.bundespraesident.de";
        }

        String photo = first(PHOTO, html);
        if (photo != null && !photo.startsWith("http")) {
            photo = "https://www.bundespraesident.de" + (photo.startsWith("/") ? "" : "/") + photo;
        }

        return new President(name, firstName, lastName, birthDate, birthPlace, startYear, pageUrl, photo);
    }

    /** Retire la fonction placée devant le nom ; null si ce qui reste n'est pas un nom de personne. */
    static String cleanName(String raw) {
        if (raw == null) return null;
        String n = FUNCTION_PREFIX.matcher(raw.replaceAll("\\s+", " ").trim()).replaceFirst("").trim();
        return PERSON_NAME.matcher(n).matches() ? n : null;
    }

    private static String first(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? Html.text(m.group(1)) : null;
    }

    private static String loadHtml(ParserContext ctx) throws Exception {
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            return Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
        }
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.getOrDefault("url", DEFAULT_URL).trim();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Allemagne - Président fédéral)")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("SOURCE_BROKEN : HTTP {} pour {} ({})", resp.statusCode(), url, resp.uri());
            } else {
                if (ctx.rawDir() != null) {
                    Files.createDirectories(ctx.rawDir());
                    Files.writeString(ctx.rawDir().resolve("president.html"), resp.body(), StandardCharsets.UTF_8);
                }
                return resp.body();
            }
        } catch (Exception e) {
            log.warn("Échec du crawl du président fédéral ({}), vérification du fichier local.", e.getMessage());
        }
        if (ctx.inputFile() != null) {
            return Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("Aucune page HTML trouvée pour le Président fédéral.");
    }
}

