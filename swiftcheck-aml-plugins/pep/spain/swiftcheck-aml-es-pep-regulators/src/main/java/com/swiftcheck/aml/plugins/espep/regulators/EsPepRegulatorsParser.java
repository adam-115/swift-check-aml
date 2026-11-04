package com.swiftcheck.aml.plugins.espep.regulators;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>es_pep_regulators</b> – présidents et membres des conseils des organismes régulateurs de l'État (bloc ES-f ;
 * loi 10/2010, art. 14.3 a) et loi 3/2015, art. 1 ; JO C/2023/724).
 *
 * <p>Source : une page officielle par organisme, décrite dans {@code regulators.json} (livré dans le JAR) : adresse et
 * expression à groupes nommés {@code role} et {@code name}. Au 03/11/2026 seul le Conseil de sécurité nucléaire (CSN)
 * a une page exploitable ; les autres organismes sont fermés aux clients automatiques (CNMV : 403, non contourné),
 * sans noms (CNMC) ou en erreur (AEPD), et leurs nominations viennent du BOE ({@code es_pep_boe}). Type
 * {@code PLUGIN_MANAGED}. Paramètre facultatif : {@code institutions}, les clés à lire (toutes par défaut).
 */
@Extension
public class EsPepRegulatorsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepRegulatorsParser.class);

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "es-pep-regulators";
    static final String TOPIC = "role.pep";
    static final String CONFIG = "/regulators.json";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Espagne - organismes regulateurs)";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Un organisme à lire. */
    record Institution(String key, String organization, String url, Pattern pattern, List<String> topics) {
    }

    /** Un membre trouvé sur la page d'un organisme. */
    record Member(String name, String role) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        List<Institution> institutions = institutions(ctx.parameters());
        List<String> failed = download(institutions, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int members = convert(institutions, ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        String message = members + " membres lus dans " + institutions.size() + " organisme(s).";
        if (!failed.isEmpty()) {
            message += " Pages non lues : " + String.join(", ", failed) + ".";
        }
        return dataset.toConversionResult().withMessage(message);
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(institutions(ctx.parameters()), ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    static List<Institution> institutions(Map<String, String> parameters) throws IOException {
        Set<String> wanted = parameters == null || parameters.getOrDefault("institutions", "").isBlank() ? null
                : Set.of(parameters.get("institutions").trim().split("[\\s,]+"));
        List<Institution> list = new ArrayList<>();
        try (InputStream in = EsPepRegulatorsParser.class.getResourceAsStream(CONFIG)) {
            for (JsonNode i : JSON.readTree(in).path("institutions")) {
                if (wanted != null && !wanted.contains(i.path("key").asText())) {
                    continue;
                }
                List<String> topics = new ArrayList<>();
                i.path("topics").forEach(t -> topics.add(t.asText()));
                list.add(new Institution(i.path("key").asText(), i.path("organization").asText(), i.path("url").asText(),
                        Pattern.compile(i.path("pattern").asText(), Pattern.DOTALL), topics));
            }
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun organisme à lire : vérifiez le paramètre « institutions ».");
        }
        return list;
    }

    /** Enregistre la page de chaque organisme sous {@code <clé>.html} ; renvoie les clés des pages non lues. */
    static List<String> download(List<Institution> institutions, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        List<String> failed = new ArrayList<>();
        for (Institution i : institutions) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(i.url())).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                Files.write(rawDir.resolve(i.key() + ".html"), response.body());
            } else {
                log.warn("{} : HTTP {}", i.url(), response.statusCode());
                failed.add(i.key() + " (HTTP " + response.statusCode() + ")");
            }
            Thread.sleep(500);
        }
        return failed;
    }

    int convert(List<Institution> institutions, Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        int total = 0;
        for (Institution i : institutions) {
            Path file = rawDir.resolve(i.key() + ".html");
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException(file.getFileName() + " absent de " + rawDir + " : page non téléchargée.");
            }
            List<Member> members = members(Files.readString(file, StandardCharsets.UTF_8), i);
            if (members.isEmpty()) {
                throw new IllegalStateException("Aucun membre trouvé sur " + i.url() + " : la page de " + i.organization()
                        + " a probablement changé.");
            }
            for (Member m : members) {
                total++;
                write(m, i, persons, dataset);
            }
        }
        persons.values().forEach(dataset::addEntity);
        log.info("{} membres d'organismes régulateurs", total);
        return total;
    }

    /** Les couples fonction/nom de la page ; les doublons, dont le nom est parfois abrégé, sont réunis. */
    static List<Member> members(String html, Institution institution) {
        List<Member> list = new ArrayList<>();
        Matcher m = institution.pattern().matcher(html);
        while (m.find()) {
            String name = clean(m.group("name"));
            String role = clean(m.group("role"));
            if (name.isEmpty() || role.isEmpty()) {
                continue;
            }
            String key = key(name);
            boolean merged = false;
            for (int k = 0; k < list.size(); k++) {
                String other = key(list.get(k).name());
                if (other.equals(key) || other.startsWith(key + " ") || key.startsWith(other + " ")) {
                    if (name.length() > list.get(k).name().length()) {
                        list.set(k, new Member(name, list.get(k).role()));
                    }
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                list.add(new Member(name, role));
            }
        }
        return list;
    }

    private static void write(Member m, Institution i, Map<String, FtmEntity> persons, FtmDatasetBuilder dataset) {
        String label = m.role() + " – " + i.organization();
        FtmEntity person = persons.computeIfAbsent(key(m.name()), k -> {
            FtmEntity e = dataset.newEntity("Person", k);
            e.addValue("name", m.name());
            e.addValue("country", "es");
            e.addValue("nationality", "es");
            e.addValue("sourceUrl", i.url());
            return e;
        });
        person.addValue("position", label);

        FtmEntity position = dataset.newEntity("Position", key(label));
        position.addValue("name", label);
        position.addValue("country", "es");
        position.addValue("organization", i.organization());
        i.topics().forEach(t -> position.addValue("topics", t));
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", key(m.name()), key(label));
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", m.role());
        occupancy.addValue("sourceUrl", i.url());
        dataset.addEntity(occupancy);
    }

    private static String clean(String s) {
        return s.replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    static String key(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }
}
