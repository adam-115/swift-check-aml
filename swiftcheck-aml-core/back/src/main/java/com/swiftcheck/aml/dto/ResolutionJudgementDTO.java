package com.swiftcheck.aml.dto;

import java.time.Instant;

import com.swiftcheck.aml.entities.Judgement;
import com.swiftcheck.aml.entities.ResolutionJudgement;

import lombok.Data;

/** Rapprochement entre deux entités. En entrée : les deux identifiants et la décision. */
@Data
public class ResolutionJudgementDTO {
    private long id;
    private String leftEntityId;
    private String rightEntityId;
    private Judgement judgement;
    private String decidedBy;
    private Instant createdAt;

    public static ResolutionJudgementDTO from(ResolutionJudgement entity) {
        ResolutionJudgementDTO dto = new ResolutionJudgementDTO();
        dto.setId(entity.getId());
        dto.setLeftEntityId(entity.getLeftEntityId());
        dto.setRightEntityId(entity.getRightEntityId());
        dto.setJudgement(entity.getJudgement());
        dto.setDecidedBy(entity.getDecidedBy());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
