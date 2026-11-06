package com.swiftcheck.aml.plugins.ltpep.seimas;

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
 * <b>lt_pep_parliament</b> – membres du Seimas (Parlement lituanien), personnes politiquement exposées au sens de la loi
 * lituanienne sur la prévention du blanchiment de capitaux et du financement du terrorisme.
 *
 * <p>Source officielle : le site du Seimas {@code www.lrs.lt}. La page « Seimo nariai » liste les membres de la législature en
 * cours (nom, groupe) ; la fiche de chaque membre donne sa date et son lieu de naissance. Le {@code robots.txt} impose
 * <b>20 secondes</b> entre deux requêtes et interdit {@code /pls/} : le parser attend 20 secondes (plancher imposé dans le code)
 * et ne lit que les pages {@code /sip/portal.show}. Avec les fiches, une exécution dure donc près d'une heure ; avec
 * {@code details=false}, une seule requête (nom et groupe, sans naissance).
 *
 * <p>Les autres sites officiels lituaniens (gouvernement, présidence, Banque de Lituanie, Cour suprême, Cour constitutionnelle,
 * Commission électorale) répondent HTTP 403 (contrôle Cloudflare) : non contournés. Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"listUrl": "https://www.lrs.lt/sip/portal.show?p_r=35299&p_k=1", "details": "true", "delayMs": "20000"}
 * }</pre>
 */
@Extension
public class LtPepSeimasParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "lt-pep-parliament";
    static final String TOPIC = "role.pep";
    static final String SEAT = "Membre du Seimas (Seimo narys)";
    static final String DEFAULT_LIST_URL = "https://www.lrs.lt/sip/portal.show?p_r=35299&p_k=1";
    static final String LIST_FILE = "list.html";
    static final long MIN_DELAY_MS = 20_000;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Lituanie - Seimas)";
    private static final Pattern MEMBER = Pattern.compile(
            "class=\"smn-name link\"\\s+href=\"([^\"]*p_asm_id=(\\d+))\"");
    private static final Pattern NAME = Pattern.compile("<a title=\"([^\"]+)\"\\s+class=\"smn-name link\"");
    private static final Pattern FACTION = Pattern.compile("<a title=\"([^\"]+)\"\\s+class=\"smn-frakcija link\"");
    private static final Pattern BIRTH = Pattern.compile("^(\\d{4}) m\\. ([a-ząčęėįšųūž]+) (\\d{1,2}) d\\.$");
    private static final List<String> MONTHS = List.of("sausio", "vasario", "kovo", "balandžio", "gegužės", "birželio", "liepos",
            "rugpjūčio", "rugsėjo", "spalio", "lapkričio", "gruodžio");

    /** Un membre listé : identifiant du Seimas, nom, groupe et adresse de sa fiche. */
    record Listed(String id, String name, String faction, String url) {

        String file() {
            return "member-" + id + ".html";
        }
    }

    /** Naissance lue sur la fiche. */
    record Birth(String date, String place) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(delayMs(p)), listUrl(p), details(p), ctx.rawDir());
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

    /** Télécharge la liste puis, si demandé, la fiche de chaque membre. */
    static void download(Fetcher fetcher, String listUrl, boolean details, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String list = fetcher.get(listUrl);
        Files.writeString(rawDir.resolve(LIST_FILE), list, StandardCharsets.UTF_8);
        if (details) {
            for (Listed l : listed(list)) {
                Files.writeString(rawDir.resolve(l.file()), fetcher.get(l.url()), StandardCharsets.UTF_8);
            }
        }
    }

    /** Lit les fichiers de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Path listFile = rawDir.resolve(LIST_FILE);
        if (!Files.exists(listFile)) {
            throw new IllegalStateException(LIST_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Listed> members = listed(Files.readString(listFile, StandardCharsets.UTF_8));
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre du Seimas lu dans " + rawDir + " : le format de la page a peut-être changé.");
        }
        FtmEntity position = dataset.newEntity("Position", "seimas");
        position.addValue("name", SEAT);
        position.addValue("country", "lt");
        position.addValue("organization", "Seimas");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        int withBirth = 0;
        for (Listed l : members) {
            Path detailFile = rawDir.resolve(l.file());
            Birth birth = Files.exists(detailFile) ? birth(Files.readString(detailFile, StandardCharsets.UTF_8)) : null;
            if (birth != null && birth.date() != null) {
                withBirth++;
            }
            FtmEntity person = dataset.newEntity("Person", l.id());
            person.addValue("name", l.name());
            person.addValue("birthDate", birth == null ? null : birth.date());
            person.addValue("birthPlace", birth == null ? null : birth.place());
            person.addValue("country", "lt");
            person.addValue("nationality", "lt");
            person.addValue("position", SEAT);
            person.addValue("sourceUrl", l.url());
            dataset.addEntity(person);

            FtmEntity occupancy = dataset.newEntity("Occupancy", l.id(), "seimas");
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", l.faction() == null ? null : "Groupe : " + l.faction());
            occupancy.addValue("sourceUrl", l.url());
            dataset.addEntity(occupancy);
        }
        return String.format("%d membres du Seimas, dont %d avec date de naissance. Source : Seimas, www.lrs.lt.", members.size(), withBirth);
    }

    /** Les membres listés sur la page « Seimo nariai » ; chaque membre est cité deux fois (photo et nom). */
    static List<Listed> listed(String html) {
        Map<String, Listed> out = new LinkedHashMap<>();
        for (String block : html.split("<div class=\"list-member")) {
            Matcher m = MEMBER.matcher(block);
            Matcher n = NAME.matcher(block);
            if (!m.find() || !n.find()) {
                continue;
            }
            Matcher f = FACTION.matcher(block);
            out.putIfAbsent(m.group(2), new Listed(m.group(2), text(n.group(1)),
                    f.find() ? text(f.group(1)) : null, m.group(1).replace("&amp;", "&")));
        }
        return new ArrayList<>(out.values());
    }

    /** Naissance : « Gimimo data | 1972 m. vasario 13 d. | Gimimo vieta | Terpeikiai, Kupiškio r., Lietuva ». */
    static Birth birth(String html) {
        List<String> lines = lines(html);
        String date = null;
        String place = null;
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (date == null && "Gimimo data".equals(lines.get(i))) {
                date = isoDate(lines.get(i + 1));
            }
            if (place == null && "Gimimo vieta".equals(lines.get(i))) {
                place = lines.get(i + 1);
            }
        }
        return date == null && place == null ? null : new Birth(date, place);
    }

    /** « 1972 m. vasario 13 d. » → 1972-02-13 ; {@code null} si illisible. */
    static String isoDate(String v) {
        Matcher m = BIRTH.matcher(v.trim());
        if (!m.matches()) {
            return null;
        }
        int month = MONTHS.indexOf(m.group(2)) + 1;
        if (month == 0) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), month, Integer.parseInt(m.group(3))).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Lignes de texte non vides de la page, sans balises ni entités. */
    static List<String> lines(String html) {
        List<String> out = new ArrayList<>();
        String body = html.replaceAll("(?s)<svg.*?</svg>", "").replaceAll("(?s)<script.*?</script>", "")
                .replaceAll("(?s)<style.*?</style>", "");
        for (String raw : body.replaceAll("<[^>]+>", "\n").split("\n")) {
            String line = text(raw);
            if (!line.isEmpty()) {
                out.add(line);
            }
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

    private static boolean details(Map<String, String> p) {
        String v = p.get("details");
        return v == null || v.isBlank() || Boolean.parseBoolean(v.trim());
    }

    /** Délai entre deux requêtes : jamais moins que les 20 secondes du robots.txt. */
    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        if (v == null || v.isBlank()) {
            return MIN_DELAY_MS;
        }
        try {
            return Math.max(MIN_DELAY_MS, Long.parseLong(v.trim()));
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
