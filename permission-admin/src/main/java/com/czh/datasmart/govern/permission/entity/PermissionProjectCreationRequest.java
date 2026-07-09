/**
 * @Author : Cui
 * @Date: 2026/07/10 20:55
 * @Description DataSmart Govern Backend - PermissionProjectCreationRequest.java
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
 * Project creation approval workflow fact.
 *
 * <p>A pending creation request is not a project and must not create datasource or data-sync scope. Only after a tenant
 * or platform administrator approves it do we call the normal project creation service, which creates the project and
 * grants the applicant OWNER membership in one audited transaction.</p>
 */
@Data
@TableName("permission_project_creation_request")
public class PermissionProjectCreationRequest {

    /**
     * Request primary key.
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Tenant where the project should be created.
     */
    private Long tenantId;

    /**
     * Application that will own the project. Null means the default application of the tenant.
     */
    private Long applicationId;

    /**
     * Requested stable project code. It is still checked again at approval time.
     */
    private String projectCode;

    /**
     * Requested project display name.
     */
    private String projectName;

    /**
     * Requested project type, usually DATA_GOVERNANCE.
     */
    private String projectType;

    /**
     * Actor who submitted the request.
     */
    private Long applicantActorId;

    /**
     * Low-sensitive applicant display snapshot.
     */
    private String applicantName;

    /**
     * Owner actor ID to receive OWNER membership after approval.
     */
    private Long ownerActorId;

    /**
     * Low-sensitive project description.
     */
    private String description;

    /**
     * Applicant reason shown to reviewers and audit.
     */
    private String requestReason;

    /**
     * Workflow status: PENDING, APPROVED, REJECTED or CANCELLED.
     */
    private String status;

    /**
     * Reviewer actor ID.
     */
    private Long reviewerActorId;

    /**
     * Reviewer role snapshot.
     */
    private String reviewerActorRole;

    /**
     * Review comment.
     */
    private String reviewComment;

    /**
     * Review decision time.
     */
    private LocalDateTime reviewTime;

    /**
     * Project ID created when the request is approved.
     */
    private Long createdProjectId;

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
