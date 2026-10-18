package com.swiftcheck.aml.dto;

import com.swiftcheck.aml.entities.ParserTestFailure;
import com.swiftcheck.aml.entities.TestFailureReason;

import lombok.Data;

/** Un élément de la source non retrouvé dans Yente. */
@Data
public class ParserTestFailureDTO {
    private String entityId;
    private String schema;
    private String name;
    private String sourceRef;
    private TestFailureReason reason;
    private String detail;

    public static ParserTestFailureDTO from(ParserTestFailure failure) {
        ParserTestFailureDTO dto = new ParserTestFailureDTO();
        dto.setEntityId(failure.getEntityId());
        dto.setSchema(failure.getEntitySchema());
        dto.setName(failure.getName());
        dto.setSourceRef(failure.getSourceRef());
        dto.setReason(failure.getReason());
        dto.setDetail(failure.getDetail());
        return dto;
    }
}
