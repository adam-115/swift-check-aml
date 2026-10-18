package com.swiftcheck.aml.dto;

import lombok.Data;

@Data
public class AdverseMediaResult {
    private String title;
    private String content;
    private String url;
    private String engine;
    private String publishedDate;
    private int riskScore; // Calculated locally based on keywords
}
