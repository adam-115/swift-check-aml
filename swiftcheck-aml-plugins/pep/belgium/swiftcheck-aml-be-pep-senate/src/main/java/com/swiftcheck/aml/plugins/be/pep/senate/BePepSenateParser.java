package com.swiftcheck.aml.plugins.be.pep.senate;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser PEP Belgique – Sénat de Belgique
 * ({@code https://www.senate.be/www/?MIval=/WieIsWie/LijstDerSenatoren&LANG=fr}).
 *
 * <p>Cible légale : Loi du 18 septembre 2017, art. 4, 28°, b) et Annexe IV –
 * « Sénateur, y compris coopté » (catégorie {@code BE-b}).</p>
 *
 * <p>Protection des données personnelles : la page source publie parfois des adresses
 * de correspondance ou privées, numéros de téléphone et adresses courriel. Conformément
 * aux règles du projet, ces données personnelles sont intégralement ignorées et purgées.</p>
 */
@Extension
public class BePepSenateParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepSenateParser.class);

    static final String ID_PREFIX = "be-pep-senate";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";
    static final String BASE_URL = "https://www.senate.be";
    static final String PHOTO_LICENCE = "© Sénat de Belgique";

    static final String POS_FEDERATED = "Sénateur des entités fédérées, Sénat de Belgique";
    static final String POS_COOPTED = "Sénateur coopté, Sénat de Belgique";
    static final String POS_PRESIDENT = "Président du Sénat de Belgique";

    private static final Set<String> SURNAME_PARTICLES = Set.of(
            "de", "van", "vander", "vande", "el", "le", "la", "du", "des", "den", "der", "ten", "ter", "t"
    );

    private static final Pattern SENATOR_BLOCK_PATTERN = Pattern.compile(
            "HREF=\"(/www/\\?MIval=showSenator&ID=([0-9]+)&LANG=fr)\"><B>([^<]+)</B></A>"
            + "([\\s\\S]*?)</TABLE>\\s*</TD>\\s*<TD><TABLE>([\\s\\S]*?)</TABLE>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern IMG_SRC_PATTERN = Pattern.compile(
            "<IMG[^>]+SRC=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TD_ROW_PATTERN = Pattern.compile(
            "<TR[^>]*>\\s*<TD[^>]*>([\\s\\S]*?)</TD>\\s*</TR>",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.getOrDefault("url", SenatePageDownloader.DEFAULT_URL);
        SenatePageDownloader.download(url, ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<SenatorRecord> records = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(records.size() + " sénateurs extraits sur senate.be.");
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

    List<SenatorRecord> convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Path file = resolveHtmlFile(rawDir);
        String html = Files.readString(file, StandardCharsets.UTF_8);
        List<SenatorRecord> senators = parseSenators(html);
        if (senators.isEmpty()) {
            throw new IllegalStateException("Aucun sénateur extrait depuis " + file);
        }
        for (SenatorRecord rec : senators) {
            writeRecord(rec, dataset);
        }
        return senators;
    }

    static List<SenatorRecord> parseSenators(String html) {
        String sanitized = SenatePageDownloader.stripPrivateCorrespondence(html);
        Matcher m = SENATOR_BLOCK_PATTERN.matcher(sanitized);
        Map<String, SenatorRecord> byId = new LinkedHashMap<>();

        while (m.find()) {
            String profilePath = decodeHtml(m.group(1)).trim();
            String senatorId = m.group(2).trim();
            String rawLastFirst = cleanText(m.group(3));
            Matcher imgM = IMG_SRC_PATTERN.matcher(m.group(4));
            String imgPath = imgM.find() ? decodeHtml(imgM.group(1)).trim() : "";
            String innerTable = m.group(5);

            if (rawLastFirst.isEmpty()) continue;

            boolean isPresident = false;
            boolean isCoopted = false;
            String originParliament = null;
            String party = null;
            String languageGroup = null;

            Matcher rowMatcher = TD_ROW_PATTERN.matcher(innerTable);
            while (rowMatcher.find()) {
                String rowHtml = rowMatcher.group(1);
                String rowText = cleanText(rowHtml);
                if (rowText.isEmpty()) continue;

                String lower = rowText.toLowerCase(Locale.ROOT);
                if (lower.startsWith("correspondance")
                        || lower.startsWith("priv")
                        || lower.startsWith("bureau")
                        || lower.startsWith("site web")
                        || lower.contains("courriel:")
                        || lower.contains("tel.:")) {
                    continue;
                }

                if (lower.matches("pr.{1,4}sidente?")) {
                    isPresident = true;
                    continue;
                }

                if (lower.contains("sénat") || lower.contains("senat") || lower.matches(".*s.{1,4}nat.*")) {
                    if (lower.contains("coopt")) {
                        isCoopted = true;
                    }
                    int parenStart = rowText.indexOf('(');
                    int parenEnd = rowText.indexOf(')', parenStart + 1);
                    if (parenStart >= 0 && parenEnd > parenStart) {
                        originParliament = rowText.substring(parenStart + 1, parenEnd).trim();
                    }
                    int dashIdx = rowText.lastIndexOf(" - ");
                    if (dashIdx >= 0) {
                        String tail = rowText.substring(dashIdx + 3).trim();
                        int commaIdx = tail.indexOf(',');
                        if (commaIdx >= 0) {
                            party = tail.substring(0, commaIdx).trim();
                            languageGroup = tail.substring(commaIdx + 1).trim();
                        } else {
                            party = tail;
                        }
                    }
                }
            }

            String[] parts = splitLastFirst(rawLastFirst);
            String lastName = parts[0];
            String firstName = parts[1];
            String displayName = firstName.isEmpty() ? lastName : (firstName + " " + lastName);
            String profileUrl = absolutize(profilePath);
            String photoUrl = absolutize(imgPath);

            SenatorRecord existing = byId.get(senatorId);
            if (existing != null && existing.president()) {
                isPresident = true;
            }
            byId.put(senatorId, new SenatorRecord(
                    senatorId,
                    firstName,
                    lastName,
                    displayName,
                    rawLastFirst,
                    isCoopted,
                    isPresident,
                    originParliament,
                    party,
                    languageGroup,
                    profileUrl,
                    photoUrl
            ));
        }

        return new ArrayList<>(byId.values());
    }

    static void writeRecord(SenatorRecord rec, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", "senator", rec.senatorId());
        person.addValue("name", rec.displayName());
        person.addValue("firstName", rec.firstName());
        person.addValue("lastName", rec.lastName());
        person.addValue("country", COUNTRY);
        person.addValue("sourceUrl", rec.profileUrl());

        if (!rec.rawLastFirst().equalsIgnoreCase(rec.displayName())) {
            person.addValue("alias", rec.rawLastFirst());
        }

        String posLabel = rec.coopted() ? POS_COOPTED : POS_FEDERATED;
        person.addValue("position", posLabel);
        if (rec.president()) {
            person.addValue("position", POS_PRESIDENT);
        }

        List<String> notes = new ArrayList<>();
        if (rec.originParliament() != null && !rec.originParliament().isBlank()) {
            notes.add("Désigné par : " + rec.originParliament());
        }
        if (rec.party() != null && !rec.party().isBlank()) {
            person.addValue("political", rec.party());
            notes.add("Groupe politique : " + rec.party());
        }
        if (rec.languageGroup() != null && !rec.languageGroup().isBlank()) {
            notes.add(rec.languageGroup());
        }
        if (!notes.isEmpty()) {
            person.addValue("notes", String.join(" | ", notes));
        }

        dataset.addEntity(person);

        if (rec.photoUrl() != null && !rec.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), rec.photoUrl(), rec.profileUrl(), PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", posLabel);
        position.addValue("name", posLabel);
        position.addValue("country", COUNTRY);
        position.addValue("organization", "Sénat de Belgique");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        FtmEntity occ = dataset.newEntity("Occupancy", rec.senatorId(), posLabel);
        occ.addValue("holder", person.getId());
        occ.addValue("post", position.getId());
        occ.addValue("status", "current");
        occ.addValue("sourceUrl", rec.profileUrl());
        if (!notes.isEmpty()) {
            occ.addValue("description", String.join(" – ", notes));
        }
        dataset.addEntity(occ);

        if (rec.president()) {
            FtmEntity presPos = dataset.newEntity("Position", POS_PRESIDENT);
            presPos.addValue("name", POS_PRESIDENT);
            presPos.addValue("country", COUNTRY);
            presPos.addValue("organization", "Sénat de Belgique");
            presPos.addValue("topics", "gov.national");
            presPos.addValue("topics", "gov.legislative");
            dataset.addEntity(presPos);

            FtmEntity presOcc = dataset.newEntity("Occupancy", rec.senatorId(), POS_PRESIDENT);
            presOcc.addValue("holder", person.getId());
            presOcc.addValue("post", presPos.getId());
            presOcc.addValue("status", "current");
            presOcc.addValue("description", POS_PRESIDENT);
            presOcc.addValue("sourceUrl", rec.profileUrl());
            dataset.addEntity(presOcc);
        }
    }

    static String[] splitLastFirst(String rawLastFirst) {
        String cleaned = rawLastFirst == null ? "" : rawLastFirst.trim().replaceAll("\\s+", " ");
        String[] tokens = cleaned.split(" ");
        if (tokens.length <= 1) {
            return new String[]{cleaned, ""};
        }
        if (tokens.length == 2) {
            return new String[]{tokens[0], tokens[1]};
        }
        String firstTokenLower = tokens[0].toLowerCase(Locale.ROOT).replace("'", "");
        if (SURNAME_PARTICLES.contains(firstTokenLower)) {
            String firstName = tokens[tokens.length - 1];
            String lastName = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, tokens.length - 1));
            return new String[]{lastName, firstName};
        }
        String lastName = tokens[0];
        String firstName = String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length));
        return new String[]{lastName, firstName};
    }

    private static Path resolveHtmlFile(Path rawDir) throws IOException {
        Path direct = rawDir.resolve(SenatePageDownloader.FILE_NAME);
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

    private static String cleanText(String htmlFragment) {
        if (htmlFragment == null) return "";
        String stripped = htmlFragment.replaceAll("<[^>]+>", " ");
        return decodeHtml(stripped).replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    static String decodeHtml(String s) {
        if (s == null) return "";
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&eacute;", "é")
                .replace("&egrave;", "è")
                .replace("&ecirc;", "ê")
                .replace("&euml;", "ë")
                .replace("&agrave;", "à")
                .replace("&acirc;", "â")
                .replace("&auml;", "ä")
                .replace("&ocirc;", "ô")
                .replace("&ouml;", "ö")
                .replace("&uuml;", "ü")
                .replace("&ucirc;", "û")
                .replace("&icirc;", "î")
                .replace("&iuml;", "ï")
                .replace("&ccedil;", "ç");
    }

    private static String absolutize(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) return "";
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl;
        }
        return pathOrUrl.startsWith("/") ? BASE_URL + pathOrUrl : BASE_URL + "/" + pathOrUrl;
    }

    public record SenatorRecord(
            String senatorId,
            String firstName,
            String lastName,
            String displayName,
            String rawLastFirst,
            boolean coopted,
            boolean president,
            String originParliament,
            String party,
            String languageGroup,
            String profileUrl,
            String photoUrl
    ) {}
}
