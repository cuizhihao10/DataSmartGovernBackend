package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.util.List;

/** Low-sensitive evidence required to publish a verified recovery case. */
@Data
public class SyncRecoveryCasePublishRequest {
    private Long diagnosisExecutionId;
    private Long validationExecutionId;
    private List<String> rootCauseCodes;
    private List<String> repairActionCodes;
    private List<String> evidenceReferences;
}
