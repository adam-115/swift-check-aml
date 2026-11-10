package com.swiftcheck.aml.plugins.mtpep.parliament;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <b>mt_pep_parliament</b> – députés de la Chambre des représentants de Malte en fonction (15<sup>e</sup> législature, 2026-) et ceux de la
 * législature précédente quand elle s'est terminée depuis moins de 12 mois, plus le président de la Chambre, personnes politiquement
 * exposées au sens de la réglementation maltaise sur la prévention du blanchiment de capitaux (PMLFTR).
 *
 * <p>Source officielle : {@code parlament.mt} (pas de robots.txt ; 1 seconde entre les appels, User-Agent générique), trois pages :
 * <ul>
 *   <li>« Parliamentary Groups » de la législature en cours : les députés par groupe (« Nom, Prénom », avec la fonction éventuelle :
 *       Prime Minister, Government Whip, Opposition Leader…) ;</li>
 *   <li>la même page pour la législature précédente (« Fourteenth Legislature (2022 - 2026) ») ;</li>
 *   <li>la fiche du président de la Chambre (« Mr Speaker - Hon Carmelo Abela »).</li>
 * </ul>
 * L'identifiant d'un député est son adresse de fiche ({@code abela-fleur}), la même d'une législature à l'autre : une personne réélue
 * n'est écrite qu'une fois. Les fiches individuelles n'ont ni naissance ni date de fin ; elles ne sont pas lues. La date de fin de la
 * législature précédente n'est pas publiée sur la page de liste : seule son année est connue, la législature est gardée si cette année
 * est celle de la fenêtre de rétention ou une plus récente.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"currentUrl": "https://parlament.mt/en/15th-leg/political-groups/",
 *  "previousUrl": "https://parlament.mt/en/14th-leg/political-groups/",
 *  "speakerUrl": "https://parlament.mt/en/15th-leg/political-groups/speaker-abela-carmelo/",
 *  "monthsAfterEnd": "12"}
 * }</pre>
 */
@Extension
public class MtPepParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "mt-pep-parliament";
    static final String TOPIC = "role.pep";
    static final String SEAT = "Member of the House of Representatives (Membru tal-Parlament)";
    static final String SPEAKER_ROLE = "Speaker of the House of Representatives";
    static final String PM_ROLE = "Prime Minister of Malta";
    static final String DEFAULT_CURRENT_URL = "https://parlament.mt/en/15th-leg/political-groups/";
    static final String DEFAULT_PREVIOUS_URL = "https://parlament.mt/en/14th-leg/political-groups/";
    static final String DEFAULT_SPEAKER_URL = "https://parlament.mt/en/15th-leg/political-groups/speaker-abela-carmelo/";
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Malte - Chambre des représentants)";
    private static final long DELAY_MS = 1000;
    private static final Pattern TOKEN = Pattern.compile(
            "<h3>\\s*<a href=\"/en/\\d+th-leg/political-groups/[^\"]+/\">([^<]+)</a>\\s*</h3>"
                    + "|<a href=\"/en/\\d+th-leg/political-groups/([^/\"]+)/([^/\"]+)/\">([^<]+)</a>");
    private static final Pattern TITLE_YEARS = Pattern.compile("Legislature\\s*\\((\\d{4})\\s*-\\s*(\\d{4})?\\s*\\)");
    private static final Pattern SPEAKER = Pattern.compile("Mr Speaker\\s*-\\s*Hon\\.?\\s+([\\p{L}'’ -]+?)\\s*$");

    /** Un député lu dans une liste : identifiant de fiche, nom d'usage, nom publié, groupe et fonction éventuelle. */
    record Member(String slug, String name, String published, String group, String role) {
    }

    /** Une liste de législature : les députés et l'année de fin (0 = législature en cours). */
    record Legislature(List<Member> members, int startYear, int endYear) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        LocalDate today = LocalDate.now();
        download(httpFetcher(), param(p, "currentUrl", DEFAULT_CURRENT_URL), param(p, "previousUrl", DEFAULT_PREVIOUS_URL),
                param(p, "speakerUrl", DEFAULT_SPEAKER_URL), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), today, monthsAfterEnd(p), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    static void download(Fetcher fetcher, String currentUrl, String previousUrl, String speakerUrl, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Files.writeString(rawDir.resolve("current.html"), fetcher.get(currentUrl), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("previous.html"), fetcher.get(previousUrl), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("speaker.html"), fetcher.get(speakerUrl), StandardCharsets.UTF_8);
    }

    /** Lit les trois pages de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws IOException {
        for (String f : List.of("current.html", "previous.html", "speaker.html")) {
            if (!Files.exists(rawDir.resolve(f))) {
                throw new IllegalStateException(f + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
        }
        Legislature current = legislature(Files.readString(rawDir.resolve("current.html"), StandardCharsets.UTF_8));
        Legislature previous = legislature(Files.readString(rawDir.resolve("previous.html"), StandardCharsets.UTF_8));
        String speaker = speaker(Files.readString(rawDir.resolve("speaker.html"), StandardCharsets.UTF_8));
        int cutoffYear = today.minusMonths(monthsAfterEnd).getYear();
        boolean keepPrevious = previous.endYear() == 0 || previous.endYear() >= cutoffYear;

        FtmEntity seat = dataset.newEntity("Position", "house-of-representatives");
        seat.addValue("name", SEAT);
        seat.addValue("country", "mt");
        seat.addValue("organization", "Chambre des représentants de Malte (Parlament ta' Malta)");
        seat.addValue("topics", "gov.national");
        seat.addValue("topics", "gov.legislative");
        dataset.addEntity(seat);
        FtmEntity speakerPost = dataset.newEntity("Position", "speaker");
        speakerPost.addValue("name", SPEAKER_ROLE);
        speakerPost.addValue("country", "mt");
        speakerPost.addValue("organization", "Chambre des représentants de Malte (Parlament ta' Malta)");
        speakerPost.addValue("topics", "gov.national");
        speakerPost.addValue("topics", "gov.legislative");
        speakerPost.addValue("topics", "gov.head");
        dataset.addEntity(speakerPost);
        FtmEntity pmPost = dataset.newEntity("Position", "prime-minister");
        pmPost.addValue("name", PM_ROLE);
        pmPost.addValue("country", "mt");
        pmPost.addValue("organization", "Gouvernement de Malte");
        pmPost.addValue("topics", "gov.national");
        pmPost.addValue("topics", "gov.executive");
        pmPost.addValue("topics", "gov.head");
        dataset.addEntity(pmPost);

        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        int mandates = 0;
        List<Legislature> kept = keepPrevious ? List.of(current, previous) : List.of(current);
        for (int i = 0; i < kept.size(); i++) {
            boolean ended = kept.get(i) == previous;
            String label = ended ? "Quatorzième législature (" + previous.startYear() + "-" + previous.endYear() + ")"
                    : "Quinzième législature (" + current.startYear() + "-)";
            for (Member m : kept.get(i).members()) {
                FtmEntity person = person(dataset, persons, m.slug(), m.name(), aliases(m), SEAT, "https://parlament.mt/en/15th-leg/political-groups/");
                FtmEntity occupancy = dataset.newEntity("Occupancy", m.slug(), "house-of-representatives", ended ? "prev" : "cur");
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", seat.getId());
                occupancy.addValue("status", ended ? "ended" : "current");
                List<String> parts = new ArrayList<>(List.of(label, "Groupe : " + m.group()));
                if (m.role() != null) {
                    parts.add("Fonction : " + m.role());
                }
                occupancy.addValue("description", String.join(" ; ", parts));
                occupancy.addValue("sourceUrl", "https://parlament.mt/en/15th-leg/political-groups/");
                dataset.addEntity(occupancy);
                mandates++;
                if (!ended && "Prime Minister".equalsIgnoreCase(m.role())) {
                    FtmEntity pm = dataset.newEntity("Occupancy", m.slug(), "prime-minister");
                    pm.addValue("holder", person.getId());
                    pm.addValue("post", pmPost.getId());
                    pm.addValue("status", "current");
                    pm.addValue("sourceUrl", "https://parlament.mt/en/15th-leg/political-groups/");
                    dataset.addEntity(pm);
                }
            }
        }
        String speakerSlug = slug(speaker);        // même identifiant que sa fiche de député : une seule personne
        FtmEntity speakerPerson = person(dataset, persons, speakerSlug, speaker, aliasesOf(speaker), SPEAKER_ROLE, "https://parlament.mt/en/15th-leg/political-groups/");
        FtmEntity speakerOcc = dataset.newEntity("Occupancy", speakerSlug, "speaker");
        speakerOcc.addValue("holder", speakerPerson.getId());
        speakerOcc.addValue("post", speakerPost.getId());
        speakerOcc.addValue("startDate", "2026-06-20");
        speakerOcc.addValue("status", "current");
        speakerOcc.addValue("sourceUrl", "https://parlament.mt/en/15th-leg/political-groups/");
        dataset.addEntity(speakerOcc);

        return String.format("%d députés en fonction, %d de la législature précédente%s, le président de la Chambre : %d personnes gardées. "
                + "Source : Parlament ta' Malta, parlament.mt.", current.members().size(), keepPrevious ? previous.members().size() : 0,
                keepPrevious ? "" : " (écartée : terminée avant la fenêtre de " + monthsAfterEnd + " mois)", persons.size());
    }

    /** Une personne, créée une fois par identifiant (réélue : une seule fiche, plusieurs mandats). */
    private static FtmEntity person(FtmDatasetBuilder dataset, Map<String, FtmEntity> persons, String key, String name, Set<String> aliases,
            String position, String url) {
        FtmEntity person = persons.get(key);
        if (person == null) {
            person = dataset.newEntity("Person", key);
            person.addValue("name", name);
            for (String alias : aliases) {
                person.addValue("alias", alias);
            }
            person.addValue("country", "mt");
            person.addValue("nationality", "mt");
            person.addValue("position", position);
            person.addValue("sourceUrl", url);
            dataset.addEntity(person);
            persons.put(key, person);
        }
        return person;
    }

    /** Les députés d'une page « Parliamentary Groups » et les années de la législature. */
    static Legislature legislature(String html) {
        String clean = html.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<script.*?</script>", "");
        Matcher years = TITLE_YEARS.matcher(clean);
        if (!years.find()) {
            throw new IllegalStateException("Page « Parliamentary Groups » : législature introuvable dans le titre (la page a changé).");
        }
        int start = Integer.parseInt(years.group(1));
        int end = years.group(2) == null ? 0 : Integer.parseInt(years.group(2));
        List<Member> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String group = null;
        Matcher m = TOKEN.matcher(clean);
        while (m.find()) {
            if (m.group(1) != null) {
                group = m.group(1).trim();
                continue;
            }
            String slug = m.group(3);
            String text = m.group(4).replaceAll("\\s+", " ").trim();
            if (group == null || !seen.add(slug)) {
                continue;
            }
            String published = text;
            String role = null;
            int dash = text.indexOf(" - ");
            if (dash > 0) {
                published = text.substring(0, dash).trim();
                role = text.substring(dash + 3).trim();
            }
            out.add(new Member(slug, usualName(published), published, group, role));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page « Parliamentary Groups » : aucun député trouvé (la page a changé).");
        }
        return new Legislature(out, start, end);
    }

    /** « Mr Speaker - Hon Carmelo Abela » → « Carmelo Abela ». */
    static String speaker(String html) {
        String text = html.replaceAll("(?s)<script.*?</script>", " ").replaceAll("<[^>]+>", "\n");
        for (String line : text.split("\n")) {
            Matcher m = SPEAKER.matcher(line.replace("&nbsp;", " ").replaceAll("\\s+", " ").trim());
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        throw new IllegalStateException("Fiche du président de la Chambre : nom introuvable (la page a changé).");
    }

    /** « Abela, Fleur » → « Fleur Abela ». */
    static String usualName(String published) {
        int comma = published.indexOf(',');
        return comma < 0 ? published : published.substring(comma + 1).trim() + " " + published.substring(0, comma).trim();
    }

    private static Set<String> aliases(Member m) {
        Set<String> out = new LinkedHashSet<>();
        if (m.published().contains(",")) {
            out.add(m.published().replace(",", ""));        // « Abela Fleur »
            out.add(m.published());                        // « Abela, Fleur »
        }
        out.remove(m.name());
        return out;
    }

    private static Set<String> aliasesOf(String name) {
        String[] words = name.split(" ");
        Set<String> out = new LinkedHashSet<>();
        if (words.length >= 2) {
            out.add(words[words.length - 1] + " " + String.join(" ", java.util.Arrays.copyOfRange(words, 0, words.length - 1)));
        }
        return out;
    }

    private static String slug(String name) {
        String[] words = name.toLowerCase().split(" ");
        return words.length >= 2 ? words[words.length - 1] + "-" + String.join("-", java.util.Arrays.copyOfRange(words, 0, words.length - 1))
                : name.toLowerCase();
    }

    static int monthsAfterEnd(Map<String, String> p) {
        String v = p.get("monthsAfterEnd");
        if (v == null || v.isBlank()) {
            return DEFAULT_MONTHS_AFTER_END;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « monthsAfterEnd » invalide : « " + v + " » (nombre de mois attendu).");
        }
    }

    private static String param(Map<String, String> p, String name, String fallback) {
        String v = p.get(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(DELAY_MS);
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
