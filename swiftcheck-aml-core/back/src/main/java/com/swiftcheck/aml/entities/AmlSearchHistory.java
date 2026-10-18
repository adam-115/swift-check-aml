package com.swiftcheck.aml.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "aml_search_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AmlSearchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "search_date", nullable = false)
    private LocalDateTime searchDate;

    @Column(name = "schema_name", nullable = false)
    private String schema;

    @Column(name = "dataset")
    private String dataset;

    @Column(name = "searched_properties", columnDefinition = "TEXT")
    private String searchedProperties;

    @Column(name = "matches_count")
    private Integer matchesCount;

    @Column(name = "results_json", columnDefinition = "LONGTEXT")
    private String resultsJson;
}
