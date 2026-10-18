package com.swiftcheck.aml.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.dto.ApplicationConfigDTO;
import com.swiftcheck.aml.service.ApplicationConfigService;

/**
 * Configuration globale (réservée aux administrateurs, voir SecurityConfig).
 * <pre>
 * GET /api/config      configuration en vigueur
 * PUT /api/config      modification
 * </pre>
 */
@RestController
@RequestMapping("/api/config")
public class ApplicationConfigController {

    private final ApplicationConfigService configService;

    public ApplicationConfigController(ApplicationConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ApplicationConfigDTO get() {
        return ApplicationConfigDTO.from(configService.current());
    }

    @PutMapping
    public ApplicationConfigDTO update(@RequestBody ApplicationConfigDTO dto) {
        return ApplicationConfigDTO.from(configService.update(dto.toEntity()));
    }
}
