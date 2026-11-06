package com.swiftcheck.aml.plugins.dkpep.folketing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.pf4j.Extension;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>dk_pep_parliament</b>, <b>dk_pep_government</b> et <b>dk_pep_oversight</b> – députés du Folketing, président du Folketing,
 * ministres et Statsrevisorer danois, personnes politiquement exposées au sens de la loi danoise sur la prévention du blanchiment
 * (hvidvaskloven).
 *
 * <p>Source officielle : l'API ouverte du Folketing {@code https://oda.ft.dk/api/Aktør} (OData, JSON, sans clé), entités de type
 * « Person » (environ 3 400, dont 2 000 avec une fiche biographique XML). La fiche donne la naissance (date complète), le décès, le
 * sexe, le parti et une carrière en lignes de texte datées : {@code constituencies} (mandats de député), {@code ministers},
 * {@code presidents} (président du Folketing) et {@code auditors} (Statsrevisorerne). Exemple :
 * « Forsvarsminister, 9. august 2013 – 28. juni 2015. » ou « Justitsminister fra 3. juni 2026. ». Les suppléants temporaires
 * ({@code substitutes}) ne sont pas repris.
 *
 * <p>Le paramètre {@code scope} choisit la couche : {@code parliament}, {@code government} ou {@code oversight}.
 * {@code monthsAfterEnd} fixe la fenêtre après la fin des fonctions (12 mois par défaut, 600 pour un historique). Les personnes
 * décédées sont ignorées. Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"apiUrl": "https://oda.ft.dk/api", "scope": "parliament", "monthsAfterEnd": "12", "delayMs": "500"}
 * }</pre>
 */
@Extension
public class DkPepFolketingParser implements Iparser {

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String PAGE_PREFIX = "aktoerer-";
    static final int PAGE_SIZE = 100;
    static final List<String> SCOPES = List.of("parliament", "government", "oversight");

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Danemark - Folketinget)";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> MONTHS = List.of("januar", "februar", "marts", "april", "maj", "juni", "juli", "august",
            "september", "oktober", "november", "december");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})\\. (januar|februar|marts|april|maj|juni|juli|august|september|oktober|november|december) (\\d{4})");

    /** Une fonction datée lue dans une ligne de carrière. */
    record Term(String label, LocalDate start, LocalDate end) {
    }

    /** Une personne de l'API. */
    record Member(String id, String name, String firstName, String lastName, String sex, String birthDate, boolean dead,
            String party, List<Term> seats, List<Term> ministries, List<Term> presidencies, List<Term> auditorships) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String scope = scope(p);
        download(httpFetcher(delayMs(p)), apiUrl(p), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(prefix(scope), TOPIC);
        String message = convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), scope, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String scope = scope(p);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(prefix(scope), TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), scope, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Télécharge toutes les pages de personnes (100 par page, jusqu'à une page incomplète). */
    static void download(Fetcher fetcher, String apiUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        String entity = URLEncoder.encode("Aktør", StandardCharsets.UTF_8);
        for (int page = 0; page < 1000; page++) {
            String body = fetcher.get(base + "/" + entity + "?$filter=typeid%20eq%205&$orderby=id&$top=" + PAGE_SIZE + "&$skip="
                    + page * PAGE_SIZE);
            Files.writeString(rawDir.resolve(PAGE_PREFIX + String.format("%03d", page) + ".json"), body, StandardCharsets.UTF_8);
            if (MAPPER.readTree(body).path("value").size() < PAGE_SIZE) {
                return;
            }
        }
        throw new IllegalStateException("Plus de 1000 pages de personnes : arrêt de sécurité.");
    }

    /** Lit les pages de {@code rawDir} et écrit la couche demandée. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, LocalDate today, int monthsAfterEnd, String scope, FtmDatasetBuilder dataset) throws IOException {
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        List<Path> pages = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rawDir, PAGE_PREFIX + "*.json")) {
            stream.forEach(pages::add);
        }
        if (pages.isEmpty()) {
            throw new IllegalStateException("Aucune page " + PAGE_PREFIX + "*.json dans " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        pages.sort(null);
        int read = 0;
        int dead = 0;
        int kept = 0;
        int unreadable = 0;
        for (Path page : pages) {
            for (JsonNode row : MAPPER.readTree(Files.readString(page, StandardCharsets.UTF_8)).path("value")) {
                String bio = row.path("biografi").asText(null);
                if (bio == null || bio.isBlank()) {
                    continue;
                }
                Member m;
                try {
                    m = member(row.path("id").asText(), row.path("navn").asText(), bio);
                } catch (IllegalStateException e) {
                    unreadable++;
                    continue;
                }
                read++;
                if (m.dead()) {
                    dead++;
                    continue;
                }
                List<Term> terms = terms(m, scope);
                terms = terms.stream().filter(t -> t.end() == null || !t.end().isBefore(cutoff)).toList();
                if (terms.isEmpty()) {
                    continue;
                }
                write(m, terms, scope, dataset);
                kept++;
            }
        }
        if (read == 0) {
            throw new IllegalStateException("Aucune fiche biographique lisible dans " + rawDir + " : le format de l'API a peut-être changé.");
        }
        return String.format("%d fiches lues (%d illisibles), %d décédées écartées, %d personnes gardées (%s).", read, unreadable, dead, kept, scope);
    }

    private static List<Term> terms(Member m, String scope) {
        List<Term> out = new ArrayList<>();
        switch (scope) {
            case "parliament" -> {
                out.addAll(m.seats());
                out.addAll(m.presidencies());
            }
            case "government" -> out.addAll(m.ministries());
            default -> out.addAll(m.auditorships());
        }
        return out;
    }

    private static void write(Member m, List<Term> terms, String scope, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", m.id());
        person.addValue("name", m.name());
        person.addValue("firstName", m.firstName());
        person.addValue("lastName", m.lastName());
        person.addValue("gender", "Mand".equals(m.sex()) ? "male" : "Kvinde".equals(m.sex()) ? "female" : null);
        person.addValue("birthDate", m.birthDate());
        person.addValue("country", "dk");
        for (String r : new TreeSet<>(terms.stream().map(t -> role(scope, t)).toList())) {
            person.addValue("position", r);
        }
        dataset.addEntity(person);

        for (Term t : terms) {
            String role = role(scope, t);
            FtmEntity position = dataset.newEntity("Position", scope, role);
            position.addValue("name", role);
            position.addValue("country", "dk");
            position.addValue("organization", organization(scope, t));
            position.addValue("topics", "gov.national");
            position.addValue("topics", switch (scope) {
                case "parliament" -> "gov.legislative";
                case "government" -> "gov.executive";
                default -> "gov.financial";
            });
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", m.id(), scope, role, String.valueOf(t.start()));
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", t.start().toString());
            occupancy.addValue("endDate", t.end() == null ? null : t.end().toString());
            occupancy.addValue("status", t.end() == null ? "current" : "ended");
            List<String> parts = new ArrayList<>();
            parts.add("Source : " + t.label());
            if ("parliament".equals(scope) && m.party() != null) {
                parts.add("Parti : " + m.party());
            }
            occupancy.addValue("description", String.join(" ; ", parts));
            dataset.addEntity(occupancy);
        }
    }

    /** Libellé français de la fonction. */
    static String role(String scope, Term t) {
        String label = t.label();
        switch (scope) {
            case "parliament":
                return label.startsWith("Folketingets formand") ? "Président du Folketing (Folketingets formand)"
                        : "Député au Folketing (folketingsmedlem)";
            case "government":
                if (label.toLowerCase().startsWith("statsminister")) {
                    return "Premier ministre (statsminister)";
                }
                return "Ministre : " + label;
            default:
                return "Statsrevisor (auditeur d'État, Statsrevisorerne)";
        }
    }

    private static String organization(String scope, Term t) {
        return switch (scope) {
            case "parliament" -> "Folketinget";
            case "government" -> "Regeringen";
            default -> "Statsrevisorerne";
        };
    }

    /** Une fiche : naissance, décès, parti et lignes de carrière datées. */
    static Member member(String id, String name, String bio) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bio.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();
            Element career = child(root, "career");
            String first = text(root, "firstname");
            String last = text(root, "lastname");
            return new Member(id, name, first, last, text(root, "sex"), birth(text(root, "born")), text(root, "died") != null,
                    text(root, "party"), lines(career, "constituencies", "constituency", false),
                    lines(career, "ministers", "minister", false), lines(career, "presidents", "president", false),
                    lines(career, "auditors", "auditor", false));
        } catch (Exception e) {
            throw new IllegalStateException("Fiche " + id + " illisible : " + e.getMessage(), e);
        }
    }

    /** Les lignes de carrière {@code item} sous {@code group}, converties en fonctions datées. */
    private static List<Term> lines(Element career, String group, String item, boolean unused) {
        List<Term> out = new ArrayList<>();
        Element g = career == null ? null : child(career, group);
        if (g == null) {
            return out;
        }
        NodeList list = g.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element e && e.getTagName().equals(item)) {
                Term t = term(e.getTextContent());
                if (t != null) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /**
     * Une ligne de carrière : « Libellé, 9. august 2013 – 28. juni 2015. » (deux dates), « Libellé fra 3. juin 2026. » (début seul)
     * ou « Libellé 21. juni 2019 - 1. november 2022. ». Renvoie {@code null} si aucune date n'est lisible.
     */
    static Term term(String raw) {
        String text = raw == null ? "" : raw.replaceAll("[\\u00A0\\u202F]", " ").replaceAll("\\s+", " ").trim();
        Matcher m = DATE.matcher(text);
        List<LocalDate> dates = new ArrayList<>();
        int firstAt = -1;
        while (m.find()) {
            if (firstAt < 0) {
                firstAt = m.start();
            }
            dates.add(LocalDate.of(Integer.parseInt(m.group(3)), MONTHS.indexOf(m.group(2)) + 1, Integer.parseInt(m.group(1))));
        }
        if (dates.isEmpty()) {
            return null;
        }
        String label = text.substring(0, firstAt).trim().replaceAll("(,|\\s+fra)$", "").trim();
        if (label.isEmpty()) {
            return null;
        }
        boolean open = text.substring(0, firstAt).trim().endsWith("fra") || dates.size() == 1;
        return new Term(label, dates.get(0), open ? null : dates.get(1));
    }

    private static String birth(String v) {
        if (v == null) {
            return null;
        }
        Matcher m = Pattern.compile("^(\\d{2})-(\\d{2})-(\\d{4})$").matcher(v.trim());
        return m.matches() ? m.group(3) + "-" + m.group(2) + "-" + m.group(1) : null;
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** Texte d'un enfant direct, {@code null} s'il est absent ou vide. */
    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    static String scope(Map<String, String> p) {
        String v = p.get("scope");
        if (v == null || v.isBlank()) {
            return "parliament";
        }
        if (!SCOPES.contains(v.trim())) {
            throw new IllegalArgumentException("Paramètre « scope » invalide : « " + v + " » (valeurs : " + SCOPES + ").");
        }
        return v.trim();
    }

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static String prefix(String scope) {
        return "dk-pep-" + scope;
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

    private static String apiUrl(Map<String, String> p) {
        String v = p.get("apiUrl");
        return v == null || v.isBlank() ? "https://oda.ft.dk/api" : v.trim();
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
