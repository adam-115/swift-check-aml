package com.swiftcheck.aml.plugins.nopep.stateenterprises;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Réponses réelles du registre de Brønnøysund (6 novembre 2026) : la liste des huit statsforetak et les rôles de Statkraft SF,
 * Statnett SF et Gassnova SF (les autres entreprises de la liste reçoivent les rôles de Gassnova dans ce test).
 */
class NoPepStateEnterprisesParserTest {

    private static final String API = "https://data.brreg.no/enhetsregisteret/api";

    @Test
    void downloadsTheCompanyListThenTheRolesOfEachCompany(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        NoPepStateEnterprisesParser.download(fetcher(calls), API, List.of(), List.of("SF"), rawDir);

        assertEquals(API + "/enheter?organisasjonsform=SF&size=100&page=0", calls.get(0));
        assertEquals(1 + 8, calls.size(), "une page de liste, puis les rôles des 8 statsforetak");
        assertTrue(calls.contains(API + "/enheter/962986277/roller"));
        assertTrue(Files.exists(rawDir.resolve("roles-962986277.json")));
    }

    @Test
    void keepsChairDeputyMembersAndChiefExecutivesOnly(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, false);

        FtmEntity ceo = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").stream().anyMatch(n -> n.contains("Vartdal"))).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), ceo.getValues("topics"));
        assertEquals(Set.of("1977-04-04"), ceo.getValues("birthDate"));
        assertTrue(ceo.getValues("position").stream().anyMatch(p -> p.startsWith("Directeur général") && p.endsWith("STATKRAFT SF")));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Person".equals(e.getSchema())
                && e.getValues("position").stream().anyMatch(p -> p.startsWith("Président du conseil"))));
        assertTrue(dataset.entities().stream().noneMatch(e -> "Person".equals(e.getSchema())
                && e.getValues("position").stream().anyMatch(p -> p.startsWith("Suppléant"))), "suppléants exclus par défaut");
        assertTrue(dataset.entities().stream().noneMatch(e -> e.getValues("name").stream().anyMatch(n -> n.contains("PRICEWATERHOUSE"))),
                "le réviseur est une entreprise, pas un dirigeant");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void alternatesCanBeIncluded(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, true);

        assertTrue(dataset.entities().stream().anyMatch(e -> "Person".equals(e.getSchema())
                && e.getValues("position").stream().anyMatch(p -> p.startsWith("Suppléant"))));
    }

    @Test
    void aPersonOnSeveralBoardsIsOneEntityWithAllHerFunctions(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, false);

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        long occupancies = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())).count();
        assertTrue(occupancies >= persons, occupancies + " fonctions pour " + persons + " personnes");
    }

    @Test
    void failsClearlyWhenTheListIsEmpty(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> NoPepStateEnterprisesParser.download(
                url -> "{\"page\":{\"totalPages\":0},\"_embedded\":{\"enheter\":[]}}", API, List.of("1120"), List.of(), rawDir));
    }

    private static FtmDatasetBuilder run(Path rawDir, boolean alternates) throws Exception {
        NoPepStateEnterprisesParser.download(fetcher(new ArrayList<>()), API, List.of(), List.of("SF"), rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(NoPepStateEnterprisesParser.ID_PREFIX, NoPepStateEnterprisesParser.TOPIC);
        new NoPepStateEnterprisesParser().convert(rawDir, alternates, dataset);
        return dataset;
    }

    private static NoPepStateEnterprisesParser.Fetcher fetcher(List<String> calls) {
        return url -> {
            calls.add(url);
            if (url.contains("/enheter?")) {
                return resource("entities-sf.json");
            }
            String org = url.substring(url.indexOf("/enheter/") + 9, url.indexOf("/roller"));
            return resource(List.of("962986277", "962986633", "991627626").contains(org) ? "roles-" + org + ".json" : "roles-991627626.json");
        };
    }

    private static String resource(String name) {
        try (InputStream in = NoPepStateEnterprisesParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
