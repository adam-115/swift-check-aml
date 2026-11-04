package com.swiftcheck.aml.plugins.espep.bankofspain;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>es_pep_bank_of_spain</b> – gouverneur, sous-gouverneur, Conseil de gouvernement et Commission exécutive de la
 * Banque d'Espagne (bloc ES-d ; loi 10/2010, art. 14.2 : membres des conseils des banques centrales ; JO C/2023/724).
 *
 * <p>Source : les pages « Consejo de Gobierno » et « Comisión Ejecutiva » de bde.es. Sous « Composición », chaque
 * membre est une fiche « nom puis fonction » (gouverneur, sous-gouverneure, consejero, secrétaire générale du Trésor,
 * vice-présidente de la CNMV). Les directeurs généraux, qui assistent sans voix délibérative, ne sont pas repris. Les
 * photos des fiches sont une photo de groupe : elles ne sont pas reprises. Type {@code PLUGIN_MANAGED}. Paramètres
 * (facultatifs) :
 * <pre>{@code
 * {"councilUrl": "https://www.bde.es/wbe/es/sobre-banco/organizacion/organos-rectores/consejo-gobierno/",
 *  "executiveUrl": "https://www.bde.es/wbe/es/sobre-banco/organizacion/organos-rectores/comision-ejecutiva/"}
 * }</pre>
 * Une personne qui sort du Conseil disparaît de la page : le core la garde pendant la durée de rétention du pays.
 */
@Extension
public class EsPepBankOfSpainParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepBankOfSpainParser.class);

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "es-pep-bank-of-spain";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_COUNCIL_URL = "https://www.bde.es/wbe/es/sobre-banco/organizacion/organos-rectores/consejo-gobierno/";
    static final String DEFAULT_EXECUTIVE_URL = "https://www.bde.es/wbe/es/sobre-banco/organizacion/organos-rectores/comision-ejecutiva/";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Espagne - Banque d'Espagne)";
    static final String COUNCIL_FILE = "consejo-gobierno.html";
    static final String EXECUTIVE_FILE = "comision-ejecutiva.html";

    private static final String ORGANIZATION = "Banco de España";
    private static final Pattern ROLE = Pattern.compile(
            "(?iu)^(Gobernador|Gobernadora|Subgobernador|Subgobernadora|Consejer[oa]|Vicepresident[ea]|"
                    + "Secretari[oa] General del Tesoro.*)$");
    private static final Pattern NAME = Pattern.compile("(?u)^\\p{Lu}[\\p{L}'.\\-]+(?:\\s+(?:de|del|la|los|las|y|\\p{Lu}[\\p{L}'.\\-]+)){1,5}$");

    /** Un membre, avec les organes où il siège. */
    record Member(String name, String role, boolean council, boolean executive) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(p.getOrDefault("councilUrl", DEFAULT_COUNCIL_URL).trim(),
                p.getOrDefault("executiveUrl", DEFAULT_EXECUTIVE_URL).trim(), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int members = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(members + " membres des organes de direction de la Banque d'Espagne.");
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

    static void download(String councilUrl, String executiveUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Files.write(rawDir.resolve(COUNCIL_FILE), get(client, councilUrl));
        Thread.sleep(500);
        Files.write(rawDir.resolve(EXECUTIVE_FILE), get(client, executiveUrl));
    }

    private static byte[] get(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " pour " + url);
        }
        return response.body();
    }

    int convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        List<Member> council = members(Files.readString(rawDir.resolve(COUNCIL_FILE), StandardCharsets.UTF_8));
        List<Member> executive = members(Files.readString(rawDir.resolve(EXECUTIVE_FILE), StandardCharsets.UTF_8));
        if (council.stream().noneMatch(m -> m.role().toLowerCase(Locale.ROOT).startsWith("gobernador"))) {
            throw new IllegalStateException("Le gouverneur est introuvable sur la page du Conseil de gouvernement : "
                    + "la page de bde.es a probablement changé.");
        }
        Map<String, Member> byKey = new LinkedHashMap<>();
        for (Member m : council) {
            byKey.put(key(m.name()), new Member(m.name(), m.role(), true, false));
        }
        for (Member m : executive) {
            byKey.merge(key(m.name()), new Member(m.name(), m.role(), false, true),
                    (a, b) -> new Member(a.name(), a.role(), a.council(), true));
        }
        for (Member m : byKey.values()) {
            write(m, dataset);
        }
        log.info("{} membres des organes de direction de la Banque d'Espagne", byKey.size());
        return byKey.size();
    }

    /** Les fiches « nom puis fonction » qui suivent le titre « Composición » dans le contenu de la page. */
    static List<Member> members(String html) {
        String content = html;
        int composition = content.indexOf("Composici");
        if (composition >= 0) {
            content = content.substring(composition);
        }
        List<String> lines = new ArrayList<>();
        for (String raw : content.replaceAll("(?s)<script.*?</script>", " ").replaceAll("<[^>]*>", "\n").split("\n")) {
            String line = decode(raw).replaceAll("\\s+", " ").trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        List<Member> members = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("Asisten") || line.startsWith("Departamento")) {
                break;
            }
            Matcher role = ROLE.matcher(lines.get(i + 1));
            if (NAME.matcher(line).matches() && role.matches()) {
                String r = lines.get(i + 1);
                if (r.toLowerCase(Locale.ROOT).startsWith("vicepresident") && i + 2 < lines.size()
                        && lines.get(i + 2).equals("CNMV")) {
                    r = r + " de la CNMV";
                }
                members.add(new Member(line, r, false, false));
                i++;
            }
        }
        return members;
    }

    private static void write(Member m, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", key(m.name()));
        person.addValue("name", m.name());
        person.addValue("country", "es");
        person.addValue("nationality", "es");
        person.addValue("sourceUrl", m.council() ? DEFAULT_COUNCIL_URL : DEFAULT_EXECUTIVE_URL);
        List<String> posts = new ArrayList<>();
        String lower = m.role().toLowerCase(Locale.ROOT);
        if (lower.startsWith("gobernador")) {
            posts.add("Gobernador del Banco de España");
        } else if (lower.startsWith("subgobernador")) {
            posts.add("Subgobernador del Banco de España");
        }
        if (m.council()) {
            posts.add("Miembro del Consejo de Gobierno del Banco de España");
        }
        if (m.executive()) {
            posts.add("Miembro de la Comisión Ejecutiva del Banco de España");
        }
        for (String post : posts) {
            person.addValue("position", post);
            FtmEntity position = dataset.newEntity("Position", key(post));
            position.addValue("name", post);
            position.addValue("country", "es");
            position.addValue("organization", ORGANIZATION);
            position.addValue("topics", "gov.financial");
            position.addValue("topics", "gov.national");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", key(m.name()), key(post));
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            occupancy.addValue("description", m.role());
            occupancy.addValue("sourceUrl", m.council() ? DEFAULT_COUNCIL_URL : DEFAULT_EXECUTIVE_URL);
            dataset.addEntity(occupancy);
        }
        dataset.addEntity(person);
    }

    static String key(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String decode(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&aacute;", "á").replace("&eacute;", "é")
                .replace("&iacute;", "í").replace("&oacute;", "ó").replace("&uacute;", "ú").replace("&ntilde;", "ñ")
                .replace("&Aacute;", "Á").replace("&Eacute;", "É").replace("&Iacute;", "Í").replace("&Oacute;", "Ó")
                .replace("&Uacute;", "Ú").replace("&Ntilde;", "Ñ").replace("&uuml;", "ü").replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
