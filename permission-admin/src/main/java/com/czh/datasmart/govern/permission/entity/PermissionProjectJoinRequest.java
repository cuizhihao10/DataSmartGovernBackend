/**
 * @Author : Cui
 * @Date: 2026/07/10 20:40
 * @Description DataSmart Govern Backend - PermissionProjectJoinRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Project join application fact.
 *
 * <p>This table is intentionally separate from {@link PermissionProjectMembership}. A join request is a workflow
 * object: it can be pending, rejected, cancelled, or approved. A membership is the final access fact consumed by
 * gateway and downstream services. Keeping the two concepts separate prevents the system from granting project data
 * scope before a human administrator or project owner has made a review decision.</p>
 */
@Data
@TableName("permission_project_join_request")
public class PermissionProjectJoinRequest {

    /**
     * Request primary key.
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Tenant boundary of the target project.
     */
    private Long tenantId;

    /**
     * Target project the user wants to join.
     */
    private Long projectId;

    /**
     * Applicant actor ID. This maps to the actor ID injected by gateway after Keycloak/IdP authentication.
     */
    private Long applicantActorId;

    /**
     * Low-sensitive applicant display snapshot used by approval pages.
     */
    private String applicantName;

    /**
     * Requested project role. Current production-facing roles are OWNER, MANAGER, READER and SERVICE.
     */
    private String requestedProjectRole;

    /**
     * User-provided reason for joining the project.
     */
    private String requestReason;

    /**
     * Workflow status: PENDING, APPROVED, REJECTED or CANCELLED.
     */
    private String status;

    /**
     * Reviewer actor ID written when the request is approved or rejected.
     */
    private Long reviewerActorId;

    /**
     * Reviewer global/platform role snapshot at review time.
     */
    private String reviewerActorRole;

    /**
     * Reviewer comment shown to applicant and auditors.
     */
    private String reviewComment;

    /**
     * Review decision time.
     */
    private LocalDateTime reviewTime;

    /**
     * Membership ID created or updated when the request is approved.
     */
    private Long membershipId;

    /**
     * Creation time.
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * Last update time.
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
