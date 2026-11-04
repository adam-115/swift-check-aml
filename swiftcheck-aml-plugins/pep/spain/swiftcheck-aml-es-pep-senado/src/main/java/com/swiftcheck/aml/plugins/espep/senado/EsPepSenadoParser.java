package com.swiftcheck.aml.plugins.espep.senado;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
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
 * <b>es_pep_senado</b> – Sénateurs de la XVe législature du Sénat espagnol (bloc ES-b ; Ley 10/2010, art. 14.2 ; JO C/2023/724).
 *
 * <p>Source officielle directe : portail officiel du Sénat ({@code www.senado.es/.../senadoresdesde1977/consultaorden/index.html}).
 * La liste officielle de l'ensemble des sénateurs de la XVe législature (élus au suffrage direct et désignés par les parlements
 * des communautés autonomes) est téléchargée avec leur identifiant parlementaire, groupe politique et circonscription.
 */
@Extension
public class EsPepSenadoParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepSenadoParser.class);

    /** Préfixe des identifiants FtM. */
    static final String ID_PREFIX = "es-pep-senado";
    static final String TOPIC = "role.pep";
    static final String SENADO_POST_URL = "https://www.senado.es/web/composicionorganizacion/senadores/composicionsenado/senadoresdesde1977/consultaorden/index.html";
    static final String SENADO_FICHA_URL = "https://www.senado.es/web/composicionorganizacion/senadores/composicionsenado/fichasenador/index.html?legis=15&id1=";
    static final String HTML_FILE = "senadores-legis15.html";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final String POSITION_NAME = "Senador (Sénateur)";

    record Senator(String id, String rawName, String firstName, String lastName, String fullName,
                   String groupCode, String groupName, String mandate, String sourceUrl) {}

    record Report(int count) {
        String message() {
            return String.format("%d sénateurs de la XVe législature collectés directement auprès du Sénat d'Espagne.", count);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        download(ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
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

    // ------------------------------------------------------------------ Téléchargement

    static void download(Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Path target = rawDir.resolve(HTML_FILE);
        if (Files.isRegularFile(target) && Files.size(target) > 50_000) {
            return;
        }

        // Essayer via node si disponible (contourne l'incompatibilité JA3/TLS d'Akamai Edge)
        boolean downloaded = false;
        try {
            String script = "async function run() {"
                    + "  const form = new URLSearchParams({ legis: '15', idcom: 'A', boton_obtener_listado: 'OBTENER LISTADO' });"
                    + "  const res = await fetch('" + SENADO_POST_URL + "', {"
                    + "    method: 'POST',"
                    + "    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },"
                    + "    body: form.toString()"
                    + "  });"
                    + "  const buf = Buffer.from(await res.arrayBuffer());"
                    + "  process.stdout.write(buf);"
                    + "}"
                    + "run().catch(e => { console.error(e); process.exit(1); });";

            ProcessBuilder pb = new ProcessBuilder("node", "-e", script);
            Process p = pb.start();
            Path tmp = rawDir.resolve("download.tmp");
            try (InputStream in = p.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            int code = p.waitFor();
            if (code == 0 && Files.size(tmp) > 50_000) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                downloaded = true;
                log.info("Sénat téléchargé avec succès via node ({} octets).", Files.size(target));
            } else {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            log.debug("Exécution de node non disponible : {}", e.getMessage());
        }

        if (!downloaded) {
            // Téléchargement direct standard Java HttpClient
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            String body = "legis=15&idcom=A&boton_obtener_listado=OBTENER+LISTADO";
            HttpRequest request = HttpRequest.newBuilder(URI.create(SENADO_POST_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200 && response.body().length > 50_000) {
                Files.write(target, response.body());
                downloaded = true;
            } else {
                throw new IOException("Impossible de télécharger la composition du Sénat depuis " + SENADO_POST_URL
                        + " (HTTP " + response.statusCode() + ").");
            }
        }
    }

    // ------------------------------------------------------------------ Conversion

    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(HTML_FILE);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(HTML_FILE + " absent de " + rawDir);
        }

        List<Senator> senators = readHtml(file);
        if (senators.isEmpty()) {
            throw new IllegalStateException("Aucun sénateur trouvé dans " + HTML_FILE);
        }

        FtmEntity position = dataset.newEntity("Position", "senador");
        position.addValue("name", "Senador");
        position.addValue("organization", "Senado de España");
        position.addValue("country", "es");
        position.addValue("topics", "gov.legislative");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        for (Senator s : senators) {
            FtmEntity person = persons.computeIfAbsent(s.id(), k -> newPerson(s, dataset));
            person.addValue("position", POSITION_NAME);

            FtmEntity occupancy = dataset.newEntity("Occupancy", s.id(), "15");
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            if (s.mandate() != null && !s.mandate().isBlank()) {
                occupancy.addValue("description", s.mandate());
            }
            occupancy.addValue("sourceUrl", s.sourceUrl());
            dataset.addEntity(occupancy);
        }

        persons.values().forEach(dataset::addEntity);
        Report report = new Report(senators.size());
        log.info(report.message());
        return report;
    }

    static List<Senator> readHtml(Path file) throws IOException {
        String html = Files.readString(file, StandardCharsets.UTF_8);
        List<Senator> list = new ArrayList<>();

        Pattern regex = Pattern.compile(
                "<a title=\"Acceso a la ficha de ([^\"]+)\" href=\"([^\"]*id1=(\\d+)[^\"]*)\">[\\s\\S]*?<abbr title=\"([^\"]*)\">([^<]*)</abbr>[\\s\\S]*?<span class=\"col-3\">\\s*([^<]+)\\s*</span>",
                Pattern.CASE_INSENSITIVE
        );

        Matcher m = regex.matcher(html);
        while (m.find()) {
            String rawName = m.group(1).trim();
            String id = m.group(3).trim();
            String groupName = m.group(4).trim();
            String groupCode = m.group(5).trim();
            String mandate = m.group(6).replaceAll("\\s+", " ").trim();

            String[] parts = rawName.split(",", 2);
            String last = parts[0].trim();
            String first = parts.length > 1 ? parts[1].trim() : null;
            String fullName = first == null ? last : (first + " " + last).trim();

            String sourceUrl = SENADO_FICHA_URL + id;
            list.add(new Senator(id, rawName, first, last, fullName, groupCode, groupName, mandate, sourceUrl));
        }
        return list;
    }

    private static FtmEntity newPerson(Senator s, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", s.id());
        person.addValue("name", s.fullName());
        if (s.firstName() != null) {
            person.addValue("firstName", s.firstName());
        }
        if (s.lastName() != null) {
            person.addValue("lastName", s.lastName());
        }
        person.addValue("country", "es");
        person.addValue("nationality", "es");
        if (s.groupCode() != null && !s.groupCode().isBlank()) {
            person.addValue("political", s.groupName() != null && !s.groupName().isBlank()
                    ? s.groupCode() + " - " + s.groupName() : s.groupCode());
        }
        person.addValue("sourceUrl", s.sourceUrl());
        return person;
    }

    static String personKey(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }
}
