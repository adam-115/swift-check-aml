package com.swiftcheck.aml.plugins.hrpep.sabor;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>hr_pep_parliament</b> – représentants du Sabor (Parlement croate) de la législature en cours, personnes politiquement
 * exposées au sens de la loi croate sur la prévention du blanchiment de capitaux et du financement du terrorisme.
 *
 * <p>Source officielle : la liste « Zastupnici » de {@code www.sabor.hr/hr/zastupnici}, un tableau paginé (50 lignes par page)
 * donnant « Nom, Prénom », le groupe ou parti, la circonscription, le statut du mandat et la législature avec sa date de début
 * (« 11. saziv Hrvatskoga sabora (16.05.2024.) »). Le parser lit les pages {@code ?page=N} jusqu'à la première page sans ligne.
 * Le site ne publie ni naissance ni décès sur la liste ; les législatures antérieures (la 10e a pris fin le 16 mai 2024) sont hors
 * de la fenêtre de douze mois. Le robots.txt n'impose pas de délai : une page toutes les 1,5 seconde, User-Agent générique.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"listUrl": "https://www.sabor.hr/hr/zastupnici", "delayMs": "1500"}
 * }</pre>
 */
@Extension
public class HrPepSaborParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "hr-pep-parliament";
    static final String TOPIC = "role.pep";
    static final String SEAT = "Représentant au Sabor (zastupnik u Hrvatskom saboru)";
    static final String DEFAULT_LIST_URL = "https://www.sabor.hr/hr/zastupnici";
    static final String PAGE_PREFIX = "page-";
    static final int MAX_PAGES = 20;
    static final long DEFAULT_DELAY_MS = 1500;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Croatie - Sabor)";
    private static final DateTimeFormatter HR_DATE = DateTimeFormatter.ofPattern("d.M.yyyy");
    private static final Pattern ROW = Pattern.compile("<tr>\\s*<td[^>]*views-field-field-prezime[\\s\\S]*?</tr>");
    private static final Pattern NAME = Pattern.compile("<a href=\"([^\"]+)\">([^<]+)</a>");
    private static final Pattern CELL = Pattern.compile("<td[^>]*views-field-view-sabor-data-(\\d)[^>]*>\\s*(?:<a[^>]*>)?([^<]*)");
    private static final Pattern STATUS = Pattern.compile("views-field-field-status-mandata\">([^<]*)<");
    private static final Pattern SAZIV = Pattern.compile("views-field-field-saziv\">([^<]*)<");
    private static final Pattern SAZIV_DATE = Pattern.compile("\\((\\d{1,2}\\.\\d{1,2}\\.\\d{4})\\.?\\)");

    /** Un représentant lu sur la liste. */
    record Mp(String slug, String name, String listedName, String party, String district, String status, String saziv, LocalDate start) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(delayMs(p)), listUrl(p), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Télécharge les pages de la liste jusqu'à la première page sans ligne. */
    static void download(Fetcher fetcher, String listUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        for (int page = 0; page < MAX_PAGES; page++) {
            String html = fetcher.get(listUrl + (listUrl.contains("?") ? "&" : "?") + "page=" + page);
            if (mps(html).isEmpty()) {
                return;
            }
            Files.writeString(rawDir.resolve(PAGE_PREFIX + page + ".html"), html, StandardCharsets.UTF_8);
        }
    }

    /** Lit les pages de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        List<Path> pages;
        try (Stream<Path> listing = Files.list(rawDir)) {
            pages = listing.filter(f -> f.getFileName().toString().startsWith(PAGE_PREFIX)).sorted().toList();
        }
        Map<String, Mp> byKey = new LinkedHashMap<>();
        for (Path page : pages) {
            for (Mp mp : mps(Files.readString(page, StandardCharsets.UTF_8))) {
                byKey.putIfAbsent(mp.slug(), mp);
            }
        }
        if (byKey.isEmpty()) {
            throw new IllegalStateException("Aucun représentant lu dans " + rawDir + " : le téléchargement n'a pas eu lieu ou la page a changé.");
        }
        FtmEntity position = dataset.newEntity("Position", "sabor");
        position.addValue("name", SEAT);
        position.addValue("country", "hr");
        position.addValue("organization", "Hrvatski sabor");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        int inactive = 0;
        for (Mp mp : byKey.values()) {
            FtmEntity person = dataset.newEntity("Person", mp.slug());
            person.addValue("name", mp.name());
            if (!mp.name().equals(mp.listedName())) {
                person.addValue("alias", mp.listedName());
            }
            person.addValue("country", "hr");
            person.addValue("nationality", "hr");
            person.addValue("position", SEAT);
            person.addValue("sourceUrl", "https://www.sabor.hr" + mp.slug());
            dataset.addEntity(person);

            boolean active = "Aktivan".equals(mp.status());
            if (!active) {
                inactive++;
            }
            FtmEntity occupancy = dataset.newEntity("Occupancy", mp.slug(), "sabor");
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", mp.start() == null ? null : mp.start().toString());
            occupancy.addValue("status", active ? "current" : "ended");
            List<String> parts = new ArrayList<>();
            if (mp.saziv() != null) {
                parts.add(mp.saziv());
            }
            if (mp.party() != null) {
                parts.add("Groupe : " + mp.party());
            }
            if (mp.district() != null) {
                parts.add("Circonscription : " + mp.district());
            }
            if (mp.status() != null && !active) {
                parts.add("Statut du mandat : " + mp.status());
            }
            occupancy.addValue("description", String.join(" ; ", parts));
            occupancy.addValue("sourceUrl", "https://www.sabor.hr" + mp.slug());
            dataset.addEntity(occupancy);
        }
        return byKey.size() + " représentants du Sabor sur " + pages.size() + " page(s)"
                + (inactive == 0 ? "" : " (" + inactive + " au statut de mandat autre qu'actif)") + ". Source : Hrvatski sabor, www.sabor.hr.";
    }

    /** Les lignes du tableau d'une page. */
    static List<Mp> mps(String html) {
        List<Mp> out = new ArrayList<>();
        Matcher r = ROW.matcher(html);
        while (r.find()) {
            String row = r.group();
            Matcher n = NAME.matcher(row);
            if (!n.find()) {
                continue;
            }
            String listed = text(n.group(2));
            String name = listed;
            int comma = listed.indexOf(", ");
            if (comma > 0) {
                name = listed.substring(comma + 2) + " " + listed.substring(0, comma);
            }
            String party = null;
            String district = null;
            Matcher c = CELL.matcher(row);
            while (c.find()) {
                String value = text(c.group(2));
                if ("1".equals(c.group(1))) {
                    party = value.isEmpty() ? null : value;
                } else if ("2".equals(c.group(1))) {
                    district = value.isEmpty() ? null : value;
                }
            }
            Matcher s = STATUS.matcher(row);
            Matcher z = SAZIV.matcher(row);
            String saziv = z.find() ? text(z.group(1)) : null;
            Matcher d = saziv == null ? null : SAZIV_DATE.matcher(saziv);
            LocalDate start = null;
            if (d != null && d.find()) {
                try {
                    start = LocalDate.parse(d.group(1), HR_DATE);
                } catch (Exception e) {
                    start = null;
                }
            }
            out.add(new Mp(n.group(1), name, listed, party, district, s.find() ? text(s.group(1)) : null, saziv, start));
        }
        return out;
    }

    static String text(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String listUrl(Map<String, String> p) {
        String v = p.get("listUrl");
        return v == null || v.isBlank() ? DEFAULT_LIST_URL : v.trim();
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        if (v == null || v.isBlank()) {
            return DEFAULT_DELAY_MS;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « delayMs » invalide : « " + v + " » (nombre de millisecondes attendu).");
        }
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(delayMs);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
