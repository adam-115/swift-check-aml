package com.swiftcheck.aml.plugins.nopep.parliament;

import java.io.ByteArrayInputStream;
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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.pf4j.Extension;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>no_pep_parliament</b> – représentants du Storting (parlement norvégien), personnes politiquement exposées au sens de la
 * loi norvégienne sur le blanchiment (hvitvaskingsloven).
 *
 * <p>Source officielle : l'API ouverte du Storting {@code https://data.stortinget.no/eksport} (XML, sans clé) :
 * <ul>
 *   <li>{@code dagensrepresentanter} : les représentants en fonction (identifiant, nom, naissance, décès, genre, comté, parti,
 *       indicateur de suppléant en exercice) ;</li>
 *   <li>{@code stortingsperioder} : les législatures et leurs dates ;</li>
 *   <li>{@code representanter?stortingsperiodeid=…} : tous les représentants d'une législature (suppléants compris).</li>
 * </ul>
 * L'API ne donne pas la date de départ d'un représentant : un ancien représentant est publié avec la fin de la législature
 * comme fin de mandat, et un représentant de la législature en cours qui n'est plus en fonction sans date de fin. Sont repris
 * les représentants en fonction et ceux dont une législature s'est terminée depuis moins de {@code monthsAfterEnd} mois ;
 * les personnes décédées sont ignorées.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"apiUrl": "https://data.stortinget.no/eksport", "monthsAfterEnd": "12", "delayMs": "500"}
 * }</pre>
 */
@Extension
public class NoPepParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "no-pep-parliament";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String CURRENT_FILE = "dagens.xml";
    static final String PERIODS_FILE = "perioder.xml";
    static final String SEAT = "Représentant au Storting (Stortingsrepresentant)";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Norvege - Stortinget)";

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Une législature. */
    record Period(String id, LocalDate from, LocalDate to) {
    }

    /** Un représentant de l'API. */
    record Rep(String id, String firstName, String lastName, String sex, String birthDate, String deathDate, String county,
            String party, boolean substitute) {

        String fullName() {
            return (firstName + " " + lastName).trim();
        }
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int current, int former, int periods, int dead) {

        String message() {
            return String.format("%d représentants en fonction, %d anciens (%d législatures lues), %d personnes décédées écartées.",
                    current, former, periods, dead);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int months = monthsAfterEnd(p);
        download(httpFetcher(delayMs(p)), required(p, "apiUrl"), LocalDate.now(), months, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now(), months, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
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

    /** Télécharge les représentants en fonction, les législatures et les listes des législatures utiles. */
    static void download(Fetcher fetcher, String apiUrl, LocalDate today, int monthsAfterEnd, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        Files.writeString(rawDir.resolve(CURRENT_FILE), fetcher.get(base + "/dagensrepresentanter"), StandardCharsets.UTF_8);
        String periodsXml = fetcher.get(base + "/stortingsperioder");
        Files.writeString(rawDir.resolve(PERIODS_FILE), periodsXml, StandardCharsets.UTF_8);
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        for (Period period : periods(periodsXml)) {
            if (period.from().isAfter(today) || period.to().isBefore(cutoff)) {
                continue;
            }
            Files.writeString(rawDir.resolve("representanter-" + period.id() + ".xml"),
                    fetcher.get(base + "/representanter?stortingsperiodeid=" + period.id()), StandardCharsets.UTF_8);
        }
    }

    /** Lit les fichiers de {@code rawDir} et écrit les représentants. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws Exception {
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        Path currentFile = rawDir.resolve(CURRENT_FILE);
        Path periodsFile = rawDir.resolve(PERIODS_FILE);
        if (!Files.exists(currentFile) || !Files.exists(periodsFile)) {
            throw new IllegalStateException(CURRENT_FILE + " ou " + PERIODS_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Rep> current = reps(read(currentFile), "dagensrepresentant");
        if (current.isEmpty()) {
            throw new IllegalStateException("Aucun représentant en fonction dans " + CURRENT_FILE + " : le format de l'API a peut-être changé.");
        }
        List<Period> periods = periods(Files.readString(periodsFile, StandardCharsets.UTF_8));
        Map<String, Rep> currentById = new LinkedHashMap<>();
        int dead = 0;
        for (Rep r : current) {
            if (r.deathDate() != null) {
                dead++;
                continue;
            }
            currentById.put(r.id(), r);
            write(r, "en fonction", r.substitute() ? "Suppléant en exercice (vara)" : null, null, null, "current", dataset);
        }
        java.util.Set<String> formerIds = new java.util.LinkedHashSet<>();
        for (Period period : periods) {
            if (period.to().isBefore(cutoff) || period.from().isAfter(today)) {
                continue;                                           // fichier resté d'une exécution précédente
            }
            Path file = rawDir.resolve("representanter-" + period.id() + ".xml");
            if (!Files.exists(file)) {
                continue;
            }
            boolean ongoing = !period.to().isBefore(today);
            for (Rep r : reps(read(file), "representant")) {
                if (r.deathDate() != null) {
                    dead++;
                    continue;
                }
                if (ongoing) {
                    if (currentById.containsKey(r.id())) {
                        continue;                                   // déjà écrit comme représentant en fonction
                    }
                    write(r, "législature " + period.id(), "Sorti en cours de législature : date de départ inconnue (source sans date)",
                            period.from(), null, "ended", dataset);
                } else {
                    write(r, "législature " + period.id(), "Fin de mandat = fin de la législature (la source ne donne pas la date de départ)",
                            period.from(), period.to(), "ended", dataset);
                }
                formerIds.add(r.id());
            }
        }
        formerIds.removeAll(currentById.keySet());
        return new Report(currentById.size(), formerIds.size(), (int) periods.stream()
                .filter(p -> !p.to().isBefore(cutoff) && !p.from().isAfter(today)
                        && Files.exists(rawDir.resolve("representanter-" + p.id() + ".xml"))).count(), dead);
    }

    private static void write(Rep r, String period, String note, LocalDate start, LocalDate end, String status, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", r.id());
        person.addValue("name", r.fullName());
        person.addValue("firstName", r.firstName());
        person.addValue("lastName", r.lastName());
        person.addValue("gender", "mann".equals(r.sex()) ? "male" : "kvinne".equals(r.sex()) ? "female" : null);
        person.addValue("birthDate", r.birthDate());
        person.addValue("country", "no");
        person.addValue("position", SEAT);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "stortinget");
        position.addValue("name", SEAT);
        position.addValue("country", "no");
        position.addValue("organization", "Stortinget");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", r.id(), "stortinget", period);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", start == null ? null : start.toString());
        occupancy.addValue("endDate", end == null ? null : end.toString());
        occupancy.addValue("status", status);
        List<String> parts = new ArrayList<>();
        parts.add("Période : " + period);
        if (r.county() != null) {
            parts.add("Comté : " + r.county());
        }
        if (r.party() != null) {
            parts.add("Parti : " + r.party());
        }
        if (note != null) {
            parts.add(note);
        }
        occupancy.addValue("description", String.join(" ; ", parts));
        dataset.addEntity(occupancy);
    }

    /** Les législatures du fichier {@code stortingsperioder}, sans les dates futures invalides. */
    static List<Period> periods(String xml) {
        List<Period> out = new ArrayList<>();
        try {
            for (Element e : elements(parse(xml), "stortingsperiode")) {
                String id = text(e, "id");
                String from = text(e, "fra");
                String to = text(e, "til");
                if (id != null && from != null && to != null) {
                    out.add(new Period(id, LocalDate.parse(from.substring(0, 10)), LocalDate.parse(to.substring(0, 10))));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("stortingsperioder illisible : " + e.getMessage(), e);
        }
        return out;
    }

    /** Les représentants d'un fichier : éléments {@code tag} (« dagensrepresentant » ou « representant »). */
    static List<Rep> reps(Document doc, String tag) {
        List<Rep> out = new ArrayList<>();
        for (Element e : elements(doc, tag)) {
            String birth = text(e, "foedselsdato");
            String death = text(e, "doedsdato");
            Element county = child(e, "fylke");
            Element party = child(e, "parti");
            out.add(new Rep(text(e, "id"), text(e, "fornavn"), text(e, "etternavn"), text(e, "kjoenn"),
                    birth == null ? null : birth.substring(0, 10), death == null ? null : death.substring(0, 10),
                    county == null ? null : text(county, "navn"), party == null ? null : text(party, "navn"),
                    "true".equals(text(e, "vara_representant"))));
        }
        return out;
    }

    private static Document read(Path file) throws Exception {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Éléments {@code tag} du document, tous niveaux (le fichier n'imbrique jamais deux éléments de même nom). */
    private static List<Element> elements(Document doc, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList list = doc.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            out.add((Element) list.item(i));
        }
        return out;
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** Texte d'un enfant direct, {@code null} s'il est absent, vide ou {@code i:nil}. */
    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        if (e == null || "true".equals(e.getAttribute("i:nil"))) {
            return null;
        }
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    static String required(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Paramètre « " + key + " » manquant dans la configuration du parser (voir le README du plugin).");
        }
        return v.trim();
    }

    static int monthsAfterEnd(Map<String, String> p) {
        String v = p.get("monthsAfterEnd");
        if (v == null || v.isBlank()) {
            return 12;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « monthsAfterEnd » invalide : « " + v + " » (nombre de mois attendu).");
        }
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 500 : Long.parseLong(v.trim());
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(delayMs);
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
