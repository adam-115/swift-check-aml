package com.swiftcheck.aml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.dto.AdverseMediaResult;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

@Service
public class SearxngMediaService {

    @Value("${SEARXNG_URL:http://localhost:8088/search}")
    private String searxngUrl;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Risk keywords in English and French
    private final List<String> RISK_KEYWORDS = Arrays.asList(
            "fraude", "fraud", "blanchiment", "money laundering", "corruption",
            "condamné", "convicted", "tribunal", "court", "trafic", "trafficking",
            "terrorisme", "terrorism", "détournement", "embezzlement", "arrêté", "arrested"
    );

    public List<AdverseMediaResult> verifyMedia(String targetName) {
        List<AdverseMediaResult> results = new ArrayList<>();
        
        try {
            // Build the query: "targetName" AND (fraud OR money laundering OR ...)
            // SearxNG will pass this to Google/Bing
            String query = "\"" + targetName + "\" (fraude OR fraud OR blanchiment OR corruption OR convicted OR arrested)";
            
            String url = UriComponentsBuilder.fromHttpUrl(searxngUrl)
                    .queryParam("q", query)
                    .queryParam("format", "json")
                    .queryParam("language", "auto")
                    .queryParam("categories", "news,general")
                    .build().toUriString();

            String jsonResponse = restTemplate.getForObject(url, String.class);
            
            if (jsonResponse != null) {
                JsonNode root = objectMapper.readTree(jsonResponse);
                JsonNode resultsNode = root.get("results");
                
                if (resultsNode != null && resultsNode.isArray()) {
                    for (JsonNode node : resultsNode) {
                        AdverseMediaResult r = new AdverseMediaResult();
                        r.setTitle(node.has("title") ? node.get("title").asText() : "");
                        r.setContent(node.has("content") ? node.get("content").asText() : "");
                        r.setUrl(node.has("url") ? node.get("url").asText() : "");
                        r.setEngine(node.has("engine") ? node.get("engine").asText() : "unknown");
                        r.setPublishedDate(node.has("publishedDate") ? node.get("publishedDate").asText() : "");
                        
                        // Strict local filter: ensure the search engine didn't ignore the name quotes
                        String fullText = (r.getTitle() + " " + r.getContent()).toLowerCase();
                        String[] nameParts = targetName.toLowerCase().split("\\s+");
                        boolean nameMatch = true;
                        for (String part : nameParts) {
                            if (!fullText.contains(part)) {
                                nameMatch = false;
                                break;
                            }
                        }
                        
                        if (nameMatch) {
                            r.setRiskScore(calculateRiskScore(r.getTitle(), r.getContent()));
                            results.add(r);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace(); // In production, use proper logging
        }
        
        // Sort by highest risk score first
        results.sort((a, b) -> Integer.compare(b.getRiskScore(), a.getRiskScore()));
        return results;
    }

    private int calculateRiskScore(String title, String content) {
        int score = 0;
        String fullText = (title + " " + content).toLowerCase();
        
        for (String keyword : RISK_KEYWORDS) {
            if (fullText.contains(keyword)) {
                score += 25; // 25 points per keyword match
            }
        }
        
        return Math.min(score, 100); // Max score is 100
    }
}
