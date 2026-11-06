package com.swiftcheck.aml.plugins.nopep.government;

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
import java.util.ArrayList;
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
 * <b>no_pep_government</b> – Premier ministre et ministres du gouvernement norvégien.
 *
 * <p>Source : l'API ouverte du Storting {@code https://data.stortinget.no/eksport/regjering} (XML, sans clé), qui publie les
 * membres du gouvernement en fonction avec identifiant, nom, naissance, genre, département, titre et parti. Le site
 * regjeringen.no n'est pas utilisé (il est protégé par un contrôle anti-robot). L'API ne donne ni l'historique des
 * gouvernements ni les secrétaires d'État. Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"apiUrl": "https://data.stortinget.no/eksport", "delayMs": "500"}
 * }</pre>
 */
@Extension
public class NoPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "no-pep-government";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String FILE = "regjering.xml";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Norvege - gouvernement)";

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Un membre du gouvernement. */
    record Member(String id, String firstName, String lastName, String sex, String birthDate, String deathDate,
            String department, String party, String title) {

        String fullName() {
            return (firstName + " " + lastName).trim();
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(delayMs(p)), apiUrl(p), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int n = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(n + " membres du gouvernement.");
    }

    /** Test du parser : chaque membre, avec l'ID et le nom que Yente doit connaître (sans réseau). */
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

    static void download(Fetcher fetcher, String apiUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        Files.writeString(rawDir.resolve(FILE), fetcher.get(base + "/regjering"), StandardCharsets.UTF_8);
    }

    /** Lit {@code regjering.xml} et écrit les membres. Séparé de {@link #parse} pour les tests. */
    int convert(Path rawDir, FtmDatasetBuilder dataset) throws Exception {
        Path file = rawDir.resolve(FILE);
        if (!Files.exists(file)) {
            throw new IllegalStateException(FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Member> members = members(Files.readString(file, StandardCharsets.UTF_8));
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre du gouvernement dans " + FILE + " : le format de l'API a peut-être changé.");
        }
        int written = 0;
        for (Member m : members) {
            if (m.deathDate() != null || m.id() == null) {
                continue;
            }
            String role = role(m);
            FtmEntity person = dataset.newEntity("Person", m.id());
            person.addValue("name", m.fullName());
            person.addValue("firstName", m.firstName());
            person.addValue("lastName", m.lastName());
            person.addValue("gender", "mann".equals(m.sex()) ? "male" : "kvinne".equals(m.sex()) ? "female" : null);
            person.addValue("birthDate", m.birthDate());
            person.addValue("country", "no");
            person.addValue("position", role);
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", "regjeringen", role);
            position.addValue("name", role);
            position.addValue("country", "no");
            position.addValue("organization", m.department() == null ? "Regjeringen" : m.department());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.executive");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", m.id(), "regjeringen", role);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", m.party() == null ? "Gouvernement en fonction" : "Gouvernement en fonction ; parti : " + m.party());
            dataset.addEntity(occupancy);
            written++;
        }
        return written;
    }

    private static String role(Member m) {
        String title = m.title() == null ? "Membre du gouvernement" : m.title();
        return m.department() == null || title.equals("Statsminister") ? title + " (Norvège)" : title + " – " + m.department();
    }

    /** Les membres du fichier {@code regjering}. */
    static List<Member> members(String xml) {
        List<Member> out = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList list = doc.getElementsByTagName("regjeringsmedlem");
            for (int i = 0; i < list.getLength(); i++) {
                Element e = (Element) list.item(i);
                Element party = child(e, "parti");
                String birth = text(e, "foedselsdato");
                String death = text(e, "doedsdato");
                out.add(new Member(text(e, "id"), text(e, "fornavn"), text(e, "etternavn"), text(e, "kjoenn"),
                        birth == null ? null : birth.substring(0, 10), death == null ? null : death.substring(0, 10),
                        text(e, "departement"), party == null ? null : text(party, "navn"), text(e, "tittel")));
            }
        } catch (Exception e) {
            throw new IllegalStateException("regjering illisible : " + e.getMessage(), e);
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

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        if (e == null || "true".equals(e.getAttribute("i:nil"))) {
            return null;
        }
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    private static String apiUrl(Map<String, String> p) {
        String v = p.get("apiUrl");
        return v == null || v.isBlank() ? "https://data.stortinget.no/eksport" : v.trim();
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
