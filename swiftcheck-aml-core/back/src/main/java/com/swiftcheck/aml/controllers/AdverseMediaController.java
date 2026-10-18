package com.swiftcheck.aml.controllers;

import com.swiftcheck.aml.dto.AdverseMediaResult;
import com.swiftcheck.aml.service.SearxngMediaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/media")
@CrossOrigin(origins = "*") // In production, replace with proper CORS
public class AdverseMediaController {

    private final SearxngMediaService mediaService;

    @Autowired
    public AdverseMediaController(SearxngMediaService mediaService) {
        this.mediaService = mediaService;
    }

    @GetMapping("/verify")
    public ResponseEntity<List<AdverseMediaResult>> verifyMedia(@RequestParam String targetName) {
        if (targetName == null || targetName.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        List<AdverseMediaResult> results = mediaService.verifyMedia(targetName);
        return ResponseEntity.ok(results);
    }
}
