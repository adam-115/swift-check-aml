package com.swiftcheck.aml.dto;

import java.util.Map;
import lombok.Data;

@Data
public class AmlAdvancedVerificationResponseDTO {
    private AmlVerificationResponseDTO companyResult;
    private Map<String, AmlVerificationResponseDTO> uboResults;
    private String errorMessage;
}
