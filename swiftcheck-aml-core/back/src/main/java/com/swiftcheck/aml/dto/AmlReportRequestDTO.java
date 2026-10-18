package com.swiftcheck.aml.dto;

import lombok.Data;

@Data
public class AmlReportRequestDTO {
    private AmlVerificationRequestDTO request;
    private AmlVerificationResponseDTO response;
}
