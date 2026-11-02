package com.swiftcheck.aml.plugins.bepep.chamber;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
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
 * <b>be_pep_chamber</b> – membres de la Chambre des représentants (catégorie BE-b ; loi du 18 septembre 2017,
 * art. 4, 28°, b), Annexe IV, 2°).
 *
 * <p>Source : site de la Chambre (dekamer.be), qui ne publie pas d'open data. Type {@code PLUGIN_MANAGED} : le plugin
 * lit la liste des 150 députés en fonction, puis la fiche de chacun, et dépose les pages dans {@code rawDir}. La
 * liste écrit « Nom Prénom » (« Aerts Staf »), la fiche « Prénom Nom » et une ligne de CV :
 * <pre>
 * CV : Législature 56 (09.06.2024 - ....)
 * Député de la circonscription électorale d'Anvers | Groupe politique : Ecolo-Groen | Né à Duffel le 08/09/1981 | …
 * </pre>
 * Les pages sont en ISO-8859-1. Paramètres :
 * <pre>{@code
 * {"listUrl": "https://www.dekamer.be/kvvcr/showpage.cfm?section=/depute&language=fr&cfm=/site/wwwcfm/depute/cvlist54.cfm",
 *  "detailUrl": "https://www.dekamer.be/kvvcr/showpage.cfm?section=/depute&language=fr&cfm=cvview54.cfm?key={key}&lactivity=56",
 *  "delayMs": "300"}
 * }</pre>
 * L'adresse e-mail et les autres coordonnées des fiches ne sont pas reprises.
 */
@Extension
public class BePepChamberParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "be-pep-chamber";
    static final String TOPIC = "role.pep";

    static final String DEFAULT_LIST_URL = "https://www.dekamer.be/kvvcr/showpage.cfm?section=/depute&language=fr"
            + "&cfm=/site/wwwcfm/depute/cvlist54.cfm";
    static final String DEFAULT_DETAIL_URL = "https://www.dekamer.be/kvvcr/showpage.cfm?section=/depute&language=fr"
            + "&cfm=cvview54.cfm?key={key}&lactivity=56";
    static final String LIST_FILE = "chambre-liste.html";
    static final String DETAIL_PREFIX = "chambre-depute-";
    static final String POSITION = "Membre de la Chambre des représentants";
    static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    /** En dessous, la liste a changé de format : mieux vaut s'arrêter que publier une Chambre incomplète. */
    static final int MIN_MEMBERS = 100;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - Chambre des representants)";
    private static final Pattern MEMBER = Pattern.compile(
            "cvview54\\.cfm\\?key=([0-9A-Za-z]+)[^\"]*\"\\s*>\\s*<b>([^<]+)</b>", Pattern.CASE_INSENSITIVE);
    /** « Activité de Staf Aerts - Législature 56 » : le nom dans l'ordre Prénom Nom (le premier h2 est une recherche). */
    private static final Pattern TITLE = Pattern.compile("Activité de\\s+(.+?)\\s+-\\s+Législature");
    /** « Né à Duffel le 08/09/1981 », « Né le 16/05/1983 » (sans lieu) ou « Née à Vilvorde » (sans date). */
    private static final Pattern BIRTH = Pattern.compile("\\|\\s*N(ée|é)(?=\\s)([^|]*)\\|");
    private static final Pattern BIRTH_PLACE = Pattern.compile("^\\s*à\\s+(.+?)(?:\\s+le\\s+\\d|\\s*$)");
    private static final Pattern BIRTH_DATE = Pattern.compile("\\ble\\s+(\\d{2})/(\\d{2})/(\\d{4})");
    /** « circonscription électorale de Flandre orientale depuis le 23/10/2025 (suppléance de …) | ». */
    private static final Pattern CONSTITUENCY = Pattern.compile(
            "circonscription électorale (?:de |d'|du |des )(.+?)(?:\\s+depuis le (\\d{2})/(\\d{2})/(\\d{4})[^|]*)?\\s*\\|");
    private static final Pattern GROUP = Pattern.compile("Groupe politique\\s*:\\s*(.+?)\\s*\\|");
    private static final Pattern TERM_START = Pattern.compile("Législature\\s+\\d+\\s*\\((\\d{2})\\.(\\d{2})\\.(\\d{4})");

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    /** Un député : clé de la Chambre, nom tel que dans la liste (« Aerts Staf »). */
    record Listed(String key, String listName) {
    }

    /** Ce que la fiche apprend en plus. */
    record Detail(String fullName, String birthDate, String birthPlace, String gender, String constituency,
            String group, String termStart) {
    }

    record Report(int members, int withBirthDate, List<String> withoutDetail) {

        String message() {
            String m = String.format("%d membres de la Chambre, dont %d avec leur date de naissance.", members, withBirthDate);
            return withoutDetail.isEmpty() ? m
                    : m + String.format(" %d sans date de naissance (fiche absente ou sans date) : %s.", withoutDetail.size(),
                            String.join(", ", withoutDetail));
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(), param(p, "listUrl", DEFAULT_LIST_URL), param(p, "detailUrl", DEFAULT_DETAIL_URL),
                Long.parseLong(param(p, "delayMs", "300")), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque député, avec l'ID et le nom que Yente doit connaître (sans réseau). */
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

    /** Dépose la liste puis la fiche de chaque député dans {@code rawDir}. */
    static void download(Fetcher fetcher, String listUrl, String detailUrl, long delayMs, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        byte[] list = fetcher.get(listUrl);
        Files.write(rawDir.resolve(LIST_FILE), list);
        for (Listed m : members(new String(list, LATIN1))) {
            try {
                Files.write(rawDir.resolve(DETAIL_PREFIX + m.key() + ".html"), fetcher.get(detailUrl.replace("{key}", m.key())));
            } catch (IOException e) {
                // Fiche absente : le député est publié sans sa date de naissance, et cité dans le message
            }
            Thread.sleep(delayMs);
        }
    }

    /** Lit les pages de {@code rawDir} et écrit les députés. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Path listFile = rawDir.resolve(LIST_FILE);
        if (!Files.exists(listFile)) {
            throw new IllegalStateException(LIST_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Listed> members = members(Files.readString(listFile, LATIN1));
        if (members.size() < MIN_MEMBERS) {
            throw new IllegalStateException("Liste de la Chambre : " + members.size() + " députés lus, au moins "
                    + MIN_MEMBERS + " attendus. Le format de la page a probablement changé.");
        }
        int withBirth = 0;
        List<String> withoutDetail = new ArrayList<>();
        for (Listed m : members) {
            Path file = rawDir.resolve(DETAIL_PREFIX + m.key() + ".html");
            Detail d = Files.exists(file) ? detail(Files.readString(file, LATIN1)) : null;
            if (d == null || d.birthDate() == null) {
                withoutDetail.add(m.listName());
            } else {
                withBirth++;
            }
            write(m, d, dataset);
        }
        return new Report(members.size(), withBirth, withoutDetail);
    }

    static List<Listed> members(String html) {
        Map<String, Listed> byKey = new LinkedHashMap<>();
        Matcher m = MEMBER.matcher(html);
        while (m.find()) {
            byKey.putIfAbsent(m.group(1), new Listed(m.group(1), text(m.group(2))));
        }
        return new ArrayList<>(byKey.values());
    }

    static Detail detail(String html) {
        String text = text(html.replaceAll("<[^>]+>", " | "));
        Matcher title = TITLE.matcher(text);
        String fullName = title.find() ? text(title.group(1)) : null;
        Matcher birth = BIRTH.matcher(text);
        String birthDate = null;
        String birthPlace = null;
        String gender = null;
        if (birth.find()) {
            gender = "ée".equals(birth.group(1)) ? "female" : "male";
            Matcher place = BIRTH_PLACE.matcher(birth.group(2));
            birthPlace = place.find() ? place.group(1).trim() : null;
            Matcher date = BIRTH_DATE.matcher(birth.group(2));
            birthDate = date.find() ? date.group(3) + "-" + date.group(2) + "-" + date.group(1) : null;
        }
        // Début du mandat : « depuis le … » pour un suppléant entré en cours de législature, sinon le début de celle-ci
        Matcher constituency = CONSTITUENCY.matcher(text);
        String place = null;
        String since = null;
        if (constituency.find()) {
            place = constituency.group(1).trim();
            since = constituency.group(2) == null ? null
                    : constituency.group(4) + "-" + constituency.group(3) + "-" + constituency.group(2);
        }
        Matcher start = TERM_START.matcher(text);
        String termStart = start.find() ? start.group(3) + "-" + start.group(2) + "-" + start.group(1) : null;
        return new Detail(fullName, birthDate, birthPlace, gender, place, first(GROUP, text),
                since != null ? since : termStart);
    }

    /**
     * Prénom et nom : la liste écrit « Nom Prénom », la fiche « Prénom Nom ». On cherche la coupure de la liste qui,
     * inversée, redonne le nom de la fiche (« Van Peteghem Vincent » → nom « Van Peteghem », prénom « Vincent »).
     * Sans fiche, le premier mot est pris comme nom.
     */
    static String[] split(String listName, String fullName) {
        String[] words = listName.split("\\s+");
        for (int k = 1; k < words.length; k++) {
            String last = String.join(" ", java.util.Arrays.copyOfRange(words, 0, k));
            String first = String.join(" ", java.util.Arrays.copyOfRange(words, k, words.length));
            if (fullName != null && fullName.equalsIgnoreCase(first + " " + last)) {
                return new String[] { first, last };
            }
        }
        return words.length > 1
                ? new String[] { String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length)), words[0] }
                : new String[] { null, listName };
    }

    private static void write(Listed m, Detail d, FtmDatasetBuilder dataset) {
        String[] name = split(m.listName(), d == null ? null : d.fullName());
        String pageUrl = DEFAULT_DETAIL_URL.replace("{key}", m.key());

        // ID = clé du député à la Chambre (« 07757 »), stable d'une législature à l'autre
        FtmEntity person = dataset.newEntity("Person", m.key());
        person.addValue("name", joinNonBlank(" ", name[0], name[1]));
        person.addValue("firstName", name[0]);
        person.addValue("lastName", name[1]);
        person.addValue("country", "be");
        person.addValue("position", POSITION);
        person.addValue("sourceUrl", pageUrl);
        if (d != null) {
            person.addValue("birthDate", d.birthDate());
            person.addValue("birthPlace", d.birthPlace());
            person.addValue("gender", d.gender());
        }
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "member");
        position.addValue("name", POSITION);
        position.addValue("country", "be");
        position.addValue("organization", "Chambre des représentants");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.key(), d == null ? null : d.termStart());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        if (d != null) {
            occupancy.addValue("startDate", d.termStart());
            occupancy.addValue("constituency", d.constituency());
            occupancy.addValue("politicalGroup", d.group());
        }
        occupancy.addValue("sourceUrl", pageUrl);
        dataset.addEntity(occupancy);
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Texte sans entités HTML ni espaces superflus. */
    static String text(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#039;", "'").replace("&#39;", "'")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String param(Map<String, String> p, String key, String fallback) {
        String value = p.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                    .header("User-Agent", USER_AGENT).header("Accept", "text/html,*/*;q=0.8").GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
