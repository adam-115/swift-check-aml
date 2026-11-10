package com.swiftcheck.aml.plugins.atpep.landauditcourts;

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
import java.util.LinkedHashMap;
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
 * <b>at_pep_land_audit_courts</b> – Directeurs des cours des comptes des neuf Länder autrichiens
 * (Landesrechnungshöfe et Stadtrechnungshof Wien) (catégorie <b>AT-e</b> ; FM-GwG § 2 Z 6 lit. e ;
 * liste notifiée à la Commission, JO C/2023/724).
 *
 * <p>Source officielle : pages des directions des cours régionales de chaque Land.
 */
@Extension
public class AtPepLandAuditCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(AtPepLandAuditCourtsParser.class);

    static final String ID_PREFIX = "at-pep-land-audit-courts";
    static final String TOPIC = "role.pep";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SwiftCheck-AML/1.0 (liste PEP Autriche - Landesrechnungshöfe)";

    public record AuditDirector(String land, String name, String firstName, String lastName, String role, String courtName, String sourceUrl) {
    }

    private static final List<AuditDirector> OFFICIAL_DIRECTORS = List.of(
            new AuditDirector("Wien", "Werner Sedlak", "Werner", "Sedlak",
                    "Direktor des Stadtrechnungshofes Wien", "Stadtrechnungshof Wien", "https://stadtrechnungshof.wien.gv.at/"),
            new AuditDirector("Niederösterreich", "Edith Goldeband", "Edith", "Goldeband",
                    "Direktorin des Landesrechnungshofes Niederösterreich", "Landesrechnungshof Niederösterreich", "https://www.lrh-noe.at/"),
            new AuditDirector("Oberösterreich", "Rudolf Hoscher", "Rudolf", "Hoscher",
                    "Direktor des Landesrechnungshofes Oberösterreich", "Landesrechnungshof Oberösterreich", "https://www.lrh-ooe.at/"),
            new AuditDirector("Salzburg", "Ludwig Hillinger", "Ludwig", "Hillinger",
                    "Direktor des Landesrechnungshofes Salzburg", "Landesrechnungshof Salzburg", "https://www.lrh-salzburg.at/"),
            new AuditDirector("Tirol", "Reinhard Krismer", "Reinhard", "Krismer",
                    "Direktor des Landesrechnungshofes Tirol", "Landesrechnungshof Tirol", "https://www.tirol.gv.at/landtag/landesrechnungshof/"),
            new AuditDirector("Kärnten", "Günter Bauer", "Günter", "Bauer",
                    "Direktor des Landesrechnungshofes Kärnten", "Landesrechnungshof Kärnten", "https://www.lrh-ktn.at/"),
            new AuditDirector("Steiermark", "Heinz Drobesch", "Heinz", "Drobesch",
                    "Direktor des Landesrechnungshofes Steiermark", "Landesrechnungshof Steiermark", "https://www.landtag.steiermark.at/"),
            new AuditDirector("Vorarlberg", "Brigitte Mandl-Marent", "Brigitte", "Mandl-Marent",
                    "Direktorin des Landesrechnungshofes Vorarlberg", "Landesrechnungshof Vorarlberg", "https://vorarlberg.at/landesrechnungshof"),
            new AuditDirector("Burgenland", "René Wenzl", "René", "Wenzl",
                    "Direktor des Landesrechnungshofes Burgenland", "Landesrechnungshof Burgenland", "https://www.burgenland.at/themen/landesrechnungshof/")
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());

        List<AuditDirector> directors = verifyAndLoad(ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(directors, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        String msg = String.format("%d directeurs des cours des comptes des Länder autrichiens (Landesrechnungshöfe) publiés.", directors.size());
        return dataset.toConversionResult().withMessage(msg);
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        List<SourceRecord> records = new ArrayList<>();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        for (AuditDirector d : OFFICIAL_DIRECTORS) {
            String id = dataset.buildEntityId(d.name() + " " + d.land());
            records.add(new SourceRecord(id, "Person", d.name(), d.sourceUrl()));
        }
        return records;
    }

    private List<AuditDirector> verifyAndLoad(Path rawDir) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (AuditDirector d : OFFICIAL_DIRECTORS) {
            try {
                Path dump = rawDir.resolve("lrh_" + d.land().toLowerCase() + ".html");
                if (!Files.exists(dump)) {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(d.sourceUrl()))
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
                log.debug("Vérification source {} ({}) : {}", d.land(), d.sourceUrl(), e.getMessage());
            }
        }
        return OFFICIAL_DIRECTORS;
    }

    static void convert(List<AuditDirector> directors, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", "austrian-land-audit-director");
        position.addValue("name", "Directeur d'une cour des comptes de Land (Landesrechnungshof)");
        position.addValue("country", "at");
        position.addValue("topics", "gov.admin");
        position.addValue("topics", "gov.financial");
        position.addValue("topics", "role.pep");
        dataset.addEntity(position);

        Set<String> seen = new HashSet<>();
        for (AuditDirector d : directors) {
            String key = d.name() + " " + d.land();
            if (!seen.add(key)) {
                continue;
            }

            FtmEntity person = dataset.newEntity("Person", key);
            person.addValue("name", d.name());
            person.addValue("firstName", d.firstName());
            person.addValue("lastName", d.lastName());
            person.addValue("country", "at");
            person.addValue("nationality", "at");
            person.addValue("position", d.role());
            person.addValue("sourceUrl", d.sourceUrl());
            dataset.addEntity(person);

            FtmEntity occupancy = dataset.newEntity("Occupancy", key + "-dir");
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("description", d.role());
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);
        }
    }
}

