package com.swiftcheck.aml.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.dto.AmlMatchDTO;
import com.swiftcheck.aml.dto.AmlVerificationRequestDTO;
import com.swiftcheck.aml.dto.AmlVerificationResponseDTO;
import com.swiftcheck.aml.dto.batch.BatchEntityDTO;
import com.swiftcheck.aml.dto.batch.BatchParseResultDTO;
import com.swiftcheck.aml.dto.batch.BatchVerificationRequestDTO;
import com.swiftcheck.aml.dto.batch.BatchVerificationResultDTO;
import com.swiftcheck.aml.dto.batch.BatchVerificationRowDTO.Status;

class BatchVerificationServiceTest {

    private final AmlVerificationService verification = mock(AmlVerificationService.class);
    private final BatchVerificationService service = new BatchVerificationService(verification);

    @Test
    void litUnFichierExcelFrancais() {
        String csv = "Référence;Nom;Prénom;Nationalité;Date de naissance;Colonne inconnue\r\n"
                + "C1;Poutine;Vladimir;Russie;07/10/1952;x\r\n"
                + "C2;\"Dupont; dit \"\"Jo\"\"\";Marie;FR;1980;\r\n"
                + "\r\n"
                + "C3;;;fr;31/02/1990;\r\n";
        BatchParseResultDTO r = service.parseCsv("clients.csv", csv.getBytes(Charset.forName("windows-1252")));

        assertEquals(";", r.getSeparator());
        assertEquals(List.of("Colonne inconnue"), r.getIgnoredColumns());
        assertEquals(3, r.getEntities().size());
        assertEquals(2, r.getValidCount());

        BatchEntityDTO e1 = r.getEntities().get(0);
        assertEquals(2, e1.getLineNumber());
        assertEquals("Vladimir Poutine", e1.getName());
        assertEquals("ru", e1.getNationality());
        assertEquals("1952-10-07", e1.getBirthDate());
        assertEquals("Person", e1.getSchema());

        BatchEntityDTO e2 = r.getEntities().get(1);
        assertEquals("Marie Dupont; dit \"Jo\"", e2.getName());
        assertEquals("1980", e2.getBirthDate());

        BatchEntityDTO e3 = r.getEntities().get(2);
        assertFalse(e3.isValid());
        assertNull(e3.getBirthDate());
        assertEquals(2, e3.getWarnings().size());
    }

    @Test
    void refuseUnFichierSansColonneDeNom() {
        byte[] csv = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> service.parseCsv("x.csv", csv));
    }

    @Test
    void verifieChaqueLigneEtProduitLeRapportPdf() {
        when(verification.verifyClient(any())).thenAnswer(inv -> {
            AmlVerificationRequestDTO req = inv.getArgument(0);
            String name = req.getProperties().get("name").get(0);
            AmlVerificationResponseDTO resp = new AmlVerificationResponseDTO();
            List<AmlMatchDTO> matches = new ArrayList<>();
            if (name.contains("Poutine")) {
                AmlMatchDTO m = new AmlMatchDTO();
                m.setId("Q7747");
                m.setCaption("Vladimir Vladimirovich PUTIN");
                m.setScore(0.95);
                m.setDatasets(List.of("eu_fsf"));
                m.setRiskCategory("CRITICAL");
                m.setPepStatusLabel("Sanction Internationale");
                matches.add(m);
            } else if (name.contains("Iran")) {
                AmlMatchDTO m = new AmlMatchDTO();
                m.setId("fatf-geo-ir");
                m.setCaption("Alerte Juridiction GAFI / FATF : Iran");
                m.setScore(1.0);
                m.setRiskCategory("CRITICAL");
                matches.add(m);
            } else if (name.contains("Panne")) {
                throw new RuntimeException("Yente injoignable");
            }
            resp.setMatches(matches);
            return resp;
        });

        String csv = "nom;prenom;nationalite\nPoutine;Vladimir;RU\nIran;Ali;IR\nDupont;Marie;FR\nPanne;Jean;FR\n;;FR\n";
        BatchParseResultDTO parsed = service.parseCsv("lot.csv", csv.getBytes(StandardCharsets.UTF_8));

        BatchVerificationRequestDTO req = new BatchVerificationRequestDTO();
        req.setFileName("lot.csv");
        req.setEntities(parsed.getEntities());
        BatchVerificationResultDTO result = service.verify(req);

        assertEquals(5, result.getTotal());
        assertEquals(1, result.getHits());
        assertEquals(1, result.getCountryAlerts());
        assertEquals(1, result.getClean());
        assertEquals(1, result.getErrors());
        assertEquals(1, result.getInvalid());
        assertEquals(Status.HIT, result.getRows().get(0).getStatus());
        assertEquals(0.95, result.getRows().get(0).getTopScore());
        assertEquals("CRITICAL", result.getRows().get(0).getHighestRisk());

        byte[] pdf = service.generateReport(result);
        assertTrue(pdf.length > 1000);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void normaliseLesDates() {
        assertEquals("1952-10-07", BatchVerificationService.normalizeDate("7/10/1952"));
        assertEquals("1952-10-07", BatchVerificationService.normalizeDate("1952-10-07 00:00:00"));
        assertEquals("1952-10", BatchVerificationService.normalizeDate("10/1952"));
        assertNull(BatchVerificationService.normalizeDate("demain"));
    }
}
