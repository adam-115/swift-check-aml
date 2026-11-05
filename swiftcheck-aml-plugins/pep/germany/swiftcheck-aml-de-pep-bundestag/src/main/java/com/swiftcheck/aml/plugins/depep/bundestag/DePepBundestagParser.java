package com.swiftcheck.aml.plugins.depep.bundestag;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import com.swiftcheck.aml.sdk.XmlElement;
import com.swiftcheck.aml.sdk.XmlRecordReader;

/**
 * <b>de_pep_bundestag</b> – Membres du Bundestag (Mitglieder des Deutschen Bundestages, MdB),
 * en fonction et sortis depuis moins de 12 mois (catégorie DE-b ; GwG § 1 (12) b) ; JO C/2023/724).
 *
 * <p>Source : open data du Bundestag (fichier XML {@code MdB-Stammdaten.zip} ou {@code MDB_STAMMDATEN.XML}).
 */
@Extension
public class DePepBundestagParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepBundestagParser.class);

    static final String ID_PREFIX = "de-pep-bundestag";
    static final String TOPIC = "role.pep";
    private static final String POSITION = "Membre du Bundestag (Mitglied des Deutschen Bundestages)";

    static final String DEFAULT_ZIP_URL = "https://www.bundestag.de/resource/blob/472878/MdB-Stammdaten.zip";
    static final String OPENDATA_PAGE = "https://www.bundestag.de/services/opendata";
    static final String DEFAULT_INDEX_URL = "https://www.bundestag.de/xml/v2/mdb/index.xml";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Bundestag)";
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    record Mandate(String wp, String startDate, String endDate, String group, String constituency) {
    }

    record Mdb(String id, String name, String firstName, String lastName, String birthDate, String birthPlace,
            String gender, String party, boolean current, String latestEnd, List<Mandate> mandates,
            String deathDate) {
    }

    record Report(int totalMdb, int current, int formerKept, int formerSkipped) {
        String message() {
            return String.format("%d fiches MdB analysées : %d députés en fonction, %d anciens députés dans la durée retenue (%d écartés : sortis avant ou décédés).",
                    totalMdb, current, formerKept, formerSkipped);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int months = monthsAfterEnd(p);
        LocalDate today = LocalDate.now();

        Path file = resolveInputFile(ctx, p);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);

        java.util.Set<String> currentIds = new java.util.HashSet<>();
        Report report = convert(file, today, months, dataset, currentIds);
        dataset.writeNdjsonFile(ctx.outputFile());
        String message = report.message() + freshness(ctx, p, today, currentIds);
        return dataset.toConversionResult().withMessage(message);
    }

    /** Adresse du zip lue dans la page open data : l'identifiant de version de l'adresse change à chaque publication. */
    static String discoverZipUrl(String html) {
        Matcher m = Pattern.compile("href=\"(/resource/blob/\\d+/MdB-Stammdaten\\.zip)\"").matcher(html);
        return m.find() ? "https://www.bundestag.de" + m.group(1) : null;
    }

    /** Date « Stand: jj.mm.aaaa » affichée sur la page open data à côté du lien du zip. */
    static LocalDate standDate(String html) {
        Matcher m = Pattern.compile("MdB-Stammdaten\\.zip.{0,400}?Stand:\\s*(\\d{2}\\.\\d{2}\\.\\d{4})", Pattern.DOTALL).matcher(html);
        return m.find() ? LocalDate.parse(m.group(1), GERMAN_DATE) : null;
    }

    /** Identifiants des députés « Aktiv » de l'index live {@code xml/v2/mdb/index.xml}. */
    static java.util.Set<String> activeIds(String indexXml) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        Matcher m = Pattern.compile("<mdbID status=\"Aktiv\">(\\d+)</mdbID>").matcher(indexXml);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /**
     * Compare le zip (mis à jour rarement) à l'index live : date du fichier et écarts de composition. Un écart signifie
     * que le zip ne décrit plus exactement le Bundestag actuel. Le résultat ne change pas les données, il est annoncé.
     */
    private String freshness(ParserContext ctx, Map<String, String> p, LocalDate today, java.util.Set<String> zipCurrent) {
        StringBuilder sb = new StringBuilder();
        try {
            Path page = ctx.rawDir() == null ? null : ctx.rawDir().resolve("opendata.html");
            if (page != null && Files.isRegularFile(page)) {
                LocalDate stand = standDate(Files.readString(page, StandardCharsets.UTF_8));
                if (stand != null) {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(stand, today);
                    sb.append(" Fichier du Bundestag arrêté au ").append(stand.format(GERMAN_DATE));
                    if (days > 60) {
                        sb.append(" (").append(days).append(" jours : les départs et arrivées depuis cette date sont à contrôler)");
                    }
                    sb.append('.');
                }
            }
            if (!"false".equalsIgnoreCase(p.get("checkIndex")) && ctx.rawDir() != null) {
                String index = httpGet(p.getOrDefault("indexUrl", DEFAULT_INDEX_URL).trim());
                Files.writeString(ctx.rawDir().resolve("mdb-index.xml"), index, StandardCharsets.UTF_8);
                java.util.Set<String> live = activeIds(index);
                java.util.Set<String> arrived = new java.util.HashSet<>(live);
                arrived.removeAll(zipCurrent);
                java.util.Set<String> left = new java.util.HashSet<>(zipCurrent);
                left.removeAll(live);
                sb.append(" Index live du Bundestag : ").append(live.size()).append(" députés actifs, ")
                        .append(arrived.size()).append(" absents du fichier, ")
                        .append(left.size()).append(" du fichier qui ne sont plus actifs.");
            }
        } catch (Exception e) {
            sb.append(" Contrôle de fraîcheur impossible : ").append(e.getMessage()).append('.');
        }
        return sb.toString();
    }

    private static String httpGet(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " pour " + url);
        }
        return resp.body();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int months = monthsAfterEnd(p);
        Path file = resolveInputFile(ctx, p);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);

        convert(file, LocalDate.now(), months, dataset);

        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    Report convert(Path file, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws Exception {
        return convert(file, today, monthsAfterEnd, dataset, new java.util.HashSet<>());
    }

    Report convert(Path file, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset,
                   java.util.Set<String> currentIds) throws Exception {
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        int[] counts = new int[4]; // total, current, kept, skipped

        try (InputStream in = openStream(file)) {
            XmlRecordReader.readEach(in, "MDB", mdbXml -> {
                counts[0]++;
                Mdb mdb = parseMdb(mdbXml);
                if (mdb == null) {
                    return;
                }
                if (mdb.current()) {
                    counts[1]++;
                    currentIds.add(mdb.id());
                    write(mdb, dataset);
                } else if (mdb.deathDate() != null) {
                    counts[3]++;                      // décédé : plus une personne à surveiller
                } else if (mdb.latestEnd() != null && !LocalDate.parse(mdb.latestEnd()).isBefore(cutoff)) {
                    counts[2]++;
                    write(mdb, dataset);
                } else {
                    counts[3]++;
                }
            });
        }
        return new Report(counts[0], counts[1], counts[2], counts[3]);
    }

    static Mdb parseMdb(XmlElement mdb) {
        String id = mdb.childText("ID");
        if (id == null || id.isBlank()) {
            return null;
        }

        XmlElement namen = mdb.child("NAMEN").child("NAME");
        String firstName = namen.childText("VORNAME");
        String lastName = namen.childText("NACHNAME");
        String adel = namen.childText("ADEL");
        String praefix = namen.childText("PRAEFIX");

        StringBuilder fullName = new StringBuilder();
        if (firstName != null) fullName.append(firstName).append(" ");
        if (praefix != null) fullName.append(praefix).append(" ");
        if (adel != null) fullName.append(adel).append(" ");
        if (lastName != null) fullName.append(lastName);
        String name = fullName.toString().trim();
        if (name.isBlank()) {
            name = "MdB " + id;
        }

        XmlElement bio = mdb.child("BIOGRAFISCHE_ANGABEN");
        String birthDate = parseDate(bio.childText("GEBURTSDATUM"));
        String birthPlace = bio.childText("GEBURTSORT");
        String geschlecht = bio.childText("GESCHLECHT");
        String gender = "weiblich".equalsIgnoreCase(geschlecht) ? "female"
                : "männlich".equalsIgnoreCase(geschlecht) ? "male" : null;
        String party = bio.childText("PARTEI_KURZ");

        List<Mandate> mandates = new ArrayList<>();
        boolean current = false;
        String latestEnd = null;

        for (XmlElement wp : mdb.child("WAHLPERIODEN").children("WAHLPERIODE")) {
            String wpNum = wp.childText("WP");
            String start = parseDate(wp.childText("MDBWP_VON"));
            String end = parseDate(wp.childText("MDBWP_BIS"));
            String constName = wp.childText("WKR_NAME");
            String constNum = wp.childText("WKR_NUMMER");
            String constituency = constName != null ? constName : constNum != null ? "Wahlkreis " + constNum : null;

            String group = null;
            for (XmlElement inst : wp.child("INSTITUTIONEN").children("INSTITUTION")) {
                if ("Fraktion/Gruppe".equalsIgnoreCase(inst.childText("INSART_LANG"))) {
                    group = inst.childText("INS_LANG");
                }
            }

            if (end == null || end.isBlank()) {
                current = true;
            } else {
                if (latestEnd == null || end.compareTo(latestEnd) > 0) {
                    latestEnd = end;
                }
            }
            mandates.add(new Mandate(wpNum, start, end, group, constituency));
        }

        return new Mdb(id, name, firstName, lastName, birthDate, birthPlace, gender, party, current, latestEnd, mandates,
                parseDate(bio.childText("STERBEDATUM")));
    }

    private static void write(Mdb mdb, FtmDatasetBuilder dataset) {
        String pageUrl = "https://www.bundestag.de/abgeordnete/biografien/" + mdb.id();

        FtmEntity person = dataset.newEntity("Person", mdb.id());
        person.addValue("name", mdb.name());
        person.addValue("firstName", mdb.firstName());
        person.addValue("lastName", mdb.lastName());
        if (mdb.birthDate() != null) person.addValue("birthDate", mdb.birthDate());
        if (mdb.birthPlace() != null) person.addValue("birthPlace", mdb.birthPlace());
        if (mdb.gender() != null) person.addValue("gender", mdb.gender());
        if (mdb.party() != null) person.addValue("political", mdb.party());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", pageUrl);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "member");
        position.addValue("name", POSITION);
        position.addValue("country", "de");
        position.addValue("organization", "Bundestag");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        for (Mandate m : mdb.mandates()) {
            String status = (m.endDate() == null && mdb.current()) ? "current" : "ended";
            FtmEntity occupancy = dataset.newEntity("Occupancy", mdb.id(), m.wp());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            if (m.startDate() != null) occupancy.addValue("startDate", m.startDate());
            if (m.endDate() != null) occupancy.addValue("endDate", m.endDate());
            occupancy.addValue("status", status);
            if (m.group() != null) occupancy.addValue("politicalGroup", m.group());
            if (m.wp() != null) occupancy.addValue("description", "Wahlperiode " + m.wp());
            occupancy.addValue("sourceUrl", pageUrl);
            dataset.addEntity(occupancy);
        }
    }

    static String parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        s = s.trim();
        try {
            LocalDate d = LocalDate.parse(s, GERMAN_DATE);
            return d.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream openStream(Path path) throws IOException {
        InputStream raw = Files.newInputStream(path);
        BufferedInputStream buf = new BufferedInputStream(raw);
        buf.mark(4);
        byte[] magic = new byte[4];
        int count = buf.read(magic);
        buf.reset();

        // Check if zip (PK\x03\x04)
        if (count == 4 && magic[0] == 0x50 && magic[1] == 0x4B && magic[2] == 0x03 && magic[3] == 0x04) {
            ZipInputStream zip = new ZipInputStream(buf);
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().toLowerCase().endsWith(".xml")) {
                    return zip;
                }
            }
            throw new IOException("Aucun fichier XML trouvé dans l'archive zip " + path);
        }
        return buf;
    }

    private Path resolveInputFile(ParserContext ctx, Map<String, String> p) throws Exception {
        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            return ctx.inputFile();
        }
        if (ctx.rawDir() == null) {
            throw new IllegalStateException("Aucun fichier d'entrée et rawDir est nul.");
        }
        Files.createDirectories(ctx.rawDir());
        Path localZip = ctx.rawDir().resolve("MdB-Stammdaten.zip");
        String url = p.getOrDefault("url", "").trim();
        if (url.isEmpty()) {
            try {
                String page = httpGet(p.getOrDefault("opendataUrl", OPENDATA_PAGE).trim());
                Files.writeString(ctx.rawDir().resolve("opendata.html"), page, StandardCharsets.UTF_8);
                url = discoverZipUrl(page);
            } catch (IOException e) {
                log.warn("Page open data illisible : {}", e.getMessage());
            }
            if (url == null || url.isEmpty()) {
                log.warn("Lien du zip introuvable sur la page open data, repli sur {}", DEFAULT_ZIP_URL);
                url = DEFAULT_ZIP_URL;
            }
        }
        log.info("Téléchargement de {} vers {}", url, localZip);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<Path> resp = client.send(request, HttpResponse.BodyHandlers.ofFile(localZip));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " lors du téléchargement de " + url);
        }
        return localZip;
    }

    private static int monthsAfterEnd(Map<String, String> p) {
        String v = p.get("monthsAfterEnd");
        if (v == null || v.isBlank()) {
            return DEFAULT_MONTHS_AFTER_END;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_MONTHS_AFTER_END;
        }
    }
}
