package com.swiftcheck.aml.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.dto.CountryDTO;
import com.swiftcheck.aml.dto.CoverageDTO;
import com.swiftcheck.aml.dto.CoverageDTO.CategoryCoverage;
import com.swiftcheck.aml.dto.CoverageDTO.CoveringParser;
import com.swiftcheck.aml.dto.CoverageDTO.FunctionCoverage;
import com.swiftcheck.aml.dto.CoverageDTO.Status;
import com.swiftcheck.aml.entities.Completeness;
import com.swiftcheck.aml.entities.Country;
import com.swiftcheck.aml.entities.DataSourceType;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.ParserKind;
import com.swiftcheck.aml.entities.PepCategoryKind;
import com.swiftcheck.aml.repositories.ParserRepository;

/** Rapport PDF de couverture : génération réelle avec le gabarit Jasper, sur deux pays. */
class CoverageReportServiceTest {

    @Test
    void generatesAPdfForAllCountries() throws Exception {
        PepReferenceService reference = mock(PepReferenceService.class);
        CoverageService coverage = mock(CoverageService.class);
        ParserRepository parsers = mock(ParserRepository.class);

        Country lu = country("LU", "Luxembourg");
        Country hu = country("HU", "Hongrie");
        when(reference.findCountries()).thenReturn(List.of(lu, hu));

        Parser parser = new Parser();
        parser.setId(7);
        parser.setName("PEP Hongrie – Assemblée nationale");
        parser.setDescription("Députés en fonction et mandats terminés depuis moins de 12 mois, d'après le registre de l'Office électoral.");
        parser.setDatasetName("hu_pep_parliament");
        parser.setWebsiteUrl("https://vtr.valasztas.hu/kepviselo");
        parser.setPluginId("hu-pep-parliament");
        parser.setDataSourceType(DataSourceType.PLUGIN_MANAGED);
        parser.setParserKind(ParserKind.API);
        parser.setRefreshHours(168);
        parser.setRetentionMonths(12);
        parser.setLicence("Données publiques");
        parser.setParameters("{\"baseUrl\": \"https://vtr.valasztas.hu/kepviselo/data\", \"monthsAfterEnd\": \"12\"}");
        parser.setTechnicalDescription("# Parser hongrois\n\n**Source** : quatre fichiers JSON.\n\n- cycle courant choisi par dates\n- `szId` comme clé\n\n"
                + "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(60));
        when(parsers.findAll()).thenReturn(List.of(parser));

        CoveringParser covering = new CoveringParser(7, parser.getName(), "hu_pep_parliament", true, Completeness.PARTIAL, true,
                "Les 199 députés en fonction et 29 mandats terminés.", "Législature 2022-2026 absente ; pas de naissance.",
                ParserExecutionStatus.SUCCESS, Instant.parse("2026-11-07T10:00:00Z"), 228, 3);
        FunctionCoverage covered = new FunctionCoverage(1, "Député de l'Assemblée nationale", "Országgyűlés", 199, Status.PARTIAL,
                "Partielle : hu_pep_parliament ne donne qu'une partie des titulaires.", List.of(), List.of(covering));
        FunctionCoverage second = new FunctionCoverage(2, "Ancien député", "Országgyűlés", null, Status.PARTIAL,
                "Partielle.", List.of("hu_pep_parliament <b>test</b> & co"), List.of(covering));
        FunctionCoverage none = new FunctionCoverage(3, "Président de la République", "Köztársasági elnök", 1, Status.NONE,
                "Non couverte : aucun parser n'est déclaré pour cette fonction.", List.of(), List.of());
        CoverageDTO huCoverage = new CoverageDTO(CountryDTO.from(hu), 1, 0, 228, List.of(
                new CategoryCoverage("HU-b", "Députés", PepCategoryKind.OFFICIAL, 1, "Loi LIII de 2017", Status.PARTIAL, List.of(covered, second)),
                new CategoryCoverage("HU-a", "Chef de l'État", PepCategoryKind.OFFICIAL, 1, null, Status.NONE, List.of(none)),
                new CategoryCoverage("HU-g", "Entreprises d'État", PepCategoryKind.OFFICIAL, 3, null, Status.NONE, List.of())));
        when(coverage.coverage("HU")).thenReturn(huCoverage);
        when(coverage.coverage("LU")).thenReturn(new CoverageDTO(CountryDTO.from(lu), 0, 0, 0, List.of()));

        CoverageReportService service = new CoverageReportService(reference, coverage, parsers);
        byte[] pdf = service.generate(null);
        net.sf.jasperreports.engine.JasperPrint print = service.print(null);
        for (int i = 0; i < Math.min(6, print.getPages().size()); i++) {
            javax.imageio.ImageIO.write((java.awt.image.BufferedImage) net.sf.jasperreports.engine.JasperPrintManager.printPageToImage(print, i, 1.4f),
                    "png", new java.io.File(System.getProperty("java.io.tmpdir"), "cov_page" + (i + 1) + ".png"));
        }
        System.out.println("Pages : " + print.getPages().size());

        assertTrue(pdf.length > 5000);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "coverage_report_test.pdf");
        Files.write(out, pdf);
        System.out.println("PDF écrit : " + out);
    }

    @Test
    void coverPageSummaryOverflowsOverSeveralPages() throws Exception {
        PepReferenceService reference = mock(PepReferenceService.class);
        CoverageService coverage = mock(CoverageService.class);
        ParserRepository parsers = mock(ParserRepository.class);
        List<Country> countries = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Country c = country("C" + i, "Pays numéro " + i);
            countries.add(c);
            when(coverage.coverage("C" + i)).thenReturn(new CoverageDTO(CountryDTO.from(c), 0, 0, 0, List.of()));
        }
        when(reference.findCountries()).thenReturn(countries);
        when(parsers.findAll()).thenReturn(List.of());
        net.sf.jasperreports.engine.JasperPrint print = new CoverageReportService(reference, coverage, parsers).print(null);
        assertTrue(print.getPages().size() >= 41, "une page de garde au moins, puis un pays par page : " + print.getPages().size());
    }

    @Test
    void reducesMarkdownToPlainText() {
        Parser parser = new Parser();
        parser.setTechnicalDescription("## Titre\n**gras** et `code`\n- point\n[lien](https://x.test)");
        String text = CoverageReportService.technical(parser);
        assertNotNull(text);
        assertEquals("Titre\ngras et code\n• point\nlien (https://x.test)", text);
        assertNull(CoverageReportService.technical(new Parser()));
    }

    private static Country country(String iso, String name) {
        Country c = new Country();
        c.setIso(iso);
        c.setName(name);
        c.setEuMember(true);
        c.setLegalBasis("Loi de test");
        c.setRetentionMonths(12);
        return c;
    }
}
