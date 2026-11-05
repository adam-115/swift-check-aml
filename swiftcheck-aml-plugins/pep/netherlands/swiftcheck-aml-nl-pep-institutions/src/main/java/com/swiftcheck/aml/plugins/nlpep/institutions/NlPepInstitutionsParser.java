package com.swiftcheck.aml.plugins.nlpep.institutions;

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
 * <b>nl_pep_institutions</b> – membres des hautes institutions néerlandaises : Raad van State, Hoge Raad et Algemene
 * Rekenkamer (juridictions supérieures et Cour des comptes, Wwft).
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètre {@code sources} : {@code "clé|Institution|adresse;…"}. La clé choisit
 * l'extracteur, chaque site publiant à sa façon :
 * <ul>
 *   <li>{@code raad_van_state} : page « Leden en staatsraden » (rubriques Vice-President, Lid, Staatsraad…, noms de la forme
 *       « Mr. S. (Sybrand) van Haersma Buma ») ;</li>
 *   <li>{@code hoge_raad} : page « Kamers » (président, vice-présidents et raadsheren par chambre, noms avec initiales seulement) ;</li>
 *   <li>{@code algemene_rekenkamer} : page « College » (phrase « bestaat momenteel uit Pieter Duisenberg (president), … »).</li>
 * </ul>
 * Aucune liste de noms n'est embarquée. Pas de dates ni de naissance sur ces pages ; les membres sortis ne sont pas nommés
 * (le core garde une personne retirée de la source pendant la durée de rétention).
 */
@Extension
public class NlPepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "nl-pep-institutions";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (lijst PEP Nederland - instellingen)";
    private static final List<String> KEYS = List.of("raad_van_state", "hoge_raad", "algemene_rekenkamer", "centrale_raad_van_beroep");

    private static final Pattern TITLES = Pattern.compile("(?i)^(?:(?:mr|dr|drs|prof|ir|ing|ll\\.m|msc|ma)\\.?\\s+)+");
    private static final Pattern RVS_NAME = Pattern.compile("^((?:[A-Z][A-Za-z]{0,2}\\.\\s*)+)\\(([^)]+)\\)\\s*(.+)$");
    private static final Pattern HR_NAME = Pattern.compile("^((?:[A-Z][A-Za-z]{0,2}\\.\\s*)+)([A-Za-zÀ-ÿ'’ -]+)$");
    private static final Pattern RK_SENTENCE = Pattern.compile("bestaat momenteel uit (.+?)\\.\\s");

    /** Une institution configurée. */
    record Source(String key, String institution, String url) {
    }

    /** Un membre lu. */
    record Member(String institution, String name, String initialsName, String role) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        List<Source> sources = sources(p.get("sources"));
        download(httpFetcher(delayMs(p)), sources, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), sources, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque membre, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), sources(p.get("sources")), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** « clé|Institution|adresse;… » → institutions, dans l'ordre. */
    static List<Source> sources(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            throw new IllegalStateException("Paramètre « sources » manquant dans la configuration du parser "
                    + "(« raad_van_state|Raad van State|https://…;hoge_raad|Hoge Raad|https://… », voir le README du plugin).");
        }
        List<Source> out = new ArrayList<>();
        for (String item : parameter.split(";")) {
            String[] parts = item.split("\\|", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new IllegalStateException("Source invalide dans « sources » : « " + item + " » (attendu : clé|institution|adresse).");
            }
            if (!KEYS.contains(parts[0].trim())) {
                throw new IllegalStateException("Extracteur inconnu « " + parts[0].trim() + " » (" + String.join(", ", KEYS) + ").");
            }
            out.add(new Source(parts[0].trim(), parts[1].trim(), parts[2].trim()));
        }
        return out;
    }

    static void download(Fetcher fetcher, List<Source> sources, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        for (Source s : sources) {
            Files.writeString(rawDir.resolve(s.key() + ".html"), fetcher.get(s.url()), StandardCharsets.UTF_8);
        }
    }

    /** Lit les pages de {@code rawDir} et écrit les membres ; retourne le message de bilan. */
    String convert(Path rawDir, List<Source> sources, FtmDatasetBuilder dataset) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Source s : sources) {
            Path file = rawDir.resolve(s.key() + ".html");
            if (!Files.exists(file)) {
                throw new IllegalStateException(file.getFileName() + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
            List<Member> members = extract(s, Files.readString(file, StandardCharsets.UTF_8));
            if (members.isEmpty()) {
                throw new IllegalStateException("Aucun membre lu pour « " + s.institution() + " » (" + s.url() + ") : la page a peut-être changé.");
            }
            members.forEach(m -> write(m, s.url(), dataset));
            counts.put(s.institution(), members.size());
        }
        return counts.values().stream().mapToInt(Integer::intValue).sum() + " membres : " + counts + ".";
    }

    /** Extrait les membres de la page selon l'extracteur de l'institution. */
    static List<Member> extract(Source s, String html) {
        List<String> lines = lines(html);
        return switch (s.key()) {
            case "raad_van_state" -> raadVanState(s, lines);
            case "hoge_raad" -> hogeRaad(s, lines);
            case "algemene_rekenkamer" -> rekenkamer(s, String.join(" ", lines) + " ");
            case "centrale_raad_van_beroep" -> centraleRaad(s, lines);
            default -> throw new IllegalStateException("Extracteur inconnu : " + s.key());
        };
    }

    /** Lignes de texte de la page : une balise ferme une ligne, entités décodées, lignes vides retirées. */
    static List<String> lines(String html) {
        String clean = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", "\n");
        List<String> out = new ArrayList<>();
        for (String raw : Html.decode(clean).split("\n")) {
            String line = raw.replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<Member> raadVanState(Source s, List<String> lines) {
        List<Member> out = new ArrayList<>();
        int start = lines.indexOf("Vice-President");
        if (start < 0) {
            return out;
        }
        String role = null;
        for (String line : lines.subList(start, lines.size())) {
            String cleaned = TITLES.matcher(line).replaceFirst("");
            Matcher n = RVS_NAME.matcher(cleaned);
            if (n.matches()) {
                String surname = n.group(3).replaceAll(",\\s*(MA|MSc|LL\\.M\\.?)$", "").trim();
                String first = n.group(2).trim();
                out.add(new Member(s.institution(), first + " " + surname, (n.group(1).trim() + " " + surname).replaceAll("\\s+", " "), role));
            } else if (line.startsWith("Vice-President") || line.startsWith("Voorzitter") || line.startsWith("Lid van")
                    || line.startsWith("Staatsraad")) {
                role = line;
            } else if (role != null && out.size() > 3 && (line.startsWith("Terug") || line.startsWith("Meer") || line.startsWith("Zie"))) {
                break;
            }
        }
        return out;
    }

    private static List<Member> hogeRaad(Source s, List<String> lines) {
        List<Member> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("Eerste of civiele kamer")) {
                start = i;                                      // la dernière occurrence : la liste, après le menu des chambres
            }
        }
        if (start < 0) {
            return out;
        }
        String section = null;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals("Bijzondere taken") || line.startsWith("Data geen uitspraken")) {
                break;
            }
            if (line.equals("Vicepresidenten") || line.equals("Raadsheren") || line.startsWith("Raadsheer in buitengewone dienst")
                    || line.startsWith("Raadsheren in buitengewone dienst")) {
                section = line.startsWith("Raadsheer") && line.contains("buitengewone") ? "Raadsheer in buitengewone dienst"
                        : line.equals("Vicepresidenten") ? "Vicepresident" : "Raadsheer";
                continue;
            }
            if (line.startsWith(",")) {
                if (!out.isEmpty() && line.toLowerCase().contains("president") && !line.toLowerCase().contains("vicepresident")) {
                    Member last = out.remove(out.size() - 1);
                    out.add(new Member(last.institution(), last.name(), last.initialsName(), "President"));
                }
                continue;
            }
            Matcher n = HR_NAME.matcher(line);
            if (n.matches() && section != null || (n.matches() && i == start + 1)) {
                String name = line.replaceAll("(?<=\\b[A-Z])\\.(?=[A-Za-z]{2,})", ". ").replaceAll("\\s+", " ").trim();
                if (out.stream().noneMatch(m -> m.name().equals(name))) {
                    out.add(new Member(s.institution(), name, name, section == null ? "President" : section));
                }
            }
        }
        return out;
    }

    /**
     * Page « Organisatie » de la Centrale Raad van Beroep : le bestuur (« mr. drs. J.C. Boeree, president »), puis les
     * rubriques « Senior raadsheren », « Raadsheren » et « Raadsheren-plaatsvervangers », un nom par ligne (initiales seulement).
     */
    private static List<Member> centraleRaad(Source s, List<String> lines) {
        List<Member> out = new ArrayList<>();
        int start = lines.indexOf("Bestuur");
        if (start < 0) {
            return out;
        }
        String section = null;
        for (String line : lines.subList(start, lines.size())) {
            if (line.startsWith("Zie ook") || line.startsWith("*Wordt")) {
                break;
            }
            switch (line) {
                case "Senior raadsheren" -> section = "Senior raadsheer";
                case "Raadsheren" -> section = "Raadsheer";
                case "Raadsheren-plaatsvervangers" -> section = "Raadsheer-plaatsvervanger";
                case "Bestuur" -> section = "Bestuurder";
                default -> {
                    String cleaned = TITLES.matcher(line.replace("*", "").trim()).replaceFirst("");
                    String role = section;
                    int comma = cleaned.indexOf(',');
                    if (comma > 0) {
                        role = cleaned.substring(comma + 1).trim();                // « president », « interim bestuurder »
                        cleaned = cleaned.substring(0, comma).trim();
                    }
                    cleaned = TITLES.matcher(cleaned).replaceFirst("");
                    Matcher n = HR_NAME.matcher(cleaned);
                    if (section != null && n.matches()) {
                        String name = cleaned.replaceAll("(?<=\\b[A-Z])\\.(?=[A-Za-z]{2,})", ". ").replaceAll("\\s+", " ")
                                .replaceAll(",?\\s*LL\\.M\\.?$", "").trim();
                        if (out.stream().noneMatch(m -> m.name().equals(name))) {
                            out.add(new Member(s.institution(), name, name, role == null ? section : role));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<Member> rekenkamer(Source s, String text) {
        List<Member> out = new ArrayList<>();
        Matcher sentence = RK_SENTENCE.matcher(text);
        if (!sentence.find()) {
            return out;
        }
        for (String part : sentence.group(1).split(",| en ")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String role = "Collegelid";
            Matcher r = Pattern.compile("^(.*?)\\s*\\((president)\\)$").matcher(p);
            if (r.matches()) {
                p = r.group(1).trim();
                role = "President";
            }
            if (p.split("\\s+").length >= 2) {
                out.add(new Member(s.institution(), p, p, role));
            }
        }
        return out;
    }

    private static void write(Member m, String url, FtmDatasetBuilder dataset) {
        String role = m.role() == null ? "Lid" : m.role();
        String label = role + ", " + m.institution();
        String[] words = m.name().split("\\s+");

        // ID = institution + nom : les pages ne donnent pas d'identifiant
        FtmEntity person = dataset.newEntity("Person", m.institution(), m.name());
        person.addValue("name", m.name());
        if (!m.initialsName().equals(m.name())) {
            person.addValue("alias", m.initialsName());
        }
        person.addValue("firstName", words[0]);
        person.addValue("lastName", words.length > 1 ? m.name().substring(m.name().indexOf(' ') + 1) : null);
        person.addValue("country", "nl");
        person.addValue("position", label);
        person.addValue("sourceUrl", url);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", label);
        position.addValue("name", label);
        position.addValue("country", "nl");
        position.addValue("organization", m.institution());
        position.addValue("topics", m.institution().contains("Rekenkamer") ? "gov.financial" : "gov.judicial");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.institution(), m.name(), role);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", url);
        dataset.addEntity(occupancy);
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
