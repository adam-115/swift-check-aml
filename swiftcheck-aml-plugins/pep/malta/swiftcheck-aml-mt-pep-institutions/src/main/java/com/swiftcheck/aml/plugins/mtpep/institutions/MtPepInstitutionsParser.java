package com.swiftcheck.aml.plugins.mtpep.institutions;

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
 * <b>mt_pep_institutions</b> – président de la République et Auditeur général (avec son adjoint) de Malte, personnes politiquement
 * exposées au sens de la réglementation maltaise sur la prévention du blanchiment de capitaux (PMLFTR).
 *
 * <p>Sources officielles (robots.txt sans restriction, 1 seconde entre les appels, User-Agent générique) :
 * <ul>
 *   <li>{@code president.gov.mt/biography/} : « H.E. Myriam Spiteri Debono – The President of Malta » ;</li>
 *   <li>{@code nao.gov.mt/about-us/senior-officials/} : « Auditor General – Mr Charles Deguara » et « Deputy Auditor General – Mr Noel
 *       Camilleri » (les deux auditeurs généraux adjoints ne sont pas repris).</li>
 * </ul>
 * Ni naissance ni dates de fonction sur ces pages. Non lus : le gouvernement (gov.mt) et la Banque centrale (centralbankmalta.org)
 * sont protégés par une vérification de navigateur Cloudflare, non contournée ; pmo.gov.mt et la Cour constitutionnelle sont
 * injoignables.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"presidentUrl": "https://president.gov.mt/biography/",
 *  "auditUrl": "https://nao.gov.mt/about-us/senior-officials/"}
 * }</pre>
 */
@Extension
public class MtPepInstitutionsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "mt-pep-institutions";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_PRESIDENT_URL = "https://president.gov.mt/biography/";
    static final String DEFAULT_AUDIT_URL = "https://nao.gov.mt/about-us/senior-officials/";
    static final String PRESIDENT_ROLE = "President of Malta";
    static final String AUDITOR_ROLE = "Auditor General of Malta";
    static final String DEPUTY_ROLE = "Deputy Auditor General of Malta";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Malte - institutions)";
    private static final long DELAY_MS = 1000;
    /** Un mot de nom propre, hors civilités (Mr, Ms, Mrs, Dr) et article : le nom s'arrête avant « Mr Charles Deguara, B.A. ». */
    private static final String WORD = "(?!(?:Mr|Ms|Mrs|Dr|The)\\b)\\p{Lu}[\\p{L}'’]+";
    private static final String NAME = WORD + "(?: " + WORD + "){1,3}";
    private static final Pattern PRESIDENT = Pattern.compile("H\\.E\\. (" + NAME + ") The President of Malta");
    private static final Pattern AUDITOR = Pattern.compile("(?<!Deputy )(?<!Assistant )Auditor General\\s*[–-]\\s*(?:Mr|Ms|Mrs|Dr)\\.? (" + NAME + ")");
    private static final Pattern DEPUTY = Pattern.compile("Deputy Auditor General\\s*[–-]\\s*(?:Mr|Ms|Mrs|Dr)\\.? (" + NAME + ")");

    /** Un titulaire : nom et fonction. */
    record Holder(String name, String role) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(), param(p, "presidentUrl", DEFAULT_PRESIDENT_URL), param(p, "auditUrl", DEFAULT_AUDIT_URL), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), param(p, "presidentUrl", DEFAULT_PRESIDENT_URL), param(p, "auditUrl", DEFAULT_AUDIT_URL), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), DEFAULT_PRESIDENT_URL, DEFAULT_AUDIT_URL, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    static void download(Fetcher fetcher, String presidentUrl, String auditUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Files.writeString(rawDir.resolve("president.html"), fetcher.get(presidentUrl), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("senior.html"), fetcher.get(auditUrl), StandardCharsets.UTF_8);
    }

    /** Lit les deux pages de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, String presidentUrl, String auditUrl, FtmDatasetBuilder dataset) throws IOException {
        Path president = rawDir.resolve("president.html");
        Path senior = rawDir.resolve("senior.html");
        if (!Files.exists(president) || !Files.exists(senior)) {
            throw new IllegalStateException("president.html ou senior.html absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Holder> all = new ArrayList<>();
        all.add(president(Files.readString(president, StandardCharsets.UTF_8)));
        all.addAll(auditors(Files.readString(senior, StandardCharsets.UTF_8)));

        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        for (Holder h : all) {
            String url = h.role().equals(PRESIDENT_ROLE) ? presidentUrl : auditUrl;
            FtmEntity person = dataset.newEntity("Person", h.name());
            person.addValue("name", h.name());
            for (String alias : aliases(h.name())) {
                person.addValue("alias", alias);
            }
            person.addValue("country", "mt");
            person.addValue("nationality", "mt");
            person.addValue("position", h.role());
            person.addValue("sourceUrl", url);
            dataset.addEntity(person);

            FtmEntity position = positions.get(h.role());
            if (position == null) {
                position = dataset.newEntity("Position", h.role());
                position.addValue("name", h.role());
                position.addValue("country", "mt");
                position.addValue("topics", "gov.national");
                if (h.role().equals(PRESIDENT_ROLE)) {
                    position.addValue("organization", "Office of the President of Malta");
                    position.addValue("topics", "gov.executive");
                    position.addValue("topics", "gov.head");
                } else {
                    position.addValue("organization", "National Audit Office of Malta");
                    position.addValue("topics", "gov.financial");
                }
                dataset.addEntity(position);
                positions.put(h.role(), position);
            }
            FtmEntity occupancy = dataset.newEntity("Occupancy", h.name(), h.role());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("sourceUrl", url);
            dataset.addEntity(occupancy);
        }
        return all.size() + " personnes : le président de la République, l'Auditeur général et son adjoint. "
                + "Source : president.gov.mt et nao.gov.mt.";
    }

    /** Le président : « H.E. Myriam Spiteri Debono The President of Malta ». */
    static Holder president(String html) {
        Matcher m = PRESIDENT.matcher(text(html));
        if (!m.find()) {
            throw new IllegalStateException("Page « Biography » du président : nom introuvable (la page a changé).");
        }
        return new Holder(m.group(1), PRESIDENT_ROLE);
    }

    /** L'Auditeur général et son adjoint : « Auditor General – Mr Charles Deguara ». */
    static List<Holder> auditors(String html) {
        String text = text(html);
        List<Holder> out = new ArrayList<>();
        Matcher a = AUDITOR.matcher(text);
        if (!a.find()) {
            throw new IllegalStateException("Page « Senior Officials » : Auditeur général introuvable (la page a changé).");
        }
        out.add(new Holder(once(a.group(1)), AUDITOR_ROLE));
        Matcher d = DEPUTY.matcher(text);
        if (d.find()) {
            out.add(new Holder(once(d.group(1)), DEPUTY_ROLE));
        }
        return out;
    }

    /** « Noel Camilleri Noel Camilleri » (nom suivi de la phrase qui le répète) → « Noel Camilleri ». */
    static String once(String name) {
        String[] w = name.split(" ");
        if (w.length % 2 == 0) {
            String first = String.join(" ", java.util.Arrays.copyOfRange(w, 0, w.length / 2));
            String second = String.join(" ", java.util.Arrays.copyOfRange(w, w.length / 2, w.length));
            if (first.equals(second)) {
                return first;
            }
        }
        return name;
    }

    /** « Charles Deguara » → « Deguara Charles ». */
    static Set<String> aliases(String name) {
        String[] words = name.split(" ");
        Set<String> out = new LinkedHashSet<>();
        out.add(words[words.length - 1] + " " + String.join(" ", java.util.Arrays.copyOfRange(words, 0, words.length - 1)));
        out.remove(name);
        return out;
    }

    /** Texte d'une page : scripts et balises retirés, entités courantes décodées, tout sur une ligne. */
    static String text(String html) {
        return html.replaceAll("(?s)<!--.*?-->", " ").replaceAll("(?s)<script.*?</script>", " ").replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&#8211;", "–").replace("&#8217;", "’").replace("&amp;", "&")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
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
