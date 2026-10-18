package com.swiftcheck.aml.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class AmlAdvancedVerificationRequestDTO {
    private String dataset;
    private String companyName;
    private List<String> ubos;
    private String schema;
    private Map<String, List<String>> properties;
}
