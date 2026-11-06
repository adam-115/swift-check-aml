package com.swiftcheck.aml.plugins.lvpep.institutions;

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
 * <b>lv_pep_institutions</b> – président de la République, président et sénateurs de la Cour suprême, juges de la Cour
 * constitutionnelle et conseil de la Banque de Lettonie, personnes politiquement exposées au sens de la loi lettonne sur la
 * prévention du blanchiment de capitaux.
 *
 * <p>Cinq pages officielles (clé du paramètre {@code keys}) :
 * <ul>
 *   <li>{@code president} : {@code president.lv/en/biography} (« … became the President of Latvia on … ») ;</li>
 *   <li>{@code supreme-court-president} : {@code at.gov.lv/.../struktura/vadiba} (« Since … the President of the Supreme Court has
 *       been … ») ;</li>
 *   <li>{@code senators} : {@code at.gov.lv/.../senatori/departamentu-sastavs} (sénateurs des départements civil, pénal et
 *       administratif, présidents de département) ;</li>
 *   <li>{@code constitutional-court} : {@code satversmestiesa.lv/en/judges} (président, vice-président et juges) ;</li>
 *   <li>{@code bank-of-latvia} : {@code bank.lv/en/about-us/governance/structure} (gouverneur, sous-gouverneurs, membres du
 *       conseil).</li>
 * </ul>
 * Aucune de ces pages ne publie de naissance ni de décès. La Cour des comptes (Valsts kontrole : lrvk.gov.lv injoignable) et les
 * anciens présidents ne sont pas lus. Aucun des sites n'impose de délai ; le parser attend 2 secondes entre deux pages, avec un
 * User-Agent générique. Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"keys": "president,supreme-court-president,senators,constitutional-court,bank-of-latvia", "delayMs": "2000"}
 * }</pre>
 */
@Extension
public class LvPepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "lv-pep-institutions";
    static final String TOPIC = "role.pep";
    static final long DEFAULT_DELAY_MS = 2000;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Lettonie - institutions)";
    private static final String CAP = "\\p{Lu}[\\p{L}'’]*(?:-\\p{Lu}[\\p{L}'’]*)*";
    private static final Pattern NAME = Pattern.compile("^" + CAP + "(?: " + CAP + ")+$");
    /** Sénateur : prénom(s) en capitales initiales puis nom de famille en MAJUSCULES, titres académiques éventuels devant. */
    private static final Pattern SENATOR = Pattern.compile(
            "^((?:(?:Dr|Mg|Prof|Assoc)\\.?(?: prof\\.| iur\\.)? ?)*)(" + CAP + "(?: " + CAP + ")*) (\\p{Lu}{2,}(?:-\\p{Lu}{2,})*)$");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2} [A-Za-z]+ \\d{4})");

    /** Une page source : clé, fichier brut et adresse. */
    record Source(String key, String file, String url) {
    }

    static final List<Source> SOURCES = List.of(
            new Source("president", "president.html", "https://www.president.lv/en/biography"),
            new Source("supreme-court-president", "supreme-court-president.html",
                    "https://www.at.gov.lv/en/par-augstako-tiesu/struktura/vadiba"),
            new Source("senators", "senators.html", "https://www.at.gov.lv/en/par-augstako-tiesu/senatori/departamentu-sastavs"),
            new Source("constitutional-court", "constitutional-court.html", "https://www.satversmestiesa.lv/en/judges"),
            new Source("bank-of-latvia", "bank-of-latvia.html", "https://www.bank.lv/en/about-us/governance/structure"));

    /** Un titulaire lu sur une page. */
    record Holder(String name, String role, String organization, String start, String topic, String note, String url) {
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
            for (Holder h : holders(s, Files.readString(file, StandardCharsets.UTF_8))) {
                FtmEntity person = dataset.newEntity("Person", h.name());
                person.addValue("name", h.name());
                person.addValue("country", "lv");
                person.addValue("nationality", "lv");
                person.addValue("position", h.role());
                person.addValue("sourceUrl", h.url());
                dataset.addEntity(person);

                FtmEntity position = positions.get(h.role() + "|" + h.organization());
                if (position == null) {
                    position = dataset.newEntity("Position", h.organization(), h.role());
                    position.addValue("name", h.role());
                    position.addValue("country", "lv");
                    position.addValue("organization", h.organization());
                    position.addValue("topics", "gov.national");
                    position.addValue("topics", h.topic());
                    dataset.addEntity(position);
                    positions.put(h.role() + "|" + h.organization(), position);
                }
                FtmEntity occupancy = dataset.newEntity("Occupancy", h.name(), h.organization(), h.role());
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
        return persons + " personnes lues sur " + sources.size() + " source(s). Sources : Présidence, Cour suprême, Cour constitutionnelle "
                + "et Banque de Lettonie (sites officiels).";
    }

    /** Les titulaires d'une page ; échoue si la page n'a plus la forme attendue. */
    static List<Holder> holders(Source s, String html) {
        List<String> lines = lines(html);
        return switch (s.key()) {
            case "president" -> president(lines, s.url());
            case "supreme-court-president" -> supremeCourtPresident(lines, s.url());
            case "senators" -> senators(lines, s.url());
            case "constitutional-court" -> constitutionalCourt(lines, s.url());
            case "bank-of-latvia" -> bank(lines, s.url());
            default -> throw new IllegalArgumentException("Source inconnue : " + s.key());
        };
    }

    static List<Holder> president(List<String> lines, String url) {
        Pattern p = Pattern.compile("^(" + CAP + "(?: " + CAP + ")+) became the President of Latvia on (\\d{1,2} [A-Za-z]+ \\d{4})");
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                return List.of(new Holder(m.group(1), "Président de la République (Valsts prezidents)", "Valsts prezidents",
                        iso(m.group(2)), "gov.head", null, url));
            }
        }
        throw new IllegalStateException("Page de la Présidence : nom et prise de fonction introuvables (la page a changé).");
    }

    static List<Holder> supremeCourtPresident(List<String> lines, String url) {
        Pattern p = Pattern.compile("^Since (\\d{1,2} [A-Za-z]+ \\d{4}) the President of the Supreme Court has been$");
        for (int i = 0; i + 1 < lines.size(); i++) {
            Matcher m = p.matcher(lines.get(i));
            if (m.matches() && NAME.matcher(lines.get(i + 1)).matches()) {
                return List.of(new Holder(lines.get(i + 1), "Président de la Cour suprême (Augstākās tiesas priekšsēdētājs)",
                        "Augstākā tiesa (Cour suprême de Lettonie)", iso(m.group(1)), "gov.judicial", null, url));
            }
        }
        throw new IllegalStateException("Page de la direction de la Cour suprême : président introuvable (la page a changé).");
    }

    static List<Holder> senators(List<String> lines, String url) {
        List<Holder> out = new ArrayList<>();
        Pattern section = Pattern.compile("^Senators of the Department of (Civil|Criminal|Administrative) Cases$");
        String department = null;
        for (int i = 0; i < lines.size(); i++) {
            Matcher sec = section.matcher(lines.get(i));
            if (sec.matches()) {
                department = sec.group(1).toLowerCase(Locale.ROOT);
                continue;
            }
            if (department == null) {
                continue;
            }
            Matcher m = SENATOR.matcher(lines.get(i));
            if (!m.matches() || i + 1 >= lines.size()) {
                continue;
            }
            String next = lines.get(i + 1);
            boolean chair = next.startsWith(", Chair of the Department") || next.startsWith("Chair of the Department");
            if (!chair && !next.contains("Senator") && !next.contains("Judge")) {
                continue;
            }
            String name = m.group(2) + " " + titleCase(m.group(3));
            out.add(new Holder(name,
                    chair ? "Président du département " + dept(department) + " de la Cour suprême (sénateur)"
                            : "Sénateur de la Cour suprême (département " + dept(department) + ")",
                    "Augstākā tiesa (Cour suprême de Lettonie)", null, "gov.judicial", "Département : " + dept(department), url));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page des sénateurs de la Cour suprême : aucun sénateur trouvé (la page a changé).");
        }
        return out;
    }

    static List<Holder> constitutionalCourt(List<String> lines, String url) {
        int from = lines.indexOf("Judges in the Constitutional Court:");
        if (from < 0) {
            throw new IllegalStateException("Page de la Cour constitutionnelle : liste des juges introuvable (la page a changé).");
        }
        List<Holder> out = new ArrayList<>();
        for (int i = from + 1; i + 1 < lines.size(); i++) {
            String title = lines.get(i + 1);
            if (NAME.matcher(lines.get(i)).matches() && (title.startsWith("President") || title.startsWith("Vice President")
                    || title.startsWith("Judge"))) {
                String role = title.startsWith("Vice President") ? "Vice-président de la Cour constitutionnelle"
                        : title.startsWith("President") ? "Président de la Cour constitutionnelle"
                        : "Juge de la Cour constitutionnelle (Satversmes tiesas tiesnesis)";
                out.add(new Holder(lines.get(i), role, "Satversmes tiesa (Cour constitutionnelle de Lettonie)", null,
                        "gov.judicial", null, url));
                i++;
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page de la Cour constitutionnelle : aucun juge trouvé (la page a changé).");
        }
        return out;
    }

    static List<Holder> bank(List<String> lines, String url) {
        int from = lines.indexOf("Council");
        if (from < 0) {
            throw new IllegalStateException("Page de la Banque de Lettonie : conseil introuvable (la page a changé).");
        }
        List<Holder> out = new ArrayList<>();
        for (int i = from + 1; i + 1 < lines.size(); i += 2) {
            String name = lines.get(i);
            String title = lines.get(i + 1);
            if (!NAME.matcher(name).matches() || !(title.equals("Governor") || title.equals("Deputy Governor")
                    || title.equals("Member of the Council"))) {
                break;
            }
            String role = title.equals("Governor") ? "Gouverneur de la Banque de Lettonie (Latvijas Bankas prezidents)"
                    : title.equals("Deputy Governor") ? "Sous-gouverneur de la Banque de Lettonie"
                    : "Membre du conseil de la Banque de Lettonie";
            out.add(new Holder(name, role, "Latvijas Banka (Banque de Lettonie)", null, "gov.financial", "Conseil de la Banque de Lettonie", url));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Page de la Banque de Lettonie : aucun membre du conseil trouvé (la page a changé).");
        }
        return out;
    }

    private static String dept(String department) {
        return switch (department) {
            case "civil" -> "civil";
            case "criminal" -> "pénal";
            default -> "administratif";
        };
    }

    /** « KALNIŅŠ » → « Kalniņš » ; « LAVIŅŠ-X » → « Laviņš-X » (chaque partie du nom composé). */
    static String titleCase(String upper) {
        return Arrays.stream(upper.split("-")).map(p -> p.charAt(0) + p.substring(1).toLowerCase(Locale.ROOT))
                .reduce((a, b) -> a + "-" + b).orElse(upper);
    }

    /** Lignes de texte non vides de la page, sans balises, entités décodées. */
    static List<String> lines(String html) {
        List<String> out = new ArrayList<>();
        String body = html.replaceAll("(?s)<svg.*?</svg>", "").replaceAll("(?s)<script.*?</script>", "")
                .replaceAll("(?s)<style.*?</style>", "");
        for (String raw : body.replaceAll("<[^>]+>", "\n").split("\n")) {
            String line = decode(raw).replaceAll("\\s+", " ").trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /** Entités HTML courantes sur ces sites (lettres lettones, guillemets, tirets) et références numériques. */
    static String decode(String s) {
        String r = s.replace("&nbsp;", " ").replace("&Scaron;", "Š").replace("&scaron;", "š").replace("&Zcaron;", "Ž")
                .replace("&zcaron;", "ž").replace("&ndash;", "–").replace("&mdash;", "—").replace("&ldquo;", "“")
                .replace("&rdquo;", "”").replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&hellip;", "…").replace("&amp;", "&");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(r);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace(' ', ' ');
    }

    /** « 16 June 2020 » → 2020-06-16 ; {@code null} si illisible. */
    static String iso(String v) {
        try {
            String[] p = v.trim().split(" ");
            return LocalDate.of(Integer.parseInt(p[2]), Month.valueOf(p[1].toUpperCase(Locale.ROOT)), Integer.parseInt(p[0])).toString();
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
