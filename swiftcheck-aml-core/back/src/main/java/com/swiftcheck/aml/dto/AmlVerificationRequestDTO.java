package com.swiftcheck.aml.dto;

import lombok.Data;
import java.util.Map;
import java.util.List;

@Data
public class AmlVerificationRequestDTO {
    private String dataset; // The yente dataset, e.g. "default", "eu_sanctions"
    private String schema;  // The FTM schema, e.g. "Person", "Organization"
    private Map<String, List<String>> properties; // Properties like {"name": ["John Doe"]}
}
