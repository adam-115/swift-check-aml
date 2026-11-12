package com.swiftcheck.aml.plugins.ropep.president;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>ro_pep_president</b> – Présidents de la Roumanie (Președintele României).
 *
 * <p>Couvre la catégorie <b>RO-a</b>, fonction <b>Président de la République</b> (fonction 733)
 * (Loi 129/2019, art. 3 alin. (2) ; JO C/2023/724).
 *
 * <p>Source dynamique : API MediaWiki Wikipédia (page <i>List_of_presidents_of_Romania</i>,
 * section Roumanie post-1989).
 */
@Extension
public class RoPepPresidentParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(RoPepPresidentParser.class);

    public static final String PARAM_URL = "url";
    public static final String DEFAULT_URL =
            "https://en.wikipedia.org/w/api.php?action=parse&page=List_of_presidents_of_Romania&prop=wikitext&section=3&format=json";

    static final String ID_PREFIX = "ro-pep-president";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "ro";
    static final String JSON_FILE = "ro-presidents.json";

    private static final Pattern WIKI_LINK = Pattern.compile("'''\\[\\[(?:[^|\\]]+\\|)?([^|\\]]+)\\]\\]'''");
    private static final Pattern BIRTH_YEAR = Pattern.compile("\\((?:born\\s+)?([12][0-9]{3})(?:[–-]([0-9]{4}))?\\)");
    private static final Pattern TERM_DATE = Pattern.compile("([0-9]{1,2}\\s+[A-Za-z]+\\s+[12][0-9]{3})");
    private static final DateTimeFormatter EN_DATE_FMT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);

        String url = ctx.parameters().getOrDefault(PARAM_URL, DEFAULT_URL);
        download(url, rawFile);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int count = convert(rawFile, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                String.format(Locale.ROOT, "%d chefs d'État de la Roumanie indexés.", count)
        );
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path rawFile = ctx.rawDir().resolve(JSON_FILE);
        if (!Files.exists(rawFile)) {
            String url = ctx.parameters().getOrDefault(PARAM_URL, DEFAULT_URL);
            download(url, rawFile);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(rawFile, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person",
                        entity.getValues("name").iterator().next(),
                        "https://en.wikipedia.org/wiki/List_of_presidents_of_Romania"));
            }
        }
        return records;
    }

    void download(String url, Path dest) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "SwiftCheck-AML/1.0 (pep-parser; contact@swiftcheck.com)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " lors du téléchargement de " + url);
        }
        log.info("Données des présidents roumains téléchargées depuis {} ({})", url, dest);
    }

    public int convert(Path rawFile, FtmDatasetBuilder dataset) throws IOException {
        JsonNode root = json.readTree(Files.readString(rawFile, StandardCharsets.UTF_8));
        String wikitext = root.path("parse").path("wikitext").path("*").asText("");
        if (wikitext.isEmpty()) {
            throw new IllegalStateException("Aucun wikitext trouvé dans : " + rawFile);
        }

        String[] rows = wikitext.split("\\|-");
        int count = 0;
        Set<String> seen = new HashSet<>();

        for (String row : rows) {
            Matcher mName = WIKI_LINK.matcher(row);
            if (!mName.find()) {
                continue;
            }
            String name = mName.group(1).trim();
            if (name.isEmpty() || seen.contains(name)) {
                continue;
            }
            seen.add(name);

            String birthYear = "";
            Matcher mBirth = BIRTH_YEAR.matcher(row);
            if (mBirth.find()) {
                birthYear = mBirth.group(1);
            }

            Matcher mDates = TERM_DATE.matcher(row);
            String tookOffice = mDates.find() ? parseIsoDate(mDates.group(1)) : "";
            String leftOffice = mDates.find() ? parseIsoDate(mDates.group(1)) : "";

            FtmEntity person = dataset.newEntity("Person", name);
            person.addValue("name", name);
            person.addValue("country", COUNTRY);
            person.addValue("nationality", COUNTRY);
            if (!birthYear.isEmpty()) {
                person.addValue("birthDate", birthYear);
            }
            person.addValue("position", "Președintele României");
            person.addValue("sourceUrl", "https://en.wikipedia.org/wiki/List_of_presidents_of_Romania");
            dataset.addEntity(person);

            String posSlug = "presedintele-romaniei";
            FtmEntity pos = dataset.newEntity("Position", posSlug);
            pos.addValue("name", "Președintele României");
            pos.addValue("country", COUNTRY);
            pos.addValue("organization", "Administrația Prezidențială");
            pos.addValue("sourceUrl", "https://en.wikipedia.org/wiki/List_of_presidents_of_Romania");
            dataset.addEntity(pos);

            FtmEntity occ = dataset.newEntity("Occupancy", "occ-" + person.getId());
            occ.addValue("holder", person.getId());
            occ.addValue("post", pos.getId());
            if (!tookOffice.isEmpty()) {
                occ.addValue("startDate", tookOffice);
            }
            if (!leftOffice.isEmpty()) {
                occ.addValue("endDate", leftOffice);
            }
            dataset.addEntity(occ);

            count++;
        }

        if (count == 0) {
            throw new IllegalStateException("Aucun président roumain extrait de " + rawFile);
        }
        return count;
    }

    private static String parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            LocalDate d = LocalDate.parse(raw.trim(), EN_DATE_FMT);
            return d.toString();
        } catch (DateTimeParseException e) {
            return "";
        }
    }
}
