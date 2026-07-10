/**
 * @Author : Cui
 * @Date: 2026/07/10 11:39
 * @Description DataSmart Govern Backend - ApprovalCenterItemView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Unified low-sensitive approval item exposed to the console.
 *
 * <p>The approval center currently aggregates project creation and project join requests. New approval domains can be
 * added behind the same contract without forcing the frontend to build another isolated pending-work page.</p>
 */
@Data
public class ApprovalCenterItemView {

    private String requestType;
    private Long requestId;
    private Long tenantId;
    private Long applicationId;
    private Long projectId;
    private String projectCode;
    private String projectName;
    private Long applicantActorId;
    private String applicantName;
    private String applicantUsername;
    private Long ownerActorId;
    private String ownerUsername;
    private String requestedProjectRole;
    private String requestReason;
    private String status;
    private Long reviewerActorId;
    private String reviewerUsername;
    private String reviewerActorRole;
    private String reviewComment;
    private LocalDateTime reviewTime;
    private Long resultResourceId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * Actions already authorized by the service for the current caller, such as CANCEL, APPROVE and REJECT.
     */
    private List<String> availableActions = List.of();
}
