package com.swiftcheck.aml.plugins.frpep.government;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Extrait réel de l'open data HATVP (27 octobre 2026) : Sébastien Lecornu et Laurent Panifous en fonction, avec leur
 * déclaration ; Michel Fournier, ancien ministre délégué, déclarations déposées mais pas encore publiées ; un député,
 * à ignorer.
 */
class FrPepGovernmentParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final String LIST_URL = "https://www.hatvp.fr/livraison/opendata/liste.csv";
    private static final String DECLARATIONS_URL = "https://www.hatvp.fr/livraison/dossiers/";
    private static final List<String> FILES = List.of("liste.csv", "lecornu-sebastien-dim35257-gouvernement.xml",
            "panifous-laurent-dim34863-gouvernement.xml");

    @Test
    void readsGovernmentMembersFromTheList(@TempDir Path dir) throws Exception {
        Path csv = copy(dir, "liste.csv");

        List<HatvpList.Member> members = HatvpList.members(csv);

        assertEquals(List.of("panifous-laurent-24169", "fournier-michel-18032", "lecornu-sebastien"),
                members.stream().map(HatvpList.Member::id).toList(), "le député est ignoré");
        HatvpList.Member panifous = members.get(0);
        assertEquals("Ministre délégué auprès du Premier ministre, chargé des relations avec le Parlement", panifous.function());
        assertEquals(3, panifous.declarations().size());
        assertEquals("panifous-laurent-dim34863-gouvernement.xml", panifous.bestDeclaration().orElseThrow().xmlFile(),
                "la plus récente");

        HatvpList.Member fournier = members.get(1);
        assertTrue(fournier.former());
        assertEquals("Ministre délégué auprès de la ministre de l'aménagement du territoire et de la décentralisation, "
                + "chargé de la ruralité", fournier.function(), "sans « Ancien »");
        assertTrue(fournier.bestDeclaration().isEmpty(), "déclarations pas encore publiées");
        assertEquals("https://www.hatvp.fr/pages_nominatives/fournier-michel-18032", fournier.pageUrl());
    }

    @Test
    void readsDatesInTheDeclaration(@TempDir Path dir) throws Exception {
        assertEquals(new HatvpList.Facts("1986-06-11", "2025-09-09", null),
                HatvpList.facts(copy(dir, "lecornu-sebastien-dim35257-gouvernement.xml")));
        assertEquals(new HatvpList.Facts("1976-12-21", "2025-10-12", null),
                HatvpList.facts(copy(dir, "panifous-laurent-dim34863-gouvernement.xml")));
    }

    @Test
    void convertsCurrentAndFormerMembers(@TempDir Path rawDir) throws Exception {
        FILES.forEach(f -> copy(rawDir, f));
        FtmDatasetBuilder dataset = dataset();

        FrPepGovernmentParser.Report report = new FrPepGovernmentParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        assertEquals(2, report.current());
        assertEquals(1, report.former());
        assertEquals(List.of("Michel FOURNIER"), report.withoutDeclaration());
        assertEquals(3, dataset.qualityReport().getEntities("Person"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity lecornu = entity(dataset, dataset.buildEntityId("lecornu-sebastien"));
        assertEquals(Set.of("Sébastien LECORNU"), lecornu.getValues("name"));
        assertEquals(Set.of("1986-06-11"), lecornu.getValues("birthDate"));
        assertEquals(Set.of("male"), lecornu.getValues("gender"));
        assertEquals(Set.of("Premier ministre"), lecornu.getValues("position"));
        assertEquals(Set.of("role.pep"), lecornu.getValues("topics"));

        FtmEntity mandate = entity(dataset, dataset.buildEntityId("lecornu-sebastien", "Premier ministre"));
        assertEquals(Set.of(lecornu.getId()), mandate.getValues("holder"));
        assertEquals(Set.of("2025-09-09"), mandate.getValues("startDate"));
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertTrue(entity(dataset, dataset.buildEntityId("Premier ministre")).getValues("topics").contains("gov.head"));

        FtmEntity fournier = entity(dataset, dataset.buildEntityId("fournier-michel-18032",
                HatvpList.function("Ancien Ministre délégué auprès de la ministre de l'aménagement du territoire et de "
                        + "la décentralisation, chargé de la ruralité")));
        assertEquals(Set.of("ended"), fournier.getValues("status"));
    }

    @Test
    void formerMembersAreDroppedAfterTheRetentionPeriod(@TempDir Path rawDir) throws Exception {
        FILES.forEach(f -> copy(rawDir, f));
        // Laurent Panifous devenu « Ancien … », fin de fonctions il y a deux ans dans sa déclaration
        Path list = rawDir.resolve("liste.csv");
        Files.writeString(list, Files.readString(list).replace(";Ministre délégué auprès du Premier ministre",
                ";Ancien Ministre délégué auprès du Premier ministre"));
        Path panifous = rawDir.resolve("panifous-laurent-dim34863-gouvernement.xml");
        Files.writeString(panifous, Files.readString(panifous).replace("<dateFinMandat/>", "<dateFinMandat>01/09/2024</dateFinMandat>"));

        FrPepGovernmentParser.Report report = new FrPepGovernmentParser().convert(rawDir, TODAY, 12, List.of(), dataset());

        assertEquals(List.of("Laurent PANIFOUS"), report.tooOld());
        assertEquals(1, report.current());
        assertTrue(report.message().startsWith("1 membres du Gouvernement en fonction, 1 anciens membres ; 1 sortis depuis plus de 12 mois écartés."));
    }

    @Test
    void aMemberStillInTheListStaysCurrentAfterAReshuffle(@TempDir Path rawDir) throws Exception {
        FILES.forEach(f -> copy(rawDir, f));
        // Cas réel (Maud Bregeon) : la dernière déclaration publiée porte sur la fonction quittée au remaniement
        Path panifous = rawDir.resolve("panifous-laurent-dim34863-gouvernement.xml");
        Files.writeString(panifous, Files.readString(panifous).replace("<dateFinMandat/>", "<dateFinMandat>26/02/2026</dateFinMandat>"));
        FtmDatasetBuilder dataset = dataset();

        FrPepGovernmentParser.Report report = new FrPepGovernmentParser().convert(rawDir, TODAY, 12, List.of(), dataset);

        assertEquals(2, report.current());
        FtmEntity mandate = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(dataset.buildEntityId("panifous-laurent-24169"))).findFirst().orElseThrow();
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertTrue(mandate.getValues("startDate").isEmpty(), "dates de l'ancienne fonction non reprises");
        assertTrue(mandate.getValues("endDate").isEmpty());
        assertEquals(Set.of("1976-12-21"), entity(dataset, dataset.buildEntityId("panifous-laurent-24169")).getValues("birthDate"));
    }

    @Test
    void downloadsTheListAndOneDeclarationPerPublishedMember(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        HatvpDownloader downloader = new HatvpDownloader(url -> {
            calls.add(url);
            String file = url.substring(url.lastIndexOf('/') + 1);
            if (file.startsWith("panifous")) {
                throw new IOException("HTTP 500");
            }
            return bytes(file);
        }, 0);

        List<String> failed = downloader.download(LIST_URL, DECLARATIONS_URL, rawDir);

        assertEquals(List.of(LIST_URL, DECLARATIONS_URL + "panifous-laurent-dim34863-gouvernement.xml",
                DECLARATIONS_URL + "lecornu-sebastien-dim35257-gouvernement.xml"), calls, "rien pour Michel Fournier");
        assertEquals(List.of(DECLARATIONS_URL + "panifous-laurent-dim34863-gouvernement.xml"), failed);
        assertTrue(Files.exists(rawDir.resolve("lecornu-sebastien-dim35257-gouvernement.xml")));

        // Déclaration non téléchargée : le membre est gardé, sans dates
        FrPepGovernmentParser.Report report = new FrPepGovernmentParser().convert(rawDir, TODAY, 12, failed, dataset());
        assertEquals(2, report.current());
        assertTrue(report.withoutDeclaration().contains("Laurent PANIFOUS"));
    }

    @Test
    void sourceRecordsComeFromTheArchivedFiles(@TempDir Path rawDir) throws Exception {
        FILES.forEach(f -> copy(rawDir, f));

        List<SourceRecord> records = new FrPepGovernmentParser()
                .readSourceRecords(new ParserContext("fr_pep_government", null, rawDir, null, Map.of()));

        assertEquals(3, records.size());
        assertTrue(records.stream().anyMatch(r -> r.name().equals("Sébastien LECORNU")
                && r.sourceRef().equals("https://www.hatvp.fr/pages_nominatives/lecornu-sebastien")));
    }

    @Test
    void requiresTheUrlsAndRefusesAListWithoutGovernment(@TempDir Path dir) throws Exception {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new FrPepGovernmentParser()
                .parse(new ParserContext("fr_pep_government", null, dir, dir.resolve("out.ndjson"), Map.of())));
        assertTrue(e.getMessage().contains("listUrl"));

        HatvpDownloader downloader = new HatvpDownloader(url -> "civilite;prenom;nom;type_mandat\nM.;A;B;depute\n".getBytes(), 0);
        assertThrows(IOException.class, () -> downloader.download(LIST_URL, DECLARATIONS_URL, dir));
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(FrPepGovernmentParser.ID_PREFIX, FrPepGovernmentParser.TOPIC);
    }

    private static FtmEntity entity(FtmDatasetBuilder dataset, String id) {
        return dataset.entities().stream().filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }

    private static Path copy(Path dir, String file) {
        try {
            Path target = dir.resolve(file);
            Files.write(target, bytes(file));
            return target;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String file) throws IOException {
        try (InputStream in = FrPepGovernmentParserTest.class.getResourceAsStream("/hatvp/" + file)) {
            if (in == null) {
                throw new IOException("404 " + file);
            }
            return in.readAllBytes();
        }
    }
}
