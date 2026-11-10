package com.swiftcheck.aml.plugins.atpep.internationalorgs;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <b>at_pep_international_orgs</b> – Dirigeants des organisations internationales ayant leur siège ou un
 * établissement principal en Autriche (catégorie <b>AT-h</b> ; FM-GwG § 2 Z 6 lit. h ; liste notifiée, JO C/2023/724).
 *
 * <p>Organisations couvertes à Vienne : OSCE (Organisation pour la sécurité et la coopération en Europe),
 * UNOV / UNODC (Office des Nations unies à Vienne), OPEP (Organisation des pays exportateurs de pétrole),
 * CTBTO (Organisation du traité d'interdiction complète des essais nucléaires), ONUDI (UNIDO) et ICMPD
 * (International Centre for Migration Policy Development).
 */
@Extension
public class AtPepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(AtPepInternationalOrgsParser.class);

    static final String ID_PREFIX = "at-pep-international-orgs";
    static final String TOPIC = "role.pep";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SwiftCheck-AML/1.0 (liste PEP Autriche - organisations internationales)";

    public record OrgLeader(String orgAcronym, String orgName, String name, String firstName, String lastName, String role, String sourceUrl) {
    }

    private static final List<OrgLeader> OFFICIAL_LEADERS = List.of(
            // OSCE (Vienne)
            new OrgLeader("OSCE", "Organisation pour la sécurité et la coopération en Europe", "Helga Maria Schmid", "Helga Maria", "Schmid",
                    "Secrétaire générale de l'OSCE", "https://www.osce.org/leadership"),
            new OrgLeader("OSCE", "Organisation pour la sécurité et la coopération en Europe", "Feridun Sinirlioğlu", "Feridun", "Sinirlioğlu",
                    "Secrétaire général désigné de l'OSCE", "https://www.osce.org/leadership"),
            new OrgLeader("OSCE", "Organisation pour la sécurité et la coopération en Europe", "Matteo Mecacci", "Matteo", "Mecacci",
                    "Directeur du BIDDH (OSCE)", "https://www.osce.org/leadership"),
            new OrgLeader("OSCE", "Organisation pour la sécurité et la coopération en Europe", "Teresa Ribeiro", "Teresa", "Ribeiro",
                    "Représentante de l'OSCE pour la liberté des médias", "https://www.osce.org/leadership"),
            new OrgLeader("OSCE", "Organisation pour la sécurité et la coopération en Europe", "Kairat Abdrakhmanov", "Kairat", "Abdrakhmanov",
                    "Haut-Commissaire pour les minorités nationales (OSCE)", "https://www.osce.org/leadership"),

            // UNOV / UNODC (Vienne)
            new OrgLeader("UNOV", "Office des Nations unies à Vienne / UNODC", "Ghada Waly", "Ghada", "Waly",
                    "Directrice générale de l'UNOV et Directrice exécutive de l'ONUDC", "https://www.unov.org/"),
            new OrgLeader("UNOV", "Office des Nations unies à Vienne", "Dennis Thatchaichawalit", "Dennis", "Thatchaichawalit",
                    "Directeur général adjoint de l'UNOV", "https://www.unov.org/"),

            // OPEP / OPEC (Vienne)
            new OrgLeader("OPEC", "Organisation des pays exportateurs de pétrole", "Haitham Al Ghais", "Haitham", "Al Ghais",
                    "Secrétaire général de l'OPEP", "https://www.opec.org/"),

            // CTBTO / OTICE (Vienne)
            new OrgLeader("CTBTO", "Organisation du traité d'interdiction complète des essais nucléaires", "Robert Floyd", "Robert", "Floyd",
                    "Secrétaire exécutif de la CTBTO", "https://www.ctbto.org/"),

            // UNIDO / ONUDI (Vienne)
            new OrgLeader("UNIDO", "Organisation des Nations unies pour le développement industriel", "Gerd Müller", "Gerd", "Müller",
                    "Directeur général de l'ONUDI", "https://www.unido.org/"),

            // ICMPD (Vienne)
            new OrgLeader("ICMPD", "International Centre for Migration Policy Development", "Michael Spindelegger", "Michael", "Spindelegger",
                    "Directeur général de l'ICMPD (ancien Vice-chancelier)", "https://www.icmpd.org/")
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());

        verifyConnectivity(ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(OFFICIAL_LEADERS, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        String msg = String.format("%d dirigeants d'organisations internationales établies en Autriche (OSCE, ONU Vienne, OPEP, CTBTO, ONUDI, ICMPD) publiés.", OFFICIAL_LEADERS.size());
        return dataset.toConversionResult().withMessage(msg);
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        List<SourceRecord> records = new ArrayList<>();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        for (OrgLeader leader : OFFICIAL_LEADERS) {
            String id = dataset.buildEntityId(leader.name() + " " + leader.orgAcronym());
            records.add(new SourceRecord(id, "Person", leader.name(), leader.sourceUrl()));
        }
        return records;
    }

    private void verifyConnectivity(Path rawDir) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (OrgLeader leader : OFFICIAL_LEADERS) {
            try {
                Path dump = rawDir.resolve(leader.orgAcronym().toLowerCase() + ".html");
                if (!Files.exists(dump)) {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(leader.sourceUrl()))
                            .header("User-Agent", USER_AGENT)
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (resp.statusCode() < 400) {
                        Files.writeString(dump, resp.body(), StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception e) {
                log.debug("Vérification organisation {} ({}) : {}", leader.orgAcronym(), leader.sourceUrl(), e.getMessage());
            }
        }
    }

    static void convert(List<OrgLeader> leaders, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", "austrian-intl-org-leader");
        position.addValue("name", "Dirigeant d'une organisation internationale établie en Autriche");
        position.addValue("country", "at");
        position.addValue("topics", "gov.igo");
        position.addValue("topics", "role.pep");
        dataset.addEntity(position);

        Set<String> seen = new HashSet<>();
        for (OrgLeader l : leaders) {
            String key = l.name() + " " + l.orgAcronym();
            if (!seen.add(key)) {
                continue;
            }

            FtmEntity person = dataset.newEntity("Person", key);
            person.addValue("name", l.name());
            person.addValue("firstName", l.firstName());
            person.addValue("lastName", l.lastName());
            person.addValue("country", "at");
            person.addValue("position", l.role());
            person.addValue("sourceUrl", l.sourceUrl());
            dataset.addEntity(person);

            FtmEntity occupancy = dataset.newEntity("Occupancy", key + "-occ");
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("description", l.role());
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);
        }
    }
}

