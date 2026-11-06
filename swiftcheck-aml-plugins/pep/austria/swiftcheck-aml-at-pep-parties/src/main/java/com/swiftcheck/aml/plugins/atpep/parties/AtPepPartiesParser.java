package com.swiftcheck.aml.plugins.atpep.parties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugins.atpep.parties.PartyRegister.Entry;
import com.swiftcheck.aml.plugins.atpep.parties.PartyRegister.Party;
import com.swiftcheck.aml.plugins.atpep.parties.PartyRegister.Person;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>at_pep_parties</b> – organes dirigeants des partis représentés au Nationalrat (catégorie <b>AT-c</b> ; FM-GwG § 2 Z 6
 * lit. c ; liste notifiée, JO C/2023/724).
 *
 * <p>Source : le « Parteienregisterauszug » du ministère fédéral de l'Intérieur (PDF, mis à jour en continu), téléchargé à
 * chaque exécution. Les partis lus sont désignés par leur numéro de registre (paramètre {@code parties}, par défaut ÖVP
 * 500004, SPÖ 500002, FPÖ 500008, Grüne 500106, NEOS 500940). La loi oblige les partis à déclarer leurs représentants,
 * mais certains inscrits indiquent « Nicht bekannt gegeben » : le message d'exécution les cite comme non déclarés, sans
 * rien inventer. Titres académiques non repris.
 *
 * <p>Paramètres : {@code pdfUrl}, {@code parties} (« 500004=ÖVP;500002=SPÖ »).
 */
@Extension
public class AtPepPartiesParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "at-pep-parties";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_PDF_URL =
            "https://citizen.bmi.gv.at/at.gv.bmi.fnsweb-p/par/public/api/ParteienGesamtverzeichnis.pdf";
    static final String DEFAULT_PARTIES = "500004=ÖVP;500002=SPÖ;500008=FPÖ;500106=GRÜNE;500940=NEOS";
    static final String PDF_FILE = "parteienregister.pdf";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Autriche - registre des partis)";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        if (ctx.rawDir() == null) {
            throw new IllegalStateException("Aucun dossier de données brutes.");
        }
        Files.createDirectories(ctx.rawDir());
        Path pdf = ctx.rawDir().resolve(PDF_FILE);
        download(value(p, "pdfUrl", DEFAULT_PDF_URL), pdf);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(pdf, parties(value(p, "parties", DEFAULT_PARTIES)), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    static List<Party> parties(String spec) {
        List<Party> out = new ArrayList<>();
        for (String item : spec.split(";")) {
            String[] kv = item.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().matches("5\\d{5}")) {
                out.add(new Party(kv[0].trim(), kv[1].trim()));
            } else if (!item.isBlank()) {
                throw new IllegalArgumentException("Paramètre « parties » invalide : « " + item + " » (attendu : 500004=ÖVP).");
            }
        }
        return out;
    }

    static String text(Path pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    /** Lit le PDF et écrit les entités ; retourne le message d'exécution. */
    static String convert(Path pdf, List<Party> parties, FtmDatasetBuilder dataset) throws IOException {
        List<Entry> entries = PartyRegister.read(text(pdf), parties);
        int people = 0;
        List<String> undeclared = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.found()) {
                missing.add(e.party().label() + " (" + e.party().number() + ")");
                continue;
            }
            if (e.people().isEmpty()) {
                undeclared.add(e.party().label());
                continue;
            }
            FtmEntity position = dataset.newEntity("Position", e.party().number(), "leadership");
            position.addValue("name", "Organe dirigeant du parti " + e.party().label());
            position.addValue("country", "at");
            position.addValue("organization", e.registeredName());
            position.addValue("topics", "gov.national");
            dataset.addEntity(position);
            for (Person person : e.people()) {
                FtmEntity entity = dataset.newEntity("Person", e.party().number(), person.name());
                entity.addValue("name", person.name());
                entity.addValue("firstName", person.firstName());
                entity.addValue("lastName", person.lastName());
                entity.addValue("country", "at");
                entity.addValue("position", person.role() + " – " + e.party().label());
                entity.addValue("sourceUrl", DEFAULT_PDF_URL);
                dataset.addEntity(entity);
                FtmEntity occupancy = dataset.newEntity("Occupancy", e.party().number(), person.name(), person.role());
                occupancy.addValue("holder", entity.getId());
                occupancy.addValue("post", position.getId());
                occupancy.addValue("status", "current");
                occupancy.addValue("description", person.role());
                occupancy.addValue("sourceUrl", DEFAULT_PDF_URL);
                dataset.addEntity(occupancy);
                people++;
            }
        }
        if (people == 0) {
            throw new IllegalStateException("Aucun représentant lu dans le registre des partis (non déclarés : "
                    + undeclared + " ; absents : " + missing + ") : le PDF a changé ou l'adresse est fausse.");
        }
        StringBuilder m = new StringBuilder(people + " représentants lus pour " + (entries.size() - undeclared.size()
                - missing.size()) + " parti(s).");
        if (!undeclared.isEmpty()) {
            m.append(" Représentants non déclarés au registre : ").append(String.join(", ", undeclared)).append('.');
        }
        if (!missing.isEmpty()) {
            m.append(" Partis introuvables dans le registre : ").append(String.join(", ", missing)).append('.');
        }
        return m.toString();
    }

    static void download(String url, Path target) throws IOException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        try {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " pour " + url);
            }
            try (InputStream in = response.body()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu", e);
        }
    }

    private static String value(Map<String, String> p, String key, String def) {
        String v = p.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    /**
     * Test du parser : relance la lecture de la source vers des dossiers temporaires (la source archivée n'est pas
     * touchée) et renvoie chaque personne avec l'ID et le nom que Yente doit connaître.
     */
    @Override
    public java.util.List<com.swiftcheck.aml.plugin.SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("src-records");
        try {
            java.nio.file.Path out = tmp.resolve("out.ndjson");
            java.nio.file.Path raw = java.nio.file.Files.createDirectories(tmp.resolve("raw"));
            parse(new ParserContext(ctx.datasetName(), ctx.inputFile(), raw, out, ctx.parameters()));
            java.util.regex.Pattern str = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
            java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("^\\{\"id\":\"((?:[^\"\\\\]|\\\\.)*)\"");
            java.util.List<com.swiftcheck.aml.plugin.SourceRecord> records = new java.util.ArrayList<>();
            for (String line : java.nio.file.Files.readAllLines(out, java.nio.charset.StandardCharsets.UTF_8)) {
                if (!line.contains("\"schema\":\"Person\"")) {
                    continue;
                }
                java.util.regex.Matcher id = idPattern.matcher(line);
                int n = line.indexOf("\"name\":[");
                if (!id.find() || n < 0) {
                    continue;
                }
                java.util.regex.Matcher name = str.matcher(line.substring(n + 7));
                if (name.find()) {
                    records.add(new com.swiftcheck.aml.plugin.SourceRecord(unescape(id.group(1)), "Person",
                            unescape(name.group(1)), null));
                }
            }
            return records;
        } finally {
            try (var walk = java.nio.file.Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t");
    }
}
