package com.swiftcheck.aml.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.dto.AmlSearchHistoryDTO;
import com.swiftcheck.aml.dto.AmlVerificationRequestDTO;
import com.swiftcheck.aml.dto.AmlMatchDTO;
import com.swiftcheck.aml.entities.AmlSearchHistory;
import com.swiftcheck.aml.repositories.AmlSearchHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AmlSearchHistoryService {

    private final AmlSearchHistoryRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired
    public AmlSearchHistoryService(AmlSearchHistoryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void logSearch(AmlVerificationRequestDTO request, List<AmlMatchDTO> matches) {
        AmlSearchHistory history = new AmlSearchHistory();
        history.setSearchDate(LocalDateTime.now());
        history.setSchema(request.getSchema());
        history.setDataset(request.getDataset());
        history.setMatchesCount(matches != null ? matches.size() : 0);

        try {
            if (request.getProperties() != null) {
                history.setSearchedProperties(objectMapper.writeValueAsString(request.getProperties()));
            }
            if (matches != null && !matches.isEmpty()) {
                history.setResultsJson(objectMapper.writeValueAsString(matches));
            }
        } catch (JsonProcessingException e) {
            history.setSearchedProperties("Error serializing properties");
            history.setResultsJson("Error serializing results");
        }

        repository.save(history);
    }

    public List<AmlSearchHistoryDTO> getAllHistory() {
        List<AmlSearchHistory> entities = repository.findAll(Sort.by(Sort.Direction.DESC, "searchDate"));
        return entities.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    private AmlSearchHistoryDTO convertToDTO(AmlSearchHistory entity) {
        AmlSearchHistoryDTO dto = new AmlSearchHistoryDTO();
        dto.setId(entity.getId());
        dto.setSearchDate(entity.getSearchDate());
        dto.setSchema(entity.getSchema());
        dto.setDataset(entity.getDataset());
        dto.setSearchedProperties(entity.getSearchedProperties());
        dto.setMatchesCount(entity.getMatchesCount());
        dto.setResultsJson(entity.getResultsJson());
        return dto;
    }
}
