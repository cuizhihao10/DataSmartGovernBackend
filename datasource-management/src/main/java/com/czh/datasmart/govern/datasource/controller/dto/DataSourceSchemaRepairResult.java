package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/** Public, low-sensitive representation of a schema repair preview or result. */
@Value
@Builder
public class DataSourceSchemaRepairResult {
    Long planId;
    String planRef;
    Long datasourceId;
    String operation;
    String objectLocator;
    String columnName;
    String currentDefinition;
    String requestedDefinition;
    String impactSummary;
    String planStatus;
    boolean requiresConfirmation;
    String confirmationDigest;
    LocalDateTime appliedAt;
    List<String> safetyConstraints;
}
