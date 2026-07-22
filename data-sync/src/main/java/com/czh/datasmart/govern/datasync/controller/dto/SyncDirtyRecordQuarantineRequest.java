/**
 * @Author : Cui
 * @Date: 2026/07/22
 * @Description DataSmart Govern Backend - SyncDirtyRecordQuarantineRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.util.List;

/** User-confirmed dirty-record quarantine request used by preview and apply. */
@Data
public class SyncDirtyRecordQuarantineRequest {
    private Long executionId;
    private List<Long> errorSampleIds;
    private Boolean quarantineAllRetryableInExecution;
    private String reason;
    private String confirmationDigest;
    private Boolean confirmed;
}
