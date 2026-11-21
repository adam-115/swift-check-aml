package com.swiftcheck.aml.controllers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.swiftcheck.aml.dto.batch.BatchParseResultDTO;
import com.swiftcheck.aml.dto.batch.BatchVerificationRequestDTO;
import com.swiftcheck.aml.dto.batch.BatchVerificationResultDTO;
import com.swiftcheck.aml.service.BatchVerificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * Vérification par lot : l'utilisateur charge un fichier CSV (une entité par ligne), en voit l'aperçu,
 * lance la vérification puis télécharge le rapport en PDF.
 */
@RestController
@RequestMapping("/api/verification/batch")
@Slf4j
public class BatchVerificationController {

    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final BatchVerificationService batchService;

    public BatchVerificationController(BatchVerificationService batchService) {
        this.batchService = batchService;
    }

    /** Lit le fichier et renvoie les entités reconnues, sans rien vérifier. */
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parse(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Aucun fichier reçu."));
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("message", "Fichier trop volumineux (5 Mo au maximum)."));
        }
        try {
            BatchParseResultDTO result = batchService.parseCsv(file.getOriginalFilename(), file.getBytes());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Lecture du fichier CSV impossible", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Lecture du fichier impossible : " + e.getMessage()));
        }
    }

    /** Vérifie toutes les entités du lot et renvoie le rapport. */
    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody BatchVerificationRequestDTO request) {
        try {
            BatchVerificationResultDTO result = batchService.verify(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Vérification par lot en échec", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Vérification impossible : " + e.getMessage()));
        }
    }

    /** Rapport PDF d'une vérification déjà faite. */
    @PostMapping("/report")
    public ResponseEntity<byte[]> report(@RequestBody BatchVerificationResultDTO result) {
        byte[] pdf = batchService.generateReport(result);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("rapport_verification_lot_" + LocalDate.now() + ".pdf").build());
        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    /** Modèle de fichier CSV à remplir. */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename("modele_verification_lot.csv").build());
        return ResponseEntity.ok().headers(headers).body(batchService.templateCsv().getBytes(StandardCharsets.UTF_8));
    }
}
