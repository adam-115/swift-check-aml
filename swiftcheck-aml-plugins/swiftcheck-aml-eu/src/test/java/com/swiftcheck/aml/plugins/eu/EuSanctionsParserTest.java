package com.swiftcheck.aml.plugins.eu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;

import com.swiftcheck.aml.sdk.FtmEntity;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Vérifie chaque règle de conversion du parser UE sur un petit fichier d'exemple. */
class EuSanctionsParserTest {

    private static FtmDatasetBuilder result;

    @BeforeAll
    static void parse() throws Exception {
        result = new FtmDatasetBuilder(EuSanctionsParser.ID_PREFIX, EuSanctionsParser.TOPIC);
        try (InputStream in = EuSanctionsParserTest.class.getResourceAsStream("/eu_sample.xml")) {
            new EuSanctionsParser().convert(in, result);
        }
    }

    /** Renvoie l'entité portant cet ID, ou échoue avec un message clair. */
    private static FtmEntity entity(FtmDatasetBuilder dataset, String id) {
        return dataset.entities().stream()
                .filter(e -> e.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }

    /** Le contrat complet appelé par le core : fichier en entrée, fichier NDJSON et bilan en sortie. */
    @Test
    void parseWritesNdjsonFileAndReturnsResult(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.xml");
        try (InputStream in = EuSanctionsParserTest.class.getResourceAsStream("/eu_sample.xml")) {
            Files.copy(in, input);
        }
        Path output = dir.resolve("out.ndjson");
        ParserContext ctx = new ParserContext("test", input, dir.resolve("raw"), output, Map.of());

        ParserResult parsed = new EuSanctionsParser().parse(ctx);

        assertEquals(result.qualityReport().getTotalEntities(), parsed.entityCount());
        assertEquals(parsed.entityCount(), Files.readAllLines(output).size(), "une entité par ligne");
        assertEquals(0, parsed.rejectedCount());
    }

    @Test
    void producesOneSubjectAndOneSanctionPerRecord() {
        assertEquals(1, result.qualityReport().getEntities("Person"));
        assertEquals(1, result.qualityReport().getEntities("Organization"));
        assertEquals(2, result.qualityReport().getEntities("Sanction"));
    }

    @Test
    void personNamesAndAttributes() {
        FtmEntity p = entity(result, "eu-fsd-13");
        assertEquals(Set.of("Ivan PETROV"), p.getValues("name"));
        assertEquals(Set.of("Иван Петров"), p.getValues("alias"));
        assertEquals(Set.of("Vania"), p.getValues("weakAlias"));
        assertEquals(Set.of("male"), p.getValues("gender"));
        assertEquals(Set.of("Minister"), p.getValues("position"));
        assertEquals(Set.of("sanction"), p.getValues("topics"));
    }

    @Test
    void personCountriesBirthAndDocuments() {
        FtmEntity p = entity(result, "eu-fsd-13");
        assertEquals(Set.of("ru"), p.getValues("nationality"), "le code 00 (inconnu) est ignoré");
        assertEquals(Set.of("1965-03-15", "1960", "1962"), p.getValues("birthDate"), "l'année islamique n'est pas une date");
        assertEquals(Set.of("Moscow, RUSSIAN FEDERATION"), p.getValues("birthPlace"));
        assertEquals(Set.of("AB123456"), p.getValues("passportNumber"));
        assertTrue(p.getValues("notes").contains("Écriture cyrillique"));
        assertTrue(p.getValues("notes").contains("Année de naissance (calendrier islamique) : 1343"));
    }

    @Test
    void sanctionCarriesLegalReferences() {
        FtmEntity s = entity(result, "eu-fsd-13-sanction");
        assertEquals(Set.of("eu-fsd-13"), s.getValues("entity"));
        assertEquals(Set.of("EU.27.28"), s.getValues("authorityId"));
        assertEquals(Set.of("QDi.001"), s.getValues("unscId"));
        assertEquals(Set.of("TAQA"), s.getValues("program"));
        assertEquals(Set.of("2002-05-28"), s.getValues("startDate"));
        assertEquals(Set.of("http://eur-lex.europa.eu/exemple"), s.getValues("sourceUrl"));
        assertEquals(Set.of("Remarque générale"), s.getValues("reason"));
    }

    @Test
    void organizationAddressContactsAndIdentifiers() {
        FtmEntity o = entity(result, "eu-fsd-99");
        assertEquals(Set.of("1 Main St, Tehran, IRAN"), o.getValues("address"));
        assertEquals(Set.of("ir"), o.getValues("country"));
        assertEquals(Set.of("a@x.ir", "b@x.ir"), o.getValues("email"));
        assertEquals(Set.of("9187629"), o.getValues("imoNumber"));
        assertEquals(Set.of("REG-1"), o.getValues("registrationNumber"));
    }

    /**
     * Test du parser : chaque personne / organisation de la source est listée avec l'ID et le nom
     * principal produits par la conversion (sinon le test Yente chercherait de mauvais éléments).
     */
    @Test
    void readSourceRecordsListeChaqueSujetAvecSonIdEtSonNom() throws Exception {
        List<SourceRecord> records;
        try (InputStream in = EuSanctionsParserTest.class.getResourceAsStream("/eu_sample.xml")) {
            records = new EuSanctionsParser().readSourceRecords(in);
        }
        Map<String, FtmEntity> subjects = new java.util.HashMap<>();
        for (FtmEntity e : result.entities()) {
            if (!"Sanction".equals(e.getSchema())) {
                subjects.put(e.getId(), e);
            }
        }
        assertEquals(subjects.size(), records.size());
        for (SourceRecord record : records) {
            FtmEntity converted = subjects.get(record.entityId());
            assertTrue(converted != null, "ID inconnu de la conversion : " + record.entityId());
            assertEquals(converted.getSchema(), record.schema());
            assertTrue(converted.getValues("name").contains(record.name()), record.name());
        }
    }
}
