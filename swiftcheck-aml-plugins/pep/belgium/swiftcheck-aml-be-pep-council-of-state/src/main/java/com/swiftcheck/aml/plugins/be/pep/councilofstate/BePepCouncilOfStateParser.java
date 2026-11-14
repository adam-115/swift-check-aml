package com.swiftcheck.aml.plugins.be.pep.councilofstate;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.be.pep.councilofstate.CouncilOfStateDocuments.Member;
import com.swiftcheck.aml.plugins.be.pep.councilofstate.CouncilOfStateDocuments.Members;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser PEP Belgique – Conseil d'État (raadvst-consetat.be).
 * <p>
 * Sources, toutes officielles et relues à chaque exécution :
 * <ol>
 *   <li>la page « Direction et gestion » : premier président, présidente, auditeur général et adjoint, noms complets ;</li>
 *   <li>la composition des chambres francophones et bilingue ({@code chambres.pdf}) et néerlandophones
 *       ({@code kamers.pdf}) : présidents de chambre, conseillers d'État, assesseurs de la section de législation ;</li>
 *   <li>la composition de l'auditorat francophone ({@code auditfr.pdf}) et néerlandophone ({@code auditnl.pdf}).</li>
 * </ol>
 * Les adresses des PDF changent à chaque mise à jour du site : elles sont relues sur les pages « Le Conseil » et
 * « L'auditorat », avec en repli les dernières adresses connues. Les greffiers, le personnel administratif et les
 * postes vacants sont écartés. Il n'y a pas de copie embarquée : un document injoignable est signalé dans le message
 * d'exécution, et la rétention garde les personnes déjà publiées.
 */
@Extension
public class BePepCouncilOfStateParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepCouncilOfStateParser.class);

    static final String BASE_URL = "https://www.raadvst-consetat.be";
    static final String DEFAULT_URL = BASE_URL + "/fr/le-conseil-detat/organisation/direction-et-gestion/";
    static final String COUNCIL_PAGE_URL = BASE_URL + "/fr/le-conseil-detat/organisation/le-conseil/";
    static final String AUDIT_PAGE_URL = BASE_URL + "/fr/le-conseil-detat/organisation/lauditorat/";
    static final String FILE_NAME = "council-of-state-direction.html";

    static final String ID_PREFIX = "be-pep-council-of-state";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";
    static final String PHOTO_LICENCE = "© Conseil d'État de Belgique";

    /** Les quatre PDF de composition : nom du fichier sur le site, page qui le cite, dernière adresse connue. */
    enum Composition {
        CHAMBERS_FR("chambres.pdf", COUNCIL_PAGE_URL, BASE_URL + "/media/5ecflwih/chambres.pdf", "chambres francophones",
                CouncilOfStateDocuments::parseChambers),
        CHAMBERS_NL("kamers.pdf", COUNCIL_PAGE_URL, BASE_URL + "/media/wzencebo/kamers.pdf", "chambres néerlandophones",
                CouncilOfStateDocuments::parseChambers),
        AUDIT_FR("auditfr.pdf", AUDIT_PAGE_URL, BASE_URL + "/media/ktni0t5j/auditfr.pdf", "auditorat francophone",
                CouncilOfStateDocuments::parseAuditFr),
        AUDIT_NL("auditnl.pdf", AUDIT_PAGE_URL, BASE_URL + "/media/qspbr1rv/auditnl.pdf", "auditorat néerlandophone",
                CouncilOfStateDocuments::parseAuditNl);

        final String fileName;
        final String page;
        final String knownUrl;
        final String label;
        final Function<String, List<Member>> reader;

        Composition(String fileName, String page, String knownUrl, String label, Function<String, List<Member>> reader) {
            this.fileName = fileName;
            this.page = page;
            this.knownUrl = knownUrl;
            this.label = label;
            this.reader = reader;
        }
    }

    private static final Pattern PERSON_CARD_PATTERN = Pattern.compile(
            "(?:<img[^>]+src=\"([^\"]+)\"[^>]*>[\\s\\S]{0,400}?)?"
            + "<h4[^>]*class=\"[^\"]*o-person__name[^\"]*\"[^>]*>([\\s\\S]*?)</h4>\\s*"
            + "<p[^>]*class=\"[^\"]*o-person__function[^\"]*\"[^>]*>([\\s\\S]*?)</p>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        List<String> missing = new ArrayList<>();

        try {
            Files.writeString(ctx.rawDir().resolve(FILE_NAME), getText(DEFAULT_URL), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[be_pep_council_of_state] Page de direction injoignable : {}", e.getMessage());
            missing.add("page de direction (" + e.getMessage() + ")");
        }

        Map<String, String> pages = new LinkedHashMap<>();
        for (Composition c : Composition.values()) {
            String url = c.knownUrl;
            try {
                String page = pages.computeIfAbsent(c.page, p -> {
                    try {
                        return getText(p);
                    } catch (Exception e) {
                        log.warn("[be_pep_council_of_state] Page {} injoignable : {}", p, e.getMessage());
                        return "";
                    }
                });
                url = findPdfLink(page, c.fileName, c.knownUrl);
                Files.write(ctx.rawDir().resolve(c.fileName), getPdf(url));
            } catch (Exception e) {
                log.warn("[be_pep_council_of_state] {} injoignable ({}) : {}", c.label, url, e.getMessage());
                missing.add(c.label + " (" + e.getMessage() + ")");
            }
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        String message = report.summary();
        if (!missing.isEmpty()) {
            message += " Documents non lus : " + String.join(" ; ", missing) + ".";
        }
        return dataset.toConversionResult().withMessage(message);
    }

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

    /** Nombre de personnes lues par document et résultat de la fusion. */
    record Report(Map<String, Integer> perDocument, List<Member> members) {
        String summary() {
            Map<String, Integer> byFunction = new LinkedHashMap<>();
            for (Member m : members) {
                byFunction.merge(m.function(), 1, Integer::sum);
            }
            StringBuilder s = new StringBuilder(members.size() + " magistrats du Conseil d'État extraits (");
            s.append(String.join(", ", byFunction.entrySet().stream().map(e -> e.getKey() + " : " + e.getValue()).toList()));
            s.append("). Mentions lues : ");
            s.append(String.join(", ", perDocument.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList()));
            return s.append('.').toString();
        }
    }

    /** Lit les documents présents dans {@code rawDir}, fusionne les personnes et écrit les entités. */
    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Members members = new Members();
        Map<String, Integer> perDocument = new LinkedHashMap<>();

        Path htmlFile = rawDir.resolve(FILE_NAME);
        if (Files.isRegularFile(htmlFile)) {
            int n = 0;
            for (DirectionCard card : parseDirectionHtml(Files.readString(htmlFile, StandardCharsets.UTF_8))) {
                members.add(card.member(), card.photoUrl());
                n++;
            }
            perDocument.put("direction", n);
        }
        for (Composition c : Composition.values()) {
            Path pdf = rawDir.resolve(c.fileName);
            if (!Files.isRegularFile(pdf)) {
                continue;
            }
            List<Member> found = c.reader.apply(pdfText(pdf));
            found.forEach(members::add);
            perDocument.put(c.label, found.size());
        }

        List<Member> list = members.list();
        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun magistrat du Conseil d'État extrait depuis " + rawDir);
        }
        for (Member m : list) {
            writeRecord(m, members.aliasesOf(m), members.photoOf(m), dataset);
        }
        return new Report(perDocument, list);
    }

    record DirectionCard(Member member, String photoUrl) {}

    /** Fiches de la page « Direction et gestion », limitées aux fonctions juridictionnelles. */
    static List<DirectionCard> parseDirectionHtml(String html) {
        List<DirectionCard> cards = new ArrayList<>();
        if (html == null) {
            return cards;
        }
        Matcher m = PERSON_CARD_PATTERN.matcher(html);
        while (m.find()) {
            String name = cleanText(m.group(2));
            String function = directionFunction(cleanText(m.group(3)));
            if (function == null) {
                continue; // greffier en chef, administrateur, directeurs, poste vacant « X »
            }
            String[] parts = CouncilOfStateDocuments.firstNameThenCapitals(name);
            if (parts == null) {
                continue;
            }
            cards.add(new DirectionCard(new Member(parts[0], parts[1], function), absolutize(m.group(1))));
        }
        return cards;
    }

    static String directionFunction(String raw) {
        String f = CouncilOfStateDocuments.stripAccents(raw).toLowerCase(Locale.ROOT).trim();
        if (f.startsWith("premier president")) {
            return CouncilOfStateDocuments.PREMIER_PRESIDENT;
        }
        if (f.equals("president") || f.equals("presidente")) {
            return CouncilOfStateDocuments.PRESIDENT;
        }
        if (f.startsWith("auditeur general adjoint")) {
            return CouncilOfStateDocuments.AUDITEUR_GENERAL_ADJOINT;
        }
        if (f.startsWith("auditeur general")) {
            return CouncilOfStateDocuments.AUDITEUR_GENERAL;
        }
        return null;
    }

    /** Adresse du PDF {@code fileName} citée dans la page, sinon la dernière adresse connue. */
    static String findPdfLink(String pageHtml, String fileName, String knownUrl) {
        Matcher m = Pattern.compile("href=\"([^\"]*/" + Pattern.quote(fileName) + ")\"", Pattern.CASE_INSENSITIVE)
                .matcher(pageHtml == null ? "" : pageHtml);
        return m.find() ? absolutize(m.group(1)) : knownUrl;
    }

    static void writeRecord(Member m, List<String> aliases, String photoUrl, FtmDatasetBuilder dataset) {
        String position = m.function() + " du Conseil d'État de Belgique";

        FtmEntity person = dataset.newEntity("Person", "member", m.displayName());
        person.addValue("name", m.displayName());
        person.addValue("firstName", m.firstName());
        person.addValue("lastName", m.lastName());
        aliases.forEach(a -> person.addValue("alias", a));
        person.addValue("country", COUNTRY);
        person.addValue("position", position);
        person.addValue("sourceUrl", DEFAULT_URL);
        dataset.addEntity(person);

        if (photoUrl != null && !photoUrl.isBlank()) {
            dataset.addMedia(person.getId(), photoUrl, DEFAULT_URL, PHOTO_LICENCE);
        }

        FtmEntity post = dataset.newEntity("Position", position);
        post.addValue("name", position);
        post.addValue("country", COUNTRY);
        post.addValue("organization", "Conseil d'État de Belgique");
        post.addValue("topics", "gov.national");
        post.addValue("topics", "gov.judicial");
        dataset.addEntity(post);

        FtmEntity occ = dataset.newEntity("Occupancy", m.displayName(), position);
        occ.addValue("holder", person.getId());
        occ.addValue("post", post.getId());
        occ.addValue("status", "current");
        occ.addValue("description", position);
        occ.addValue("sourceUrl", DEFAULT_URL);
        dataset.addEntity(occ);
    }

    static String pdfText(Path pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String getText(String url) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
            throw new IOException("HTTP " + resp.statusCode() + " sur " + url);
        }
        return resp.body();
    }

    private byte[] getPdf(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> resp = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = resp.body();
        if (resp.statusCode() != 200 || body == null || body.length < 1000
                || !new String(body, 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF")) {
            throw new IOException("HTTP " + resp.statusCode() + " ou contenu non PDF sur " + url);
        }
        return body;
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - Conseil d'Etat)")
                .GET().build();
    }

    static String cleanText(String s) {
        if (s == null) {
            return "";
        }
        String text = s.replaceAll("<[^>]*>", " ");
        Matcher m = NUMERIC_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String absolutize(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return null;
        }
        String clean = pathOrUrl.replace("&amp;", "&").trim();
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return clean;
        }
        return clean.startsWith("/") ? BASE_URL + clean : BASE_URL + "/" + clean;
    }
}
