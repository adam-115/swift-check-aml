package com.swiftcheck.aml.plugins.fipep.parliament;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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
 * <b>fi_pep_parliament</b> et <b>fi_pep_government</b> – députés de l'Eduskunta et ministres finlandais, personnes
 * politiquement exposées au sens de la loi finlandaise sur la prévention du blanchiment (laki rahanpesun ja terrorismin
 * rahoittamisen estämisestä).
 *
 * <p>Source officielle : l'API ouverte de l'Eduskunta
 * {@code https://avoindata.eduskunta.fi/api/v1/tables/MemberOfParliament/rows} (JSON paginé, sans clé). Chaque ligne est un
 * député, présent ou passé depuis 1907, avec une fiche XML : naissance (année seulement), décès, sexe, périodes de mandat
 * ({@code Edustajatoimet}), groupes parlementaires et appartenance au gouvernement ({@code ValtioneuvostonJasenyydet} :
 * fonction, nom du gouvernement, dates exactes). Les ministres qui n'ont jamais été députés n'y figurent pas ; le site
 * valtioneuvosto.fi est protégé par un contrôle anti-robot et n'est pas utilisé.
 *
 * <p>Le paramètre {@code scope} choisit la couche : {@code parliament} (députés) ou {@code government} (ministres).
 * {@code monthsAfterEnd} fixe la fenêtre après la fin des fonctions (12 mois par défaut, 600 pour l'historique). Les personnes
 * décédées sont ignorées. Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"apiUrl": "https://avoindata.eduskunta.fi/api/v1", "scope": "parliament", "monthsAfterEnd": "12", "delayMs": "500"}
 * }</pre>
 */
@Extension
public class FiPepParliamentParser implements Iparser {

    /** Préfixes des identifiants FtM : ne jamais les changer, les alertes y sont rattachées. */
    static final String ID_PREFIX_PARLIAMENT = "fi-pep-parliament";
    static final String ID_PREFIX_GOVERNMENT = "fi-pep-government";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String PAGE_PREFIX = "members-";
    static final int PAGE_SIZE = 100;
    static final String SEAT = "Député à l'Eduskunta (kansanedustaja)";

    private static final DateTimeFormatter FI_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Finlande - Eduskunta)";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Une période de fonction (mandat de député ou fonction ministérielle). */
    record Term(String title, String government, LocalDate start, LocalDate end) {
    }

    /** Un député de l'API. */
    record Member(String id, String firstNames, String callName, String lastName, String sex, String birthYear, String deathDate,
            String party, List<Term> seats, List<Term> ministries) {

        /** Nom usuel : prénom d'appel et nom de famille (« Ben Zyskowicz »). */
        String displayName() {
            String first = callName != null && !callName.isBlank() ? callName : firstNames;
            return (first + " " + lastName).trim();
        }

        String fullName() {
            return (firstNames + " " + lastName).trim();
        }
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int read, int dead, int kept) {

        String message(String scope) {
            return String.format("%d fiches lues, %d personnes décédées écartées, %d personnes gardées (%s).", read, dead, kept, scope);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String scope = scope(p);
        download(httpFetcher(delayMs(p)), apiUrl(p), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(prefix(scope), TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), scope, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message(scope));
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

    /** Télécharge toutes les pages de la table des députés. */
    static void download(Fetcher fetcher, String apiUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        for (int page = 0; page < 1000; page++) {
            String body = fetcher.get(base + "/tables/MemberOfParliament/rows?perPage=" + PAGE_SIZE + "&page=" + page);
            Files.writeString(rawDir.resolve(PAGE_PREFIX + String.format("%03d", page) + ".json"), body, StandardCharsets.UTF_8);
            if (!MAPPER.readTree(body).path("hasMore").asBoolean(false)) {
                return;
            }
        }
        throw new IllegalStateException("Plus de 1000 pages de députés : arrêt de sécurité.");
    }

    /** Lit les pages de {@code rawDir} et écrit la couche demandée. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, String scope, FtmDatasetBuilder dataset) throws Exception {
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
        for (Path page : pages) {
            for (Member m : members(Files.readString(page, StandardCharsets.UTF_8))) {
                read++;
                if (m.deathDate() != null) {
                    dead++;
                    continue;
                }
                List<Term> terms = "government".equals(scope) ? m.ministries() : m.seats();
                if (terms.stream().noneMatch(t -> t.end() == null || !t.end().isBefore(cutoff))) {
                    continue;
                }
                write(m, terms, scope, dataset);
                kept++;
            }
        }
        if (read == 0) {
            throw new IllegalStateException("Aucun député lu dans " + rawDir + " : le format de l'API a peut-être changé.");
        }
        return new Report(read, dead, kept);
    }

    private static void write(Member m, List<Term> terms, String scope, FtmDatasetBuilder dataset) {
        boolean government = "government".equals(scope);
        FtmEntity person = dataset.newEntity("Person", m.id());
        person.addValue("name", m.displayName());
        if (!m.fullName().equals(m.displayName())) {
            person.addValue("alias", m.fullName());
        }
        person.addValue("firstName", m.firstNames());
        person.addValue("lastName", m.lastName());
        person.addValue("gender", "Mies".equals(m.sex()) ? "male" : "Nainen".equals(m.sex()) ? "female" : null);
        person.addValue("birthDate", m.birthYear());
        person.addValue("country", "fi");
        for (String role : new TreeSet<>(terms.stream().map(t -> role(t, government)).toList())) {
            person.addValue("position", role);
        }
        dataset.addEntity(person);

        for (Term t : terms) {
            String role = role(t, government);
            FtmEntity position = dataset.newEntity("Position", government ? "valtioneuvosto" : "eduskunta", role);
            position.addValue("name", role);
            position.addValue("country", "fi");
            position.addValue("organization", government ? "Valtioneuvosto (gouvernement)" : "Eduskunta");
            position.addValue("topics", "gov.national");
            position.addValue("topics", government ? "gov.executive" : "gov.legislative");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", m.id(), government ? "valtioneuvosto" : "eduskunta", role,
                    String.valueOf(t.start()));
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", t.start() == null ? null : t.start().toString());
            occupancy.addValue("endDate", t.end() == null ? null : t.end().toString());
            occupancy.addValue("status", t.end() == null ? "current" : "ended");
            List<String> parts = new ArrayList<>();
            if (t.government() != null) {
                parts.add("Gouvernement : " + t.government());
            }
            if (!government && m.party() != null) {
                parts.add("Groupe parlementaire : " + m.party());
            }
            if (!parts.isEmpty()) {
                occupancy.addValue("description", String.join(" ; ", parts));
            }
            dataset.addEntity(occupancy);
        }
    }

    private static String role(Term t, boolean government) {
        if (!government) {
            return SEAT;
        }
        String title = t.title() == null ? "ministre" : t.title().trim();
        if (title.equalsIgnoreCase("pääministeri")) {
            return "Premier ministre (pääministeri)";
        }
        if (title.toLowerCase().startsWith("pääministerin sijainen")) {
            return "Suppléant du Premier ministre (pääministerin sijainen)";
        }
        return "Ministre : " + title;
    }

    /** Les députés d'une page JSON de l'API. */
    static List<Member> members(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        List<String> columns = new ArrayList<>();
        root.path("columnNames").forEach(c -> columns.add(c.asText()));
        int xmlFi = columns.indexOf("XmlDataFi");
        int xml = columns.indexOf("XmlData");
        int id = columns.indexOf("personId");
        if (id < 0 || (xmlFi < 0 && xml < 0)) {
            throw new IllegalStateException("Colonnes inattendues dans la réponse de l'API : " + columns);
        }
        List<Member> out = new ArrayList<>();
        for (JsonNode row : root.path("rowData")) {
            String data = xmlFi >= 0 && !row.get(xmlFi).isNull() ? row.get(xmlFi).asText() : xml >= 0 && !row.get(xml).isNull() ? row.get(xml).asText() : null;
            if (data == null || data.isBlank()) {
                continue;
            }
            out.add(member(row.get(id).asText(), data));
        }
        return out;
    }

    private static Member member(String id, String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();
            List<Term> seats = new ArrayList<>();
            Element seatsEl = child(root, "Edustajatoimet");
            if (seatsEl != null) {
                for (Element e : children(seatsEl, "Edustajatoimi")) {
                    LocalDate start = date(text(e, "AlkuPvm"));
                    if (start != null) {
                        seats.add(new Term(null, null, start, date(text(e, "LoppuPvm"))));
                    }
                }
            }
            List<Term> ministries = new ArrayList<>();
            Element govEl = child(root, "ValtioneuvostonJasenyydet");
            if (govEl != null) {
                for (Element e : children(govEl, "Jasenyys")) {
                    LocalDate start = date(text(e, "AlkuPvm"));
                    String title = text(e, "Nimi");
                    String kind = text(e, "Ministeriys");
                    if (start != null && (title != null || kind != null)) {
                        ministries.add(new Term(title != null ? title : kind, text(e, "Hallitus"), start, date(text(e, "LoppuPvm"))));
                    }
                }
            }
            return new Member(id, text(root, "EtunimetNimi"), text(root, "KutsumaNimi"), text(root, "SukuNimi"),
                    text(root, "SukuPuoliKoodi"), year(text(root, "SyntymaPvm")), text(root, "KuolemaPvm"), party(root), seats,
                    ministries);
        } catch (Exception e) {
            throw new IllegalStateException("Fiche du député " + id + " illisible : " + e.getMessage(), e);
        }
    }

    /** Le groupe parlementaire actuel, sinon le dernier groupe connu. */
    private static String party(Element root) {
        Element groups = child(root, "Eduskuntaryhmat");
        if (groups == null) {
            return null;
        }
        Element current = child(groups, "NykyinenEduskuntaryhma");
        String name = current == null ? null : text(current, "Nimi");
        if (name != null) {
            return name;
        }
        Element former = child(groups, "EdellisetEduskuntaryhmat");
        String last = null;
        if (former != null) {
            for (Element g : children(former, "Eduskuntaryhma")) {
                String n = text(g, "Nimi");
                if (n != null) {
                    last = n;
                }
            }
        }
        return last;
    }

    private static LocalDate date(String v) {
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v.trim(), FI_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static String year(String v) {
        return v != null && v.trim().matches("\\d{4}") ? v.trim() : null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element e && e.getTagName().equals(name)) {
                out.add(e);
            }
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
        if (v == null || v.isBlank() || v.trim().equals("parliament")) {
            return "parliament";
        }
        if (v.trim().equals("government")) {
            return "government";
        }
        throw new IllegalArgumentException("Paramètre « scope » invalide : « " + v + " » (parliament ou government attendu).");
    }

    static String prefix(String scope) {
        return "government".equals(scope) ? ID_PREFIX_GOVERNMENT : ID_PREFIX_PARLIAMENT;
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
        return v == null || v.isBlank() ? "https://avoindata.eduskunta.fi/api/v1" : v.trim();
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
