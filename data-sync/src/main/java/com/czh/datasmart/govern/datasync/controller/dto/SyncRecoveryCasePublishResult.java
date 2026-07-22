package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Published case reference returned to the Agent workflow. */
@Value
@Builder
public class SyncRecoveryCasePublishResult {
    Long caseId;
    Long syncTaskId;
    Long diagnosisExecutionId;
    Long validationExecutionId;
    String caseStatus;
    List<String> rootCauseCodes;
    List<String> repairActionCodes;
    boolean reusedExistingCase;
}
