package com.swiftcheck.aml.plugins.frpep.stateenterprises;

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
 * Extraits réels du 02/11/2026 : la liste de la page « Les entreprises de l'APE » et les réponses de l'API Recherche
 * d'entreprises pour Civipol, le grand port maritime de Marseille (aucun dirigeant au registre), la société
 * aéroportuaire de Guadeloupe Pôle Caraïbes (nom coupé en deux lignes sur la page) et une page de trois EPIC
 * (RATP, AFPA, Charbonnages de France en liquidation). Les autres entreprises de l'APE n'ont pas de fiche dans
 * l'extrait : elles sont signalées « introuvables ».
 */
class FrPepStateEnterprisesParserTest {

    @BeforeAll
    static void noPause() {
        CompanyRegistry.pauseMs = 0;
    }

    @Test
    void readsEverySectorOfTheApePage() throws IOException {
        List<ApePage.Listed> listed = ApePage.read(text("/raw/ape.html"));

        assertEquals(85, listed.size(), "85 lignes, dont « Société aéroportuaire de Guadeloupe » et « Pôle Caraïbes »");
        assertEquals(13, listed.stream().filter(ApePage.Listed::singleShare).count());
        assertEquals(new ApePage.Listed("Areva", "Énergie", false), listed.get(0), "paragraphe à puces");
        assertTrue(listed.contains(new ApePage.Listed("Aubert & Duval SAS",
                "Entités où l’État ne détient qu’une seule action", true)));
    }

    @Test
    void everyNameOfThePageHasAnEntryInTheSirenFile() throws IOException {
        Map<String, FrPepStateEnterprisesParser.Known> known = FrPepStateEnterprisesParser.known();
        for (ApePage.Listed l : ApePage.read(text("/raw/ape.html"))) {
            assertTrue(known.containsKey(ApePage.fold(l.name())), l.name());
        }
    }

    @Test
    void keepsApeCompaniesAndNationalEpicsWithTheirLeaders(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();

        FrPepStateEnterprisesParser.Report report = new FrPepStateEnterprisesParser().convert(rawDir, Map.of(), dataset);

        assertEquals(71, report.apeListed(), "sans les 13 entités à une seule action ; Pôle Caraïbes compté une fois");
        assertEquals(3, report.apeCompanies(), "Civipol, GPMM, Pôle Caraïbes");
        assertEquals(List.of(), report.apeUnknown());
        assertEquals(11 + 57, report.apeWithoutSiren().size(), "11 sans SIREN français + 57 absents de l'extrait");
        assertEquals(3, report.epics());
        assertEquals(List.of("Grand port maritime de Marseille", "CHARBONNAGES DE FRANCE"), report.withoutLeaders(),
                "liquidateur écarté");
        assertEquals(6 + 13 + 33 + 27, report.leaders(), "personnes physiques, sans commissaires aux comptes");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
    }

    @Test
    void writesThePersonTheCompanyAndTheirLink(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = dataset();
        new FrPepStateEnterprisesParser().convert(rawDir, Map.of(), dataset);

        FtmEntity person = find(dataset, "Person", "name", "Carole Chevrier");
        assertEquals(Set.of("Carole Chevrier", "Carole Puig"), person.getValues("alias"), "nom de naissance en alias");
        assertEquals(Set.of("1982-09"), person.getValues("birthDate"));
        assertEquals(Set.of("Administrateur – Civipol"), person.getValues("position"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));

        FtmEntity company = find(dataset, "Company", "registrationNumber", "434914164");
        assertEquals(Set.of("gov.soe"), company.getValues("topics"), "la société n'est pas une PEP");
        FtmEntity link = find(dataset, "Directorship", "director", person.getId());
        assertEquals(Set.of(company.getId()), link.getValues("organization"));

        FtmEntity ratp = find(dataset, "Person", "name", "Augustin De Romanet De Beaune");
        assertEquals(Set.of(Leaders.UNSPECIFIED_ROLE + " – REGIE AUTONOME DES TRANSPORTS PARISIENS (RATP)"),
                ratp.getValues("position"), "qualité « Autre » du registre");
    }

    @Test
    void singleShareEntitiesOnlyOnRequest(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);

        FrPepStateEnterprisesParser.Report report = new FrPepStateEnterprisesParser()
                .convert(rawDir, Map.of("includeSingleShare", "true"), dataset());

        assertEquals(71 + 13, report.apeListed());
    }

    @Test
    void downloadsTheFilesOfEachCompanyAndEveryPageOfEachNature(@TempDir Path rawDir) throws Exception {
        String page = text("/raw/ape.html");
        List<String> calls = new ArrayList<>();
        CompanyRegistry.Fetcher fetcher = url -> {
            calls.add(url);
            return (url.startsWith("https://ape") ? page : "{\"results\":[],\"total_pages\":2,\"total_results\":30}")
                    .getBytes(StandardCharsets.UTF_8);
        };

        FrPepStateEnterprisesParser.download(fetcher, Map.of("apeUrl", "https://ape.test", "apiUrl", "https://api.test",
                "natures", "4110"), rawDir);

        assertEquals("https://ape.test", calls.get(0));
        assertEquals(60, calls.stream().filter(u -> u.contains("&q=")).count(), "un SIREN par entreprise rattachée");
        assertEquals(List.of("https://api.test?nature_juridique=4110&etat_administratif=A&per_page=25&page=1",
                "https://api.test?nature_juridique=4110&etat_administratif=A&per_page=25&page=2"),
                calls.stream().filter(u -> u.contains("nature_juridique")).toList());
        assertTrue(Files.exists(rawDir.resolve("companies-siren-552081317.json")), "EDF");
        assertFalse(Files.exists(rawDir.resolve("companies-siren-448747360.json")), "Adit : une seule action");
        try (var listing = Files.list(rawDir)) {
            assertTrue(listing.allMatch(Files::isRegularFile), "le core n'archive que les fichiers de la racine de rawDir");
        }
    }

    @Test
    void blockedApePageStopsTheRunInsteadOfFallingBackToAnEmbeddedList(@TempDir Path rawDir) {
        CompanyRegistry.Fetcher fetcher = url -> {
            if (url.startsWith("https://ape")) {
                throw new IOException("HTTP 403 : " + url);  // contrôle anti-robots : jamais contourné
            }
            return "{\"results\":[],\"total_pages\":1}".getBytes(StandardCharsets.UTF_8);
        };

        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> FrPepStateEnterprisesParser.download(fetcher, Map.of("apeUrl", "https://ape.test", "natures", "4110"), rawDir));

        assertTrue(e.getMessage().contains("HTTP 403"));
        // aucune liste de secours : sans la page, le dossier de travail ne contient pas de page APE
        assertTrue(!Files.exists(rawDir.resolve(FrPepStateEnterprisesParser.APE_FILE)));
    }

    @Test
    void namesAreWrittenInProperCase() {
        assertEquals("Jean-Marc Antoine", Leaders.properCase("JEAN-MARC  ANTOINE"));
        assertEquals("D'Arc", Leaders.properCase("D'ARC"));
        assertEquals("Pedini", Leaders.properCase(Leaders.beforeParenthesis("PEDINI (BACONNET)")));
        assertEquals("BACONNET", Leaders.inParenthesis("PEDINI (BACONNET)"));
        assertEquals("Membre", Leaders.role("Autre, Membre"));
        assertEquals(Leaders.UNSPECIFIED_ROLE, Leaders.role("Autre"));
    }

    private static FtmEntity find(FtmDatasetBuilder dataset, String schema, String property, String value) {
        return dataset.entities().stream()
                .filter(e -> schema.equals(e.getSchema()) && e.getValues(property).contains(value))
                .findFirst().orElseThrow(() -> new AssertionError(schema + " " + property + "=" + value));
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(FrPepStateEnterprisesParser.ID_PREFIX);
    }

    private static void copyFixture(Path rawDir) throws IOException {
        Files.writeString(rawDir.resolve(FrPepStateEnterprisesParser.APE_FILE), text("/raw/ape.html"));
        for (String file : List.of("siren-434914164.json", "siren-775558489.json", "siren-789918604.json",
                "nature-4120-1.json")) {
            Files.writeString(rawDir.resolve(CompanyRegistry.PREFIX + file), text("/raw/companies/" + file));
        }
    }

    private static String text(String resource) throws IOException {
        try (InputStream in = FrPepStateEnterprisesParserTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
