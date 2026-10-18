package com.swiftcheck.aml.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.dto.AmlVerificationRequestDTO;
import com.swiftcheck.aml.service.AmlVerificationService;

import com.swiftcheck.aml.dto.AmlVerificationResponseDTO;

@RestController
@RequestMapping("/api/verification")
public class AmlVerificationController {

    private final AmlVerificationService amlVerificationService;

    @Autowired
    public AmlVerificationController(AmlVerificationService amlVerificationService) {
        this.amlVerificationService = amlVerificationService;
    }

    @PostMapping("/verify")
    public ResponseEntity<AmlVerificationResponseDTO> verifyClient(@RequestBody AmlVerificationRequestDTO requestDto) {
        try {
            AmlVerificationResponseDTO result = amlVerificationService.verifyClient(requestDto);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            AmlVerificationResponseDTO errorDto = new AmlVerificationResponseDTO();
            errorDto.setErrorMessage(e.getMessage());
            return ResponseEntity.status(500).body(errorDto);
        }
    }

    @PostMapping("/verify-advanced")
    public ResponseEntity<com.swiftcheck.aml.dto.AmlAdvancedVerificationResponseDTO> verifyAdvanced(@RequestBody com.swiftcheck.aml.dto.AmlAdvancedVerificationRequestDTO requestDto) {
        try {
            com.swiftcheck.aml.dto.AmlAdvancedVerificationResponseDTO result = amlVerificationService.verifyAdvanced(requestDto);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            com.swiftcheck.aml.dto.AmlAdvancedVerificationResponseDTO errorDto = new com.swiftcheck.aml.dto.AmlAdvancedVerificationResponseDTO();
            errorDto.setErrorMessage(e.getMessage());
            return ResponseEntity.status(500).body(errorDto);
        }
    }

    @PostMapping("/report")
    public ResponseEntity<byte[]> generateReport(@RequestBody com.swiftcheck.aml.dto.AmlReportRequestDTO requestDto) {
        try {
            byte[] pdfBytes = amlVerificationService.generateReport(requestDto);
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "aml_report.pdf");
            return new ResponseEntity<>(pdfBytes, headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
}
