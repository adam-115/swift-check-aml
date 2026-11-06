package com.swiftcheck.aml.plugins.ropep.senate;

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
import java.util.Locale;
import java.util.Map;
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
 * <b>ro_pep_senate</b> – sénateurs du Sénat de Roumanie en fonction, personnes politiquement exposées au sens de la loi
 * roumaine 129/2019 sur la prévention du blanchiment de capitaux.
 *
 * <p>Source officielle : la page « Parlamentari » de {@code www.senat.ro/FisaSenatori.aspx} (une seule requête, 134 sénateurs).
 * Chaque fiche donne « NOM Prénom », la <b>date de naissance</b>, la circonscription et le groupe parlementaire ; l'identifiant
 * {@code ParlamentarID} du Sénat est stable. La page cite chaque sénateur deux fois : dédoublonnée par identifiant. Elle ne donne
 * pas de date de début de mandat ni les anciens sénateurs. La Chambre des députés (cdep.ro) n'est pas joignable. Aucun robots.txt
 * (404) ; un appel, User-Agent générique.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"url": "https://www.senat.ro/FisaSenatori.aspx"}
 * }</pre>
 */
@Extension
public class RoPepSenateParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ro-pep-senate";
    static final String TOPIC = "role.pep";
    static final String SEAT = "Sénateur de Roumanie (senator)";
    static final String FILE = "senators.html";
    static final String DEFAULT_URL = "https://www.senat.ro/FisaSenatori.aspx";
    static final String SITE = "https://www.senat.ro/";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Roumanie - Sénat)";
    private static final DateTimeFormatter RO_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Pattern CARD = Pattern.compile(
            "<div class=\"new-card-without-pics\">\\s*<a[^>]*href='(FisaSenator\\.aspx\\?ParlamentarID=([0-9a-fA-F-]+))'>([^<]+)</a>([\\s\\S]*?)</div>");
    private static final Pattern PARAGRAPH = Pattern.compile("<p>([^<]*)</p>");
    private static final Pattern UPPER = Pattern.compile("^[\\p{Lu}'’-]{2,}$");

    /** Un sénateur lu sur la page. */
    record Senator(String id, String name, String listedName, String birth, String district, String group, String url) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.get("url") == null || p.get("url").isBlank() ? DEFAULT_URL : p.get("url").trim();
        download(httpFetcher(), url, ctx.rawDir());
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

    static void download(Fetcher fetcher, String url, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Files.writeString(rawDir.resolve(FILE), fetcher.get(url), StandardCharsets.UTF_8);
    }

    /** Lit la page de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(FILE);
        if (!Files.exists(file)) {
            throw new IllegalStateException(FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Senator> senators = senators(Files.readString(file, StandardCharsets.UTF_8));
        FtmEntity position = dataset.newEntity("Position", "senat");
        position.addValue("name", SEAT);
        position.addValue("country", "ro");
        position.addValue("organization", "Senatul României");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        int withBirth = 0;
        for (Senator s : senators) {
            FtmEntity person = dataset.newEntity("Person", s.id());
            person.addValue("name", s.name());
            if (!s.name().equals(s.listedName())) {
                person.addValue("alias", s.listedName());
            }
            person.addValue("birthDate", s.birth());
            if (s.birth() != null) {
                withBirth++;
            }
            person.addValue("country", "ro");
            person.addValue("nationality", "ro");
            person.addValue("position", SEAT);
            person.addValue("sourceUrl", s.url());
            dataset.addEntity(person);

            FtmEntity occupancy = dataset.newEntity("Occupancy", s.id(), "senat");
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            List<String> parts = new ArrayList<>();
            if (s.group() != null) {
                parts.add(s.group());
            }
            if (s.district() != null) {
                parts.add(s.district());
            }
            occupancy.addValue("description", String.join(" ; ", parts));
            occupancy.addValue("sourceUrl", s.url());
            dataset.addEntity(occupancy);
        }
        return String.format("%d sénateurs, dont %d avec date de naissance. Source : Senatul României, www.senat.ro.", senators.size(), withBirth);
    }

    /** Les sénateurs de la page, sans doublon. */
    static List<Senator> senators(String html) {
        Map<String, Senator> out = new LinkedHashMap<>();
        Matcher m = CARD.matcher(html);
        while (m.find()) {
            String listed = text(m.group(3));
            String birth = null;
            String district = null;
            String group = null;
            Matcher p = PARAGRAPH.matcher(m.group(4));
            while (p.find()) {
                String line = text(p.group(1));
                if (line.startsWith("Data nasterii:")) {
                    birth = isoDate(line.substring("Data nasterii:".length()).trim());
                } else if (line.startsWith("Circumscrip")) {
                    district = line;
                } else if (line.startsWith("Grupul")) {
                    group = line;
                }
            }
            out.putIfAbsent(m.group(2), new Senator(m.group(2), reorder(listed), listed, birth, district, group, SITE + m.group(1)));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun sénateur lu : la page a changé.");
        }
        return new ArrayList<>(out.values());
    }

    /** « ABRUDEAN Mircea » → « Mircea Abrudean » ; « ALDEA Valentina-Mariana » → « Valentina-Mariana Aldea ». */
    static String reorder(String listed) {
        String[] tokens = listed.split(" ");
        int n = 0;
        while (n < tokens.length - 1 && UPPER.matcher(tokens[n]).matches()) {
            n++;
        }
        if (n == 0) {
            return listed;
        }
        StringBuilder surname = new StringBuilder();
        for (int i = 0; i < n; i++) {
            surname.append(i > 0 ? " " : "").append(titleCase(tokens[i]));
        }
        StringBuilder given = new StringBuilder();
        for (int i = n; i < tokens.length; i++) {
            given.append(given.length() > 0 ? " " : "").append(tokens[i]);
        }
        return given + " " + surname;
    }

    static String titleCase(String upper) {
        StringBuilder sb = new StringBuilder();
        for (String part : upper.split("-")) {
            if (sb.length() > 0) {
                sb.append('-');
            }
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    static String isoDate(String v) {
        try {
            return LocalDate.parse(v, RO_DATE).toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String text(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(3)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
