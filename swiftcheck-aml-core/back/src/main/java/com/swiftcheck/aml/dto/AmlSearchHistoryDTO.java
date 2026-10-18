package com.swiftcheck.aml.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AmlSearchHistoryDTO {
    private Long id;
    private LocalDateTime searchDate;
    private String schema;
    private String dataset;
    private String searchedProperties;
    private Integer matchesCount;
    private String resultsJson;
}
