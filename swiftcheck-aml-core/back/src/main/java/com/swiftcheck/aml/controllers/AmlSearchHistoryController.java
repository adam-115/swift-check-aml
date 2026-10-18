package com.swiftcheck.aml.controllers;

import com.swiftcheck.aml.dto.AmlSearchHistoryDTO;
import com.swiftcheck.aml.service.AmlSearchHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/history")
public class AmlSearchHistoryController {

    private final AmlSearchHistoryService historyService;

    @Autowired
    public AmlSearchHistoryController(AmlSearchHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    public ResponseEntity<List<AmlSearchHistoryDTO>> getAllHistory() {
        return ResponseEntity.ok(historyService.getAllHistory());
    }
}
