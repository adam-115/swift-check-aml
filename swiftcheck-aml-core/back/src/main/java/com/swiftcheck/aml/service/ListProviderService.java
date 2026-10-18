package com.swiftcheck.aml.service;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.ListProvider;
import com.swiftcheck.aml.repositories.ListProviderRepository;

@Service
public class ListProviderService {

    private final ListProviderRepository listProviderRepository;

    @Autowired
    public ListProviderService(ListProviderRepository listProviderRepository) {
        this.listProviderRepository = listProviderRepository;
    }

    public ListProvider save(ListProvider entity) {
        return listProviderRepository.save(entity);
    }

    public Optional<ListProvider> findById(Long id) {
        return listProviderRepository.findById(id);
    }

    public List<ListProvider> findAll() {
        return listProviderRepository.findAll();
    }

    
    public Page<ListProvider> findAll(Pageable pageable) {
        return listProviderRepository.findAll(pageable);
    }

    public void deleteById(Long id) {
        listProviderRepository.deleteById(id);
    }
}
