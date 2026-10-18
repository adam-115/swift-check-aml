package com.swiftcheck.aml.controllers;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.dto.ListProviderDTO;
import com.swiftcheck.aml.entities.ListProvider;
import com.swiftcheck.aml.service.ListProviderService;

/**
 * Fournisseurs de listes (UE, ONU, OFAC…).
 * <pre>
 * GET    /api/providers          liste paginée
 * GET    /api/providers/{id}
 * POST   /api/providers          création
 * PUT    /api/providers/{id}     modification
 * DELETE /api/providers/{id}     suppression (refusée s'il a encore des parsers)
 * </pre>
 */
@RestController
@RequestMapping("/api/providers")
public class ListProviderController {

    private final ListProviderService providerService;

    public ListProviderController(ListProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    public Page<ListProviderDTO> getAll(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return providerService.findAll(PageRequest.of(page, size)).map(ListProviderDTO::from);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ListProviderDTO> getById(@PathVariable long id) {
        return providerService.findById(id)
                .map(provider -> ResponseEntity.ok(ListProviderDTO.from(provider)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ListProviderDTO create(@RequestBody ListProviderDTO dto) {
        ListProvider provider = new ListProvider();
        dto.copyTo(provider);
        return ListProviderDTO.from(providerService.save(provider));
    }

    @PutMapping("/{id}")
    public ListProviderDTO update(@PathVariable long id, @RequestBody ListProviderDTO dto) {
        ListProvider provider = providerService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable : " + id));
        dto.copyTo(provider);
        return ListProviderDTO.from(providerService.save(provider));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        providerService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
