package com.swiftcheck.aml.dto;

import java.time.Instant;

import com.swiftcheck.aml.entities.ExecutionTriggerEnum;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;

/** Une exécution de parser, en lecture seule. */
public record ParserExecutionDTO(
        long id,
        long parserId,
        String parserName,
        String datasetName,
        ExecutionTriggerEnum triggerType,
        ParserExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        String inputSha256,
        int entityCount,
        int personCount,
        int retainedCount,
        int rejectedCount,
        int addedCount,
        int removedCount,
        int changedCount,
        String message) {

    public static ParserExecutionDTO from(ParserExecution e) {
        return new ParserExecutionDTO(e.getId(), e.getParser().getId(), e.getParser().getName(),
                e.getParser().getDatasetName(), e.getTriggerType(), e.getStatus(), e.getStartedAt(),
                e.getFinishedAt(), e.getInputSha256(), e.getEntityCount(), e.getPersonCount(), e.getRetainedCount(), e.getRejectedCount(),
                e.getAddedCount(), e.getRemovedCount(), e.getChangedCount(), e.getMessage());
    }
}
