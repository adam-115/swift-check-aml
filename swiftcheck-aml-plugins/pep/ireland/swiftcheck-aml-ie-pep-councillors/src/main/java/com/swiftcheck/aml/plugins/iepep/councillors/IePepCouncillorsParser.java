package com.swiftcheck.aml.plugins.iepep.councillors;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>ie_pep_councillors</b> – conseillers des conseils locaux irlandais dont le conseil publie la liste de ses élus en open
 * data (data.gov.ie / Smart Dublin) : à ce jour Dublin City Council et Dún Laoghaire-Rathdown County Council.
 * Couche étendue, hors de la liste de l'article 37 du Criminal Justice Act 2010 : à activer selon l'approche par les risques.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Le plugin télécharge chaque CSV déclaré dans le paramètre {@code sources}
 * ({@code "Nom du conseil|https://….csv;Autre conseil|https://….csv"}). Les colonnes sont reconnues par leur en-tête :
 * {@code Name} (précédé ou non de « Cllr »), {@code Party}, et la circonscription ({@code LEA}, {@code Local electoral area}
 * ou {@code Ward}). L'encodage est détecté (UTF-8, sinon Windows-1252).
 *
 * <p>Un conseil qui ne publie pas sa liste n'est pas couvert : c'est dit dans le message de l'exécution.
 */
@Extension
public class IePepCouncillorsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ie-pep-councillors";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Irlande - conseils locaux)";
    private static final Pattern TITLE = Pattern.compile("(?i)^\\s*(?:Cllr\\.?|Councillor|Clr\\.?)\\s+");

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    /** Un conseiller lu. */
    record Councillor(String council, String name, String party, String area) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        Map<String, String> sources = sources(p.get("sources"));
        download(httpFetcher(), sources, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), sources, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque conseiller, avec l'ID et le nom que Yente doit connaître (sans réseau). */
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

    /** « Conseil|adresse;Conseil|adresse » → conseil → adresse, dans l'ordre. */
    static Map<String, String> sources(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            throw new IllegalStateException("Paramètre « sources » manquant dans la configuration du parser "
                    + "(« Nom du conseil|https://….csv;Autre conseil|https://….csv », voir le README du plugin).");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String item : parameter.split(";")) {
            String[] parts = item.split("\\|", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalStateException("Source invalide dans « sources » : « " + item + " » (attendu : nom|adresse).");
            }
            out.put(parts[0].trim(), parts[1].trim());
        }
        return out;
    }

    static void download(Fetcher fetcher, Map<String, String> sources, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        int i = 0;
        for (String url : sources.values()) {
            Files.write(rawDir.resolve(fileName(i++)), fetcher.get(url));
        }
    }

    private static String fileName(int index) {
        return "council-" + index + ".csv";
    }

    /** Lit les CSV de {@code rawDir} et écrit les conseillers ; retourne le message de bilan. */
    String convert(Path rawDir, Map<String, String> sources, FtmDatasetBuilder dataset) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int i = 0;
        for (String council : sources.keySet()) {
            Path file = rawDir.resolve(fileName(i++));
            if (!Files.exists(file)) {
                throw new IllegalStateException(file.getFileName() + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
            List<Councillor> list = read(council, decode(Files.readAllBytes(file)));
            if (list.isEmpty()) {
                throw new IllegalStateException("Aucun conseiller lu dans la liste de « " + council + " » (colonne « Name » attendue) : "
                        + "le format du fichier a changé.");
            }
            list.forEach(c -> write(c, dataset));
            counts.put(council, list.size());
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        return String.format("%d conseillers de %d conseils : %s. Les autres conseils irlandais ne publient pas leur liste en open data.",
                total, counts.size(), counts);
    }

    /** UTF-8 si le fichier est valide, sinon Windows-1252 (cas du fichier de Dublin City Council). */
    static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    /** Colonnes reconnues par leur en-tête ; une ligne par conseiller. */
    static List<Councillor> read(String council, String csv) {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = rows.get(0);
        for (int c = 0; c < header.size(); c++) {
            columns.put(header.get(c).trim().toLowerCase(Locale.ROOT), c);
        }
        Integer name = columns.get("name");
        Integer party = columns.get("party");
        Integer area = firstOf(columns, "lea", "local electoral area", "ward");
        List<Councillor> out = new ArrayList<>();
        if (name == null) {
            return out;
        }
        for (List<String> row : rows.subList(1, rows.size())) {
            String full = cell(row, name);
            if (full == null) {
                continue;
            }
            String clean = TITLE.matcher(full).replaceFirst("").replaceAll("\\s+", " ").trim();
            if (!clean.isEmpty()) {
                out.add(new Councillor(council, clean, cell(row, party), cell(row, area)));
            }
        }
        return out;
    }

    private static Integer firstOf(Map<String, Integer> columns, String... names) {
        for (String n : names) {
            if (columns.containsKey(n)) {
                return columns.get(n);
            }
        }
        return null;
    }

    private static String cell(List<String> row, Integer index) {
        if (index == null || index >= row.size()) {
            return null;
        }
        String v = row.get(index).trim();
        return v.isEmpty() ? null : v;
    }

    /** CSV minimal : virgule, guillemets doublés, retours à la ligne dans les champs entre guillemets. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cur.toString());
                cur.setLength(0);
                if (row.stream().anyMatch(s -> !s.isBlank())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cur.append(c);
            }
        }
        row.add(cur.toString());
        if (row.stream().anyMatch(s -> !s.isBlank())) {
            rows.add(row);
        }
        return rows;
    }

    private static void write(Councillor c, FtmDatasetBuilder dataset) {
        String label = "Councillor, " + c.council();
        String[] words = c.name().split("\\s+");

        // ID = conseil + nom : les listes ne donnent pas d'identifiant
        FtmEntity person = dataset.newEntity("Person", c.council(), c.name());
        person.addValue("name", c.name());
        person.addValue("firstName", words[0]);
        person.addValue("lastName", words.length > 1 ? c.name().substring(c.name().indexOf(' ') + 1) : null);
        person.addValue("country", "ie");
        person.addValue("position", label);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", label);
        position.addValue("name", label);
        position.addValue("country", "ie");
        position.addValue("organization", c.council());
        position.addValue("topics", "gov.muni");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", c.council(), c.name(), "councillor");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        List<String> parts = new ArrayList<>();
        if (c.area() != null) {
            parts.add("Circonscription : " + c.area());
        }
        if (c.party() != null) {
            parts.add("Parti : " + c.party());
        }
        occupancy.addValue("description", String.join(" ; ", parts));
        dataset.addEntity(occupancy);
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
