package com.swiftcheck.aml.dto;

import java.time.Instant;
import java.util.List;

import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserTest;
import com.swiftcheck.aml.entities.ParserTestStatus;

import lombok.Data;

/** Test de parser tel qu'affiché par l'interface (lecture seule). */
@Data
public class ParserTestDTO {
    private long id;
    private long parserId;
    private String parserName;
    private String datasetName;
    /** Publication testée. */
    private Long executionId;
    private ParserTestStatus status;
    private Instant startedAt;
    private Instant finishedAt;
    private int sourceCount;
    private int foundCount;
    private int notConvertedCount;
    private int notIndexedCount;
    private int notMatchedCount;
    private int errorCount;
    /** Part des éléments de la source retrouvés dans Yente, en %. */
    private double foundPercent;
    private String message;

    /** Publication testée : date et nombre d'entités publiées. */
    private Instant publishedAt;
    private Integer publishedEntityCount;
    /** Fichier(s) source relus par le test, avec leur date de téléchargement et leur empreinte. */
    private List<SourceFileDTO> sourceFiles = List.of();

    /** Test enrichi de sa publication et de ses fichiers source (voir ParserTestViews). */
    public static ParserTestDTO from(ParserTest test, ParserExecution publication, List<SourceFileDTO> sourceFiles) {
        ParserTestDTO dto = from(test);
        if (publication != null) {
            dto.setPublishedAt(publication.getStartedAt());
            dto.setPublishedEntityCount(publication.getEntityCount());
        }
        dto.setSourceFiles(sourceFiles);
        return dto;
    }

    public static ParserTestDTO from(ParserTest test) {
        ParserTestDTO dto = new ParserTestDTO();
        dto.setId(test.getId());
        dto.setParserId(test.getParser().getId());
        dto.setParserName(test.getParser().getName());
        dto.setDatasetName(test.getParser().getDatasetName());
        dto.setExecutionId(test.getExecutionId());
        dto.setStatus(test.getStatus());
        dto.setStartedAt(test.getStartedAt());
        dto.setFinishedAt(test.getFinishedAt());
        dto.setSourceCount(test.getSourceCount());
        dto.setFoundCount(test.getFoundCount());
        dto.setNotConvertedCount(test.getNotConvertedCount());
        dto.setNotIndexedCount(test.getNotIndexedCount());
        dto.setNotMatchedCount(test.getNotMatchedCount());
        dto.setErrorCount(test.getErrorCount());
        dto.setFoundPercent(test.getSourceCount() == 0 ? 0
                : Math.round(test.getFoundCount() * 1000.0 / test.getSourceCount()) / 10.0);
        dto.setMessage(test.getMessage());
        return dto;
    }
}
