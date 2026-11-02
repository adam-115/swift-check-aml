package com.swiftcheck.aml.plugins.frpep.localenterprises;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Réponses réelles de l'API Recherche d'entreprises (02/11/2026) : deux SEM (Adoma, 470 M€ de chiffre d'affaires ;
 * Deves Ensoleillé, 1,75 M€), deux régies sans comptes publiés (Eau de Paris, ETI ; Metpark, PME), et deux résultats
 * de la recherche « société publique locale » : la SPL des ports de la Manche (10,8 M€) et une SA dont le nom ne dit
 * pas qu'elle est une SPL.
 */
class FrPepLocalEnterprisesParserTest {

    @BeforeAll
    static void noPause() {
        CompanyRegistry.pauseMs = 0;
    }

    @Test
    void keepsEntitiesAboveTheRevenueThresholdOrLargeWithoutAccounts(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();

        FrPepLocalEnterprisesParser.Report report = new FrPepLocalEnterprisesParser().convert(rawDir, Map.of(), dataset);

        assertEquals(5, report.candidates(), "la SA qui ne se dit pas SPL n'est pas candidate");
        assertEquals(1, report.byRevenue(), "Adoma");
        assertEquals(1, report.bySize(), "Eau de Paris (ETI) ; pas Metpark (PME)");
        assertEquals(12 + 19, report.leaders());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
    }

    @Test
    void thresholdIsAParameter(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);

        assertEquals(3, new FrPepLocalEnterprisesParser().convert(rawDir, Map.of("minRevenue", "10000000"), dataset())
                .retained(), "+ la SPL des ports de la Manche");
        assertEquals(4, new FrPepLocalEnterprisesParser().convert(rawDir, Map.of("minRevenue", "1000000"), dataset())
                .retained(), "+ Deves Ensoleillé");
    }

    @Test
    void writesTheLeaderWithTheCompanyContext(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();
        new FrPepLocalEnterprisesParser().convert(rawDir, Map.of(), dataset);

        FtmEntity company = dataset.entities().stream()
                .filter(e -> "Company".equals(e.getSchema()) && e.getValues("registrationNumber").contains("788058030"))
                .findFirst().orElseThrow();
        assertEquals(Set.of("Société d'économie mixte contrôlée par une ou plusieurs collectivités (chiffre "
                + "d'affaires 2022 : 470 M€)"), company.getValues("description"));
        FtmEntity person = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema())
                        && e.getValues("position").stream().anyMatch(p -> p.endsWith("– ADOMA")))
                .findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        assertEquals(1, person.getValues("birthDate").size());
    }

    @Test
    void recognisesLocalPublicCompaniesByTheirName() {
        assertTrue(FrPepLocalEnterprisesParser.isSplName("SOCIETE PUBLIQUE LOCALE FUNERAIRE DE PARIS"));
        assertTrue(FrPepLocalEnterprisesParser.isSplName("SOCIETE  PUBLIQUE LOCALE PORT DE PLAISANCE DE TOGA"));
        assertTrue(FrPepLocalEnterprisesParser.isSplName("SOCIETE PUBLIQUE LOCAL U SANT'ANGHJULU"));
        assertTrue(FrPepLocalEnterprisesParser.isSplName("SOCIETE ANONYME PUBLIQUE LOCALE DES TRANSPORTS (STABUS)"));
        assertTrue(FrPepLocalEnterprisesParser.isSplName("SPL ENFANCE ET ANIMATION (SPLEA)"));
        assertTrue(FrPepLocalEnterprisesParser.isSplName("PORTS DE LA MANCHE (S.P.L D'EXPLOITATION)"));
        assertFalse(FrPepLocalEnterprisesParser.isSplName("EAU DU PONANT"));
        assertFalse(FrPepLocalEnterprisesParser.isSplName("SPLENDID HOTEL"));
    }

    @Test
    void downloadsEveryPageOfEachNatureAndOfEachSplQuery(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        CompanyRegistry.Fetcher fetcher = url -> {
            calls.add(url);
            return "{\"results\":[],\"total_pages\":2,\"total_results\":30}".getBytes(StandardCharsets.UTF_8);
        };

        FrPepLocalEnterprisesParser.download(fetcher, Map.of("apiUrl", "https://api.test"), rawDir);
        try (var listing = Files.list(rawDir)) {
            assertTrue(listing.allMatch(Files::isRegularFile), "le core n'archive que les fichiers de la racine de rawDir");
        }

        assertEquals(4 * 2 + 2 * 2, calls.size(), "4 natures et 2 recherches SPL, 2 pages chacune");
        assertTrue(calls.contains("https://api.test?nature_juridique=5599&q=societe+publique+locale"
                + "&etat_administratif=A&per_page=25&page=2"), calls.toString());
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(FrPepLocalEnterprisesParser.ID_PREFIX);
    }

    private static void copyFixture(Path rawDir) throws IOException {
        for (String file : List.of("nature-5515-1.json", "nature-4150-1.json", "spl0-1.json")) {
            try (InputStream in = FrPepLocalEnterprisesParserTest.class.getResourceAsStream("/raw/companies/" + file)) {
                Files.write(rawDir.resolve(CompanyRegistry.PREFIX + file), in.readAllBytes());
            }
        }
    }
}
