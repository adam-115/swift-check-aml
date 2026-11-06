package com.swiftcheck.aml.plugins.fipep.courts;

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
 * <b>fi_pep_courts</b> – présidents et membres (oikeusneuvos) des deux cours suprêmes finlandaises : la Cour suprême
 * (Korkein oikeus) et la Cour administrative suprême (Korkein hallinto-oikeus), membres de cours suprêmes au sens de la loi
 * finlandaise sur la prévention du blanchiment.
 *
 * <p>Sources officielles (pages « Presidentti ja jäsenet », mêmes gabarits) :
 * {@code https://www.korkeinoikeus.fi/tietoa-meista/henkilosto/presidentti-ja-jasenet/} et
 * {@code https://www.kho.fi/tietoa-meista/henkilosto/presidentti-ja-jasenet/}. Chaque fiche donne le nom puis « Syntynyt AAAA »
 * ou « s. AAAA » (année de naissance). Une mention « (vv.) » signale un membre en congé ; il est repris avec une note.
 * Les pages sont téléchargées à chaque exécution. Type {@code PLUGIN_MANAGED}. Paramètres (facultatifs) :
 * <pre>{@code
 * {"delayMs": "1500", "courts": "kko,kho"}
 * }</pre>
 */
@Extension
public class FiPepCourtsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fi-pep-courts";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Finlande - hautes cours)";
    private static final Pattern BIRTH = Pattern.compile("^(?:Syntynyt|s\\.) (\\d{4})\\b.*");
    private static final Pattern NAME = Pattern.compile("^\\p{Lu}[\\p{L}'’.-]*(?: (?:\\p{Lu}[\\p{L}'’.-]*|von|van|de|af|la|le|der|den)){1,4}(?: \\(vv\\.\\))?$");

    /** Une cour. */
    record Court(String key, String name, String label, String url, String president, String member) {
    }

    static final List<Court> COURTS = List.of(
            new Court("kko", "Korkein oikeus", "Cour suprême de Finlande", "https://www.korkeinoikeus.fi/tietoa-meista/henkilosto/presidentti-ja-jasenet/",
                    "Président de la Cour suprême (Korkein oikeus)", "Juge de la Cour suprême (oikeusneuvos)"),
            new Court("kho", "Korkein hallinto-oikeus", "Cour administrative suprême de Finlande", "https://www.kho.fi/tietoa-meista/henkilosto/presidentti-ja-jasenet/",
                    "Président de la Cour administrative suprême (Korkein hallinto-oikeus)", "Juge de la Cour administrative suprême (oikeusneuvos)"));

    /** Un juge lu sur une page. */
    record Judge(String name, String birthYear, boolean president, boolean onLeave) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(delayMs(p)), selected(p), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), selected(p), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque juge, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), selected(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    static void download(Fetcher fetcher, List<Court> courts, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        for (Court c : courts) {
            Files.writeString(rawDir.resolve(c.key() + ".html"), fetcher.get(c.url()), StandardCharsets.UTF_8);
        }
    }

    /** Lit les pages de {@code rawDir} et écrit les juges ; renvoie le message du bilan. */
    String convert(Path rawDir, List<Court> courts, FtmDatasetBuilder dataset) throws IOException {
        int persons = 0;
        List<String> failures = new ArrayList<>();
        for (Court c : courts) {
            Path file = rawDir.resolve(c.key() + ".html");
            if (!Files.exists(file)) {
                throw new IllegalStateException(file.getFileName() + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
            List<Judge> judges;
            try {
                judges = read(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IllegalStateException e) {
                failures.add(c.name() + " (" + e.getMessage() + ")");
                continue;
            }
            FtmEntity presidentPost = post(dataset, c, c.president());
            FtmEntity memberPost = post(dataset, c, c.member());
            for (Judge j : judges) {
                FtmEntity person = dataset.newEntity("Person", c.key(), j.name());
                person.addValue("name", j.name());
                person.addValue("firstName", j.name().substring(0, j.name().indexOf(' ')));
                person.addValue("lastName", j.name().substring(j.name().indexOf(' ') + 1));
                person.addValue("birthDate", j.birthYear());
                person.addValue("country", "fi");
                person.addValue("position", j.president() ? c.president() : c.member());
                person.addValue("sourceUrl", c.url());
                dataset.addEntity(person);

                FtmEntity occupancy = dataset.newEntity("Occupancy", c.key(), j.name(), "fonction");
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", (j.president() ? presidentPost : memberPost).getId());
                occupancy.addValue("status", "current");
                occupancy.addValue("description", j.onLeave() ? "En congé (vv.) à la date de lecture de la page" : "En fonction");
                occupancy.addValue("sourceUrl", c.url());
                dataset.addEntity(occupancy);
                persons++;
            }
        }
        if (persons == 0) {
            throw new IllegalStateException("Aucun juge lu : " + String.join(" ; ", failures));
        }
        String message = persons + " juges lus sur " + (courts.size() - failures.size()) + " cour(s)";
        return failures.isEmpty() ? message + "." : message + ". Cours non lues : " + String.join(" ; ", failures) + ".";
    }

    private static FtmEntity post(FtmDatasetBuilder dataset, Court c, String name) {
        FtmEntity position = dataset.newEntity("Position", c.key(), name);
        position.addValue("name", name);
        position.addValue("country", "fi");
        position.addValue("organization", c.name());
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.judicial");
        dataset.addEntity(position);
        return position;
    }

    /** Les juges d'une page : président sous « Presidentti », membres sous « Jäsenet » ou « Oikeusneuvokset eli jäsenet ». */
    static List<Judge> read(String html) {
        String body = html.contains("<main") ? html.substring(html.indexOf("<main")) : html;
        String text = decode(body.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<(br|/p|/div|/li|/h\\d)[^>]*>", "\n").replaceAll("<[^>]+>", " "));
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replaceAll("[\\u200B-\\u200F\\u2060\\uFEFF\\u00AD]", "").replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        List<Judge> out = new ArrayList<>();
        boolean president = false;
        boolean seen = false;
        boolean inList = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals("Yhteystiedot") && seen) {
                break;
            }
            if (line.equals("Presidentti")) {
                inList = true;
                president = true;
                continue;
            }
            if (line.equals("Jäsenet") || line.equals("Oikeusneuvokset eli jäsenet")) {
                president = false;
                continue;
            }
            Matcher birth = i + 1 < lines.size() ? BIRTH.matcher(lines.get(i + 1)) : null;
            Matcher birth2 = i + 2 < lines.size() ? BIRTH.matcher(lines.get(i + 2)) : null;
            Matcher found = birth != null && birth.matches() ? birth : birth2 != null && birth2.matches() ? birth2 : null;
            boolean afterBio = i > 0 && (lines.get(i - 1).length() > 60 || lines.get(i - 1).equals("Jäsenet")
                    || lines.get(i - 1).equals("Oikeusneuvokset eli jäsenet") || lines.get(i - 1).equals("Presidentti"));
            if (inList && (found != null || afterBio) && NAME.matcher(line).matches() && !line.endsWith(".")
                    && !BIRTH.matcher(line).matches()) {
                boolean leave = line.endsWith("(vv.)");
                String name = line.replace("(vv.)", "").trim();
                out.add(new Judge(name, found == null ? null : found.group(1), president && out.stream().noneMatch(Judge::president), leave));
                seen = true;
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Aucun juge reconnu : la mise en page de la page a peut-être changé.");
        }
        return out;
    }

    private static String decode(String s) {
        Matcher m = Pattern.compile("&#x([0-9A-Fa-f]+);|&#(\\d+);").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp = m.group(1) != null ? Integer.parseInt(m.group(1), 16) : Integer.parseInt(m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&nbsp;", " ").replace("&amp;", "&").replace("&auml;", "ä").replace("&ouml;", "ö")
                .replace("&aring;", "å").replace("&Auml;", "Ä").replace("&Ouml;", "Ö");
    }

    static List<Court> selected(Map<String, String> p) {
        String keys = p.get("courts");
        if (keys == null || keys.isBlank()) {
            return COURTS;
        }
        Map<String, Court> byKey = new LinkedHashMap<>();
        COURTS.forEach(c -> byKey.put(c.key(), c));
        List<Court> out = new ArrayList<>();
        for (String k : keys.split(",")) {
            Court c = byKey.get(k.trim());
            if (c == null) {
                throw new IllegalArgumentException("Cour inconnue dans « courts » : « " + k.trim() + " ». Valeurs possibles : " + byKey.keySet() + ".");
            }
            out.add(c);
        }
        return out;
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 1500 : Long.parseLong(v.trim());
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
