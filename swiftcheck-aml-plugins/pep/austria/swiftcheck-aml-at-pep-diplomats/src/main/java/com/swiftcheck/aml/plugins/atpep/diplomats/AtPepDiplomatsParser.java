package com.swiftcheck.aml.plugins.atpep.diplomats;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 * <b>at_pep_diplomats</b> – Ambassadeurs, chargés d'affaires et représentants permanents de la République
 * d'Autriche à l'étranger (catégorie <b>AT-f</b> ; FM-GwG § 2 Z 6 lit. f ; liste notifiée, JO C/2023/724).
 *
 * <p>Source officielle : annuaire des représentations autrichiennes du ministère fédéral des Affaires européennes
 * et internationales (BMEIA, {@code https://www.bmeia.gv.at/botschaften-konsulate/suche-nach-oesterreichischen-vertretungen}).
 *
 * <p>Les consuls honoraires (Honorarkonsul / Honorargeneralkonsul) sont systématiquement exclus conformément
 * aux lignes directrices LBC/FT.
 */
@Extension
public class AtPepDiplomatsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(AtPepDiplomatsParser.class);

    static final String ID_PREFIX = "at-pep-diplomats";
    static final String TOPIC = "role.pep";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SwiftCheck-AML/1.0 (liste PEP Autriche - diplomates)";
    static final String SEARCH_URL = "https://www.bmeia.gv.at/botschaften-konsulate/suche-nach-oesterreichischen-vertretungen";

    private static final Pattern COUNTRY_OPTION = Pattern.compile("<option value=\"(\\d+)\">([^<]+)</option>");
    private static final Pattern LEITUNG_BLOCK = Pattern.compile(
            "(?s)Leitung:\\s*</div>\\s*<div class=\"col-lg-9\">\\s*([^<\\r\\n]+(?:\\r?\\n[^<\\r\\n]+)*?)\\s*</div>");

    /**
     * Ligne Leitung : NOM Prénom[, Titres] | Rôle
     * Ex : « PÖSTINGER Georg, Mag. | ao. und bev. Botschafter »
     *      « KAUDEL-JENSEN Barbara, MA | ao. und bev. Botschafterin »
     */
    private static final Pattern LEITUNG_LINE = Pattern.compile(
            "^([\\p{Lu}][\\p{Lu}'’-]+(?: [\\p{Lu}][\\p{Lu}'’-]+)*)\\s+([\\p{Lu}][\\p{L}'’-]+(?: [\\p{Lu}][\\p{L}'’-]+)*)(?:,\\s*[^|]+)?\\s*\\|\\s*(.*)$");

    public record Diplomat(String name, String firstName, String lastName, String role, String country, String sourceUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        long delay = parseLong(p.get("delayMs"), 150);

        List<Diplomat> diplomats = loadOrFetch(ctx.rawDir(), delay);
        if (diplomats.isEmpty()) {
            throw new IllegalStateException("Aucun ambassadeur extrait depuis le BMEIA.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(diplomats, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        String msg = String.format("%d ambassadeurs et chefs de mission diplomatique autrichiens publiés.", diplomats.size());
        return dataset.toConversionResult().withMessage(msg);
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        List<Diplomat> diplomats = loadFromDir(ctx.rawDir());
        List<SourceRecord> records = new ArrayList<>();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        for (Diplomat d : diplomats) {
            String id = dataset.buildEntityId(d.name() + " " + d.country());
            records.add(new SourceRecord(id, "Person", d.name(), d.sourceUrl()));
        }
        return records;
    }

    private List<Diplomat> loadOrFetch(Path rawDir, long delayMs) throws IOException {
        List<Diplomat> cached = loadFromDir(rawDir);
        if (!cached.isEmpty()) {
            log.info("Chargement de {} diplomates depuis le cache {}", cached.size(), rawDir);
            return cached;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        log.info("Interrogation de la page principale BMEIA...");
        String mainHtml = httpGet(client, SEARCH_URL);
        Files.writeString(rawDir.resolve("bmeia_main.html"), mainHtml, StandardCharsets.UTF_8);

        Map<String, String> countries = extractCountries(mainHtml);
        log.info("{} pays répertoriés sur le BMEIA", countries.size());

        Map<String, Diplomat> unique = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, String> entry : countries.entrySet()) {
            String countryId = entry.getKey();
            String countryName = entry.getValue();

            String queryUrl = SEARCH_URL + "?tx_bmeiadb_piresults%5Bwhich%5D=austrianEmbassy&tx_bmeiadb_piresults%5Bquery%5D=" + countryId;
            try {
                if (delayMs > 0 && count > 0) {
                    Thread.sleep(delayMs);
                }
                String html = httpGet(client, queryUrl);
                Files.writeString(rawDir.resolve("bmeia_country_" + countryId + ".html"), html, StandardCharsets.UTF_8);
                List<Diplomat> extracted = parsePage(html, countryName, queryUrl);
                for (Diplomat d : extracted) {
                    unique.putIfAbsent(d.name(), d);
                }
                count++;
            } catch (Exception e) {
                log.warn("Erreur téléchargement pays {} ({}) : {}", countryId, countryName, e.getMessage());
            }
        }

        return new ArrayList<>(unique.values());
    }

    static List<Diplomat> loadFromDir(Path rawDir) throws IOException {
        if (!Files.exists(rawDir)) {
            return List.of();
        }
        Map<String, Diplomat> unique = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(rawDir)) {
            List<Path> files = stream.filter(f -> f.getFileName().toString().startsWith("bmeia_country_")).toList();
            for (Path f : files) {
                String html = Files.readString(f, StandardCharsets.UTF_8);
                String countryName = f.getFileName().toString();
                List<Diplomat> list = parsePage(html, countryName, SEARCH_URL);
                for (Diplomat d : list) {
                    unique.putIfAbsent(d.name(), d);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    static Map<String, String> extractCountries(String html) {
        Map<String, String> countries = new LinkedHashMap<>();
        Matcher m = COUNTRY_OPTION.matcher(html);
        while (m.find()) {
            countries.put(m.group(1), m.group(2).trim());
        }
        return countries;
    }

    static List<Diplomat> parsePage(String html, String countryName, String sourceUrl) {
        List<Diplomat> list = new ArrayList<>();
        Matcher block = LEITUNG_BLOCK.matcher(html);
        while (block.find()) {
            String line = block.group(1).replaceAll("\\s+", " ").trim();
            if (line.isEmpty() || line.toLowerCase(Locale.ROOT).contains("honorarkonsul")) {
                continue;
            }
            Matcher m = LEITUNG_LINE.matcher(line);
            if (m.matches()) {
                String rawLast = m.group(1).trim();
                String rawFirst = m.group(2).trim();
                String role = m.group(3).trim();

                // Format du nom complet en casse standard
                String last = toProperCase(rawLast);
                String first = rawFirst;
                String fullName = first + " " + last;

                String mappedRole = mapRole(role, countryName);
                list.add(new Diplomat(fullName, first, last, mappedRole, countryName, sourceUrl));
            }
        }
        return list;
    }

    static String mapRole(String rawRole, String country) {
        String lower = rawRole.toLowerCase(Locale.ROOT);
        if (lower.contains("botschafter")) {
            return "Ambassadeur extraordinaire et plénipotentiaire d'Autriche (" + country + ")";
        } else if (lower.contains("geschäftsträger")) {
            return "Chargé d'affaires d'Autriche (" + country + ")";
        } else if (lower.contains("ständige") || lower.contains("vertreter")) {
            return "Représentant permanent d'Autriche (" + country + ")";
        } else if (lower.contains("generalkonsul")) {
            return "Consul général d'Autriche (" + country + ")";
        }
        return rawRole + " (" + country + ")";
    }

    static String toProperCase(String caps) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char c : caps.toCharArray()) {
            sb.append(up ? c : Character.toLowerCase(c));
            up = c == '-' || c == '\'' || c == '’' || c == ' ';
        }
        return sb.toString();
    }

    static void convert(List<Diplomat> diplomats, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", "austrian-ambassador");
        position.addValue("name", "Ambassadeur ou chargé d'affaires d'Autriche");
        position.addValue("country", "at");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "role.pep");
        dataset.addEntity(position);

        Set<String> seenIds = new HashSet<>();
        for (Diplomat d : diplomats) {
            String personKey = d.name();
            if (!seenIds.add(personKey)) {
                continue;
            }

            FtmEntity person = dataset.newEntity("Person", personKey);
            person.addValue("name", d.name());
            person.addValue("firstName", d.firstName());
            person.addValue("lastName", d.lastName());
            person.addValue("country", "at");
            person.addValue("nationality", "at");
            person.addValue("position", d.role());
            person.addValue("sourceUrl", d.sourceUrl());
            dataset.addEntity(person);

            FtmEntity occupancy = dataset.newEntity("Occupancy", personKey + "-ambassador");
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("description", d.role());
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);
        }
    }

    private static String httpGet(HttpClient client, String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                throw new IOException("HTTP " + resp.statusCode() + " sur " + url);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interruption lors du fetch de " + url, e);
        }
    }

    private static long parseLong(String s, long defaultValue) {
        if (s == null || s.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

