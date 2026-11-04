package com.swiftcheck.aml.plugins.depep.stateenterprises;

import java.io.InputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_state_enterprises</b> – Membres des directoires et conseils de surveillance des entreprises publiques
 * allemandes (Beteiligungsbericht des Bundes et des Länder).
 * <p>
 * Catégorie <b>DE-h</b> (§ 1 (12) h) GwG ; liste JO C/2023/724).
 * Couvre les entreprises d'État au-delà des seuils légaux (participation publique &gt; 50%, &gt; 2 000 salariés ou &gt; 3 Md€ de bilan).
 */
@Extension
public class DePepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepStateEnterprisesParser.class);

    static final String ID_PREFIX = "de-pep-state-enterprises";
    static final String TOPIC_PEP = "role.pep";
    static final String TOPIC_SOE = "gov.soe";
    static final String CONFIG_RESOURCE = "/state-enterprises.json";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    private static final Pattern LINE_ROLE_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:[-*•]\\s*)?((?:Vorstandsvorsitzende[rn]?|Aufsichtsratsvorsitzende[rn]?|Verwaltungsratsvorsitzende[rn]?|Vorsitzende[rn]?\\s+der\\s+Geschäftsführung|Mitglied\\s+des\\s+Vorstands|Mitglied\\s+des\\s+Aufsichtsrats|Geschäftsführer(?:in)?|CEO))\\s*[:\\-]\\s*(.+)$");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GovernanceMember(String role, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnterpriseConfig(String key, String name, String sector, String shareholder, String url,
                                   List<GovernanceMember> governance) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<EnterpriseConfig> enterprises) {
    }

    record Director(String companyKey, String companyName, String role,
                    String fullName, String title, String cleanName, String sourceUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<Director> allDirectors = new ArrayList<>();

        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String content = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            allDirectors.addAll(parseFileContent(content, config));
            FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC_PEP);
            convert(allDirectors, dataset);
            dataset.writeNdjsonFile(ctx.outputFile());
            return dataset.toConversionResult().withMessage(
                    allDirectors.size() + " dirigeants d'entreprises publiques allemandes extraits.");
        }

        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        boolean allowCrawl = !"false".equalsIgnoreCase(p.get("crawl"));

        if (ctx.rawDir() != null) {
            Files.createDirectories(ctx.rawDir());
            if (config.enterprises() != null) {
                for (EnterpriseConfig ec : config.enterprises()) {
                    Path entFile = ctx.rawDir().resolve(ec.key() + ".html");
                    String content = null;
                    if (Files.isRegularFile(entFile)) {
                        content = Files.readString(entFile, StandardCharsets.UTF_8);
                    } else if (allowCrawl && ec.url() != null && !ec.url().isBlank()) {
                        content = crawl(ec.url(), "SwiftCheck-AML/1.0 (liste PEP Allemagne - Entreprises publiques)");
                        if (content != null && !content.isBlank()) {
                            Files.writeString(entFile, content, StandardCharsets.UTF_8);
                        }
                    }

                    if (content != null) {
                        allDirectors.addAll(parseFileContent(content, config));
                    }

                    if (ec.governance() != null) {
                        for (GovernanceMember gm : ec.governance()) {
                            final String name = gm.name();
                            boolean exists = allDirectors.stream().anyMatch(d -> d.fullName().equalsIgnoreCase(name));
                            if (!exists) {
                                addDirector(allDirectors, ec.key(), ec.name(), gm.role(), gm.name(), ec.url());
                            }
                        }
                    }
                }
            }

            Path rawConfigFile = ctx.rawDir().resolve("state-enterprises.json");
            if (!Files.exists(rawConfigFile)) {
                try (InputStream in = DePepStateEnterprisesParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
                    if (in != null) {
                        Files.copy(in, rawConfigFile);
                    }
                }
            }
        }

        if (allDirectors.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant d'entreprise publique extrait.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC_PEP);
        convert(allDirectors, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                allDirectors.size() + " dirigeants d'entreprises publiques allemandes extraits.");
    }

    static String crawl(String url, String userAgent) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                return resp.body();
            }
        } catch (Exception e) {
            log.warn("Échec du crawl de {} : {}", url, e.getMessage());
        }
        return null;
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<Director> directors = new ArrayList<>();
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            directors.addAll(parseFileContent(Files.readString(ctx.inputFile(), StandardCharsets.UTF_8), config));
        }
        if (directors.isEmpty() && config.enterprises() != null) {
            for (EnterpriseConfig ec : config.enterprises()) {
                if (ec.governance() != null) {
                    for (GovernanceMember gm : ec.governance()) {
                        addDirector(directors, ec.key(), ec.name(), gm.role(), gm.name(), ec.url());
                    }
                }
            }
        }
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (Director d : directors) {
            records.add(new SourceRecord(
                    ids.buildEntityId(d.companyKey(), d.cleanName()),
                    "Person",
                    d.fullName(),
                    d.companyName() + " – " + d.role()
            ));
        }
        return records;
    }

    static Config loadConfig(ParserContext ctx) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String configFile = ctx.parameters().get("configFile");
        if (configFile != null && !configFile.isBlank()) {
            Path p = Path.of(configFile);
            if (Files.isRegularFile(p)) {
                return mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), Config.class);
            }
        }
        try (InputStream in = DePepStateEnterprisesParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return mapper.readValue(in, Config.class);
        }
    }

    static List<Director> parseFileContent(String content, Config config) {
        List<Director> list = new ArrayList<>();
        String currentCompany = null;
        String currentCompanyKey = null;

        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.toLowerCase().startsWith("unternehmen:") || trimmed.toLowerCase().startsWith("gesellschaft:")) {
                currentCompany = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                currentCompanyKey = currentCompany.replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase();
                continue;
            }

            Matcher m = LINE_ROLE_PATTERN.matcher(trimmed);
            if (m.find()) {
                String role = m.group(1).trim();
                String names = m.group(2).trim();
                String comp = currentCompany != null ? currentCompany : "Unternehmen";
                String compKey = currentCompanyKey != null ? currentCompanyKey : "ent";
                for (String single : names.split("[,;]|\\bund\\b")) {
                    String name = single.trim();
                    if (!name.isEmpty()) {
                        addDirector(list, compKey, comp, role, name, null);
                    }
                }
            }
        }
        return list;
    }

    private static void addDirector(List<Director> list, String compKey, String compName, String role, String rawName, String sourceUrl) {
        String title = null;
        String cleanName = rawName;
        Matcher tm = ACADEMIC_TITLE.matcher(rawName);
        if (tm.find()) {
            title = tm.group(0).trim();
            cleanName = rawName.substring(tm.end()).trim();
        }
        list.add(new Director(compKey, compName, role, rawName, title, cleanName, sourceUrl));
    }

    private void convert(List<Director> directors, FtmDatasetBuilder dataset) {
        for (Director d : directors) {
            // Création de l'entreprise si pas déjà présente
            FtmEntity company = dataset.newEntity("Company", d.companyKey());
            company.addValue("name", d.companyName());
            company.addValue("jurisdiction", "de");
            company.addValue("topics", TOPIC_SOE);
            dataset.addEntity(company);

            String posKey = d.companyKey() + "-dir";
            FtmEntity position = dataset.newEntity("Position", posKey);
            position.addValue("name", d.role() + " (" + d.companyName() + ")");
            position.addValue("country", "de");
            position.addValue("organization", d.companyName());
            position.addValue("topics", "gov.national");
            position.addValue("topics", TOPIC_SOE);
            dataset.addEntity(position);

            FtmEntity person = dataset.newEntity("Person", d.companyKey(), d.cleanName());
            person.addValue("name", d.cleanName());
            person.addValue("title", d.title());
            String[] parts = d.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            person.addValue("position", d.role() + " (" + d.companyName() + ")");
            person.addValue("topics", TOPIC_SOE);
            if (d.sourceUrl() != null) {
                person.addValue("sourceUrl", d.sourceUrl());
            }
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", d.companyKey(), d.cleanName(), "dir");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", d.role());
            dataset.addEntity(occ);
        }
    }
}

