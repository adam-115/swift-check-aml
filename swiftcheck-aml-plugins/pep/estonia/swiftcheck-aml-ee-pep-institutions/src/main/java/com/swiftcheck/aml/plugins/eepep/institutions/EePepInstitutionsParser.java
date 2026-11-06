package com.swiftcheck.aml.plugins.eepep.institutions;

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
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
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
 * <b>ee_pep_institutions</b> – président de la République, juges de la Cour suprême, Auditeur général et organes de la Banque
 * d'Estonie, personnes politiquement exposées au sens de la loi estonienne sur la prévention du blanchiment de capitaux.
 *
 * <p>Cinq pages officielles (clé du paramètre {@code keys}) :
 * <ul>
 *   <li>{@code president} : {@code president.ee/en/head-state/biography} (nom, naissance, prise de fonction) ;</li>
 *   <li>{@code supreme-court} : {@code riigikohus.ee/.../organisation/justices} (président et juges par chambre ; naissance
 *       du président seulement) ;</li>
 *   <li>{@code auditor-general} : {@code riigikontroll.ee/en/about-us/general/auditor-general} (nom, naissance, fonction) ;</li>
 *   <li>{@code eesti-pank-executive} : directoire (gouverneur et sous-gouverneurs, mois de prise de fonction) ;</li>
 *   <li>{@code eesti-pank-supervisory} : conseil de surveillance (président, membres du Riigikogu et experts).</li>
 * </ul>
 * Le Chancelier de justice n'est pas lu : le poste est vacant depuis l'élection de sa titulaire à la présidence (la page ne
 * donne que l'intérim). Aucun des cinq sites n'impose de délai ; le parser attend 2 secondes entre deux pages, avec un User-Agent
 * générique. Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"keys": "president,supreme-court,auditor-general,eesti-pank-executive,eesti-pank-supervisory", "delayMs": "2000"}
 * }</pre>
 */
@Extension
public class EePepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ee-pep-institutions";
    static final String TOPIC = "role.pep";
    static final long DEFAULT_DELAY_MS = 2000;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Estonie - institutions)";
    private static final String NAME = "\\p{Lu}[\\p{L}'’.-]*(?: \\p{Lu}[\\p{L}'’.-]*)+";
    private static final Pattern BORN = Pattern.compile("born (?:on )?(\\d{1,2} [A-Za-z]+ \\d{4})(?: in ([^.,]+))?");
    private static final Pattern NAME_LINE = Pattern.compile("^" + NAME + "$");

    /** Une page source : clé, fichier brut et adresse. */
    record Source(String key, String file, String url) {
    }

    static final List<Source> SOURCES = List.of(
            new Source("president", "president.html", "https://president.ee/en/head-state/biography"),
            new Source("supreme-court", "supreme-court.html",
                    "https://www.riigikohus.ee/en/about-supreme-court/organisation/justices"),
            new Source("auditor-general", "auditor-general.html",
                    "https://www.riigikontroll.ee/en/about-us/general/auditor-general"),
            new Source("eesti-pank-executive", "eesti-pank-executive.html",
                    "https://www.eestipank.ee/en/about-us/executive-board-eesti-pank"),
            new Source("eesti-pank-supervisory", "eesti-pank-supervisory.html",
                    "https://www.eestipank.ee/en/about-us/supervisory-board-eesti-pank"));

    /** Un titulaire lu sur une page : {@code start} et {@code birth} sont des dates ISO, éventuellement partielles. */
    record Holder(String name, String role, String organization, String start, String birth, String birthPlace,
            String topic, String note, String url) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<Source> selected = selected(p);
        download(httpFetcher(delayMs(p)), selected, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), selected, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
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

    /** Télécharge chaque page demandée. */
    static void download(Fetcher fetcher, List<Source> sources, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        for (Source s : sources) {
            Files.writeString(rawDir.resolve(s.file()), fetcher.get(s.url()), StandardCharsets.UTF_8);
        }
    }

    /** Lit les pages de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, List<Source> sources, FtmDatasetBuilder dataset) throws IOException {
        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        int persons = 0;
        for (Source s : sources) {
            Path file = rawDir.resolve(s.file());
            if (!Files.exists(file)) {
                throw new IllegalStateException(s.file() + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
            String html = Files.readString(file, StandardCharsets.UTF_8);
            for (Holder h : holders(s, html)) {
                FtmEntity person = dataset.newEntity("Person", h.name(), h.birth() == null ? h.organization() : h.birth());
                person.addValue("name", h.name());
                person.addValue("birthDate", h.birth());
                person.addValue("birthPlace", h.birthPlace());
                person.addValue("country", "ee");
                person.addValue("nationality", "ee");
                person.addValue("position", h.role());
                person.addValue("sourceUrl", h.url());
                dataset.addEntity(person);

                FtmEntity position = positions.get(h.role() + "|" + h.organization());
                if (position == null) {
                    position = dataset.newEntity("Position", h.organization(), h.role());
                    position.addValue("name", h.role());
                    position.addValue("country", "ee");
                    position.addValue("organization", h.organization());
                    position.addValue("topics", "gov.national");
                    position.addValue("topics", h.topic());
                    dataset.addEntity(position);
                    positions.put(h.role() + "|" + h.organization(), position);
                }
                FtmEntity occupancy = dataset.newEntity("Occupancy", h.name(), h.birth() == null ? h.organization() : h.birth(),
                        h.organization(), h.role());
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", position.getId());
                occupancy.addValue("startDate", h.start());
                occupancy.addValue("status", "current");
                occupancy.addValue("description", h.note());
                occupancy.addValue("sourceUrl", h.url());
                dataset.addEntity(occupancy);
                persons++;
            }
        }
        return persons + " personnes lues sur " + sources.size() + " source(s). Sources : président de la République, Cour suprême, "
                + "Riigikontroll et Eesti Pank (sites officiels).";
    }

    /** Les titulaires d'une page ; échoue si la page n'a plus la forme attendue. */
    static List<Holder> holders(Source s, String html) {
        return switch (s.key()) {
            case "president" -> president(html, s.url());
            case "supreme-court" -> supremeCourt(html, s.url());
            case "auditor-general" -> auditorGeneral(html, s.url());
            case "eesti-pank-executive" -> eestiPankExecutive(html, s.url());
            case "eesti-pank-supervisory" -> eestiPankSupervisory(html, s.url());
            default -> throw new IllegalArgumentException("Source inconnue : " + s.key());
        };
    }

    static List<Holder> president(String html, String url) {
        Matcher m = null;
        Matcher since = null;
        for (String line : lines(html)) {
            Matcher b = Pattern.compile("^President (" + NAME + ") was born (?:on )?(\\d{1,2} [A-Za-z]+ \\d{4})(?: in ([^.,]+))?").matcher(line);
            if (m == null && b.find()) {
                m = b;
            }
            Matcher s = Pattern.compile("President of the Republic of Estonia since (\\d{1,2} [A-Za-z]+ \\d{4})").matcher(line);
            if (since == null && s.find()) {
                since = s;
            }
        }
        if (m == null || since == null) {
            throw new IllegalStateException("Page du président : nom, naissance ou prise de fonction introuvables (la page a changé).");
        }
        return List.of(new Holder(m.group(1), "Président de la République (Vabariigi President)", "Vabariigi President",
                iso(since.group(1)), iso(m.group(2)), place(m.group(3)), "gov.head", null, url));
    }

    static List<Holder> auditorGeneral(String html, String url) {
        // Une expression par ligne de texte, ancrée au début : le titre de la page et le fil d'Ariane contiennent aussi
        // « Auditor General » et fausseraient une recherche sur le texte entier.
        Matcher name = null;
        Matcher born = null;
        Matcher since = null;
        for (String line : lines(html)) {
            Matcher n = Pattern.compile("^Auditor General (" + NAME + ")$").matcher(line);
            if (name == null && n.matches()) {
                name = n;
            }
            Matcher b = Pattern.compile("^(" + NAME + "), born (?:on )?(\\d{1,2} [A-Za-z]+ \\d{4})(?: in ([^.,]+))?").matcher(line);
            if (born == null && b.find()) {
                born = b;
            }
            Matcher s = Pattern.compile("^" + NAME + " has been Auditor General since (\\d{1,2} [A-Za-z]+ \\d{4})").matcher(line);
            if (since == null && s.find()) {
                since = s;
            }
        }
        if (name == null || born == null || since == null) {
            throw new IllegalStateException("Page de l'Auditeur général : nom, naissance ou prise de fonction introuvables (la page a changé).");
        }
        return List.of(new Holder(name.group(1), "Auditeur général (Riigikontrolör)", "Riigikontroll (Cour des comptes)",
                iso(since.group(1)), iso(born.group(2)), place(born.group(3)), "gov.financial",
                "En fonction depuis le premier mandat ; mandat en cours renouvelé", url));
    }

    static List<Holder> supremeCourt(String html, String url) {
        List<Holder> out = new ArrayList<>();
        Matcher section = Pattern.compile("judges-list__title\">([^<]+)<").matcher(html);
        List<int[]> titles = new ArrayList<>();
        List<String> titleNames = new ArrayList<>();
        while (section.find()) {
            titles.add(new int[] {section.start()});
            titleNames.add(clean(section.group(1)));
        }
        String[] cards = html.split("<article class=\"judge-card\">");
        int offset = cards[0].length();
        for (int i = 1; i < cards.length; i++) {
            int position = offset + "<article class=\"judge-card\">".length();
            offset = position + cards[i].length();
            String card = cards[i].contains("</article>") ? cards[i].substring(0, cards[i].indexOf("</article>")) : cards[i];
            Matcher name = Pattern.compile("judge-card__name\">([^<]+)<").matcher(card);
            Matcher title = Pattern.compile("judge-card__title\">([^<]+)<").matcher(card);
            if (!name.find() || !title.find()) {
                continue;
            }
            String chamber = null;
            for (int t = 0; t < titles.size(); t++) {
                if (titles.get(t)[0] < position) {
                    chamber = titleNames.get(t);
                }
            }
            String cardTitle = clean(title.group(1));
            Matcher born = BORN.matcher(text(card));
            boolean president = cardTitle.startsWith("President of the Supreme Court");
            boolean chairman = cardTitle.startsWith("Chairman of the");
            String role = president ? "Président de la Cour suprême (Riigikohtu esimees)"
                    : chairman ? "Juge et président de la chambre « " + chamber + " » de la Cour suprême"
                    : "Juge de la Cour suprême (Riigikohtu liige)";
            boolean hasBirth = born.find();
            out.add(new Holder(clean(name.group(1)), role, "Riigikohus (Cour suprême d'Estonie)", null,
                    hasBirth ? iso(born.group(1)) : null, hasBirth ? place(born.group(2)) : null, "gov.judicial",
                    president || chamber == null ? null : "Chambre : " + chamber, url));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page de la Cour suprême : aucun juge trouvé (la page a changé).");
        }
        return out;
    }

    static List<Holder> eestiPankExecutive(String html, String url) {
        List<String> lines = lines(html);
        List<Holder> out = new ArrayList<>();
        Pattern header = Pattern.compile("^(" + NAME + "), (Governor|Deputy Governor) of Eesti Pank$");
        for (int i = 0; i < lines.size(); i++) {
            Matcher h = header.matcher(lines.get(i));
            if (!h.matches()) {
                continue;
            }
            String start = null;
            Pattern became = Pattern.compile("^" + Pattern.quote(h.group(1)) + " became (?:a |the )?(?:Deputy )?Governor of Eesti Pank in ([A-Za-z]+ \\d{4})");
            for (int j = i + 1; j < lines.size() && start == null; j++) {
                Matcher b = became.matcher(lines.get(j));
                if (b.find()) {
                    start = iso(b.group(1));
                } else if (header.matcher(lines.get(j)).matches()) {
                    break;
                }
            }
            String role = "Governor".equals(h.group(2)) ? "Gouverneur de la Banque d'Estonie (Eesti Panga president)"
                    : "Sous-gouverneur de la Banque d'Estonie (Eesti Panga asepresident)";
            out.add(new Holder(h.group(1), role, "Eesti Pank (Banque d'Estonie)", start, null, null, "gov.financial",
                    "Membre du directoire", url));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page du directoire d'Eesti Pank : aucun membre trouvé (la page a changé).");
        }
        return out;
    }

    static List<Holder> eestiPankSupervisory(String html, String url) {
        List<String> lines = lines(html);
        int from = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("The Eesti Pank Supervisory Board is composed of Riigikogu members")) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            throw new IllegalStateException("Page du conseil de surveillance d'Eesti Pank : composition introuvable (la page a changé).");
        }
        List<Holder> out = new ArrayList<>();
        String group = "Membre du Riigikogu";
        String chairStart = null;
        for (int i = from + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("and experts")) {
                group = "Expert";
                continue;
            }
            Matcher since = Pattern.compile("^Since (\\d{1,2} [A-Za-z]+ \\d{4}),$").matcher(line);
            if (since.matches()) {
                chairStart = iso(since.group(1));
                if (i + 1 < lines.size() && NAME_LINE.matcher(lines.get(i + 1)).matches()) {
                    out.add(new Holder(lines.get(i + 1), "Président du conseil de surveillance de la Banque d'Estonie",
                            "Eesti Pank (Banque d'Estonie)", chairStart, null, null, "gov.financial", "Président du conseil de surveillance", url));
                }
                break;
            }
            if (NAME_LINE.matcher(line).matches()) {
                out.add(new Holder(line, "Membre du conseil de surveillance de la Banque d'Estonie",
                        "Eesti Pank (Banque d'Estonie)", null, null, null, "gov.financial", "Conseil de surveillance : " + group.toLowerCase(Locale.ROOT), url));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page du conseil de surveillance d'Eesti Pank : aucun membre trouvé (la page a changé).");
        }
        return out;
    }

    /** Texte de la page, sans balises ni entités, sur une seule ligne. */
    static String text(String html) {
        return String.join(" ", lines(html)).replaceAll("\\s+", " ");
    }

    /** Lignes de texte non vides de la page, sans balises ni entités. */
    static List<String> lines(String html) {
        List<String> out = new ArrayList<>();
        String body = html.replaceAll("(?s)<script.*?</script>", "").replaceAll("(?s)<style.*?</style>", "");
        for (String raw : body.replaceAll("<[^>]+>", "\n").split("\n")) {
            String line = clean(raw);
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    static String clean(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#039;", "'").replace("&#x27;", "'").replace("&quot;", "\"")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String place(String v) {
        return v == null ? null : v.trim();
    }

    /** « 12 October 2026 » → 2026-10-12 ; « June 2026 » → 2026-06 ; {@code null} si illisible. */
    static String iso(String v) {
        try {
            String[] p = v.trim().split(" ");
            if (p.length == 3) {
                return LocalDate.of(Integer.parseInt(p[2]), Month.valueOf(p[1].toUpperCase(Locale.ROOT)),
                        Integer.parseInt(p[0])).toString();
            }
            if (p.length == 2) {
                return String.format("%s-%02d", p[1], Month.valueOf(p[0].toUpperCase(Locale.ROOT)).getValue());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static List<Source> selected(Map<String, String> p) {
        String keys = p.get("keys");
        if (keys == null || keys.isBlank()) {
            return SOURCES;
        }
        List<Source> out = new ArrayList<>();
        for (String key : Arrays.stream(keys.split(",")).map(String::trim).filter(k -> !k.isEmpty()).toList()) {
            out.add(SOURCES.stream().filter(s -> s.key().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Source inconnue : « " + key + " » (valeurs : "
                            + SOURCES.stream().map(Source::key).toList() + ").")));
        }
        return out;
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
