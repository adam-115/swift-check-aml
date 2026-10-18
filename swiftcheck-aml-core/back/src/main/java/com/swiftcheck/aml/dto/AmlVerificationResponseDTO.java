package com.swiftcheck.aml.dto;

import lombok.Data;
import java.util.List;

@Data
public class AmlVerificationResponseDTO {
    private List<AmlMatchDTO> matches;
    private String errorMessage;
}
