package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionPolicyChangeRequest.java
 * @Version:1.0.0
 *
 * 权限绑定变更申请实体。
 * 这张表承载的是“高风险权限治理动作不要直接改库，而要先申请、再审批、最后执行”的完整链路。
 *
 * 当前重点记录以下几类事实：
 * 1. 谁发起了这次申请；
 * 2. 这次申请打算改哪个租户、哪个角色、哪类绑定；
 * 3. 当时系统要求由哪些角色审批；
 * 4. 最终是谁批的，是直接审批还是委托代批；
 * 5. 审批通过后是否真的执行了绑定替换，以及执行摘要是什么。
 *
 * 之所以把“审批角色快照”和“审批模式”也存进申请单，而不是只依赖运行时动态判断，
 * 是因为真实治理系统里，审批策略可能会调整；但历史申请依然需要解释清楚：
 * “为什么当时是这几类人能批”“为什么这次算代批而不是直接批”。
 */
@Data
@TableName("sync_permission_policy_change_request")
public class SyncPermissionPolicyChangeRequest {

    /**
     * 申请主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 目标作用域租户 ID。
     * 约定 0 表示平台全局权限变更，非 0 表示租户级权限覆盖。
     */
    private Long targetTenantId;

    /**
     * 申请发起人 ID。
     */
    private Long requesterId;

    /**
     * 申请发起人角色。
     */
    private String requesterRole;

    /**
     * 申请发起人所属租户。
     * 后续做租户隔离、审批范围校验和审计追溯时会使用这个上下文。
     */
    private Long requesterTenantId;

    /**
     * 被修改绑定的目标角色。
     */
    private String targetRole;

    /**
     * 本次申请涉及的绑定类型。
     * 例如 MENU、ROUTE、DATA_SCOPE。
     */
    private String bindingType;

    /**
     * 本次申请希望写入的绑定值列表 JSON。
     */
    private String bindingValuesJson;

    /**
     * 本次申请希望使用的默认优先级。
     */
    private Integer requestedPriority;

    /**
     * 本次申请希望使用的绑定来源。
     */
    private String requestedBindingSource;

    /**
     * 申请原因说明。
     */
    private String requestReason;

    /**
     * 提交申请时解析出的审批角色快照 JSON。
     * 这里记录的是“这张申请单当时要求哪些角色审批”，用于保障历史可解释性。
     */
    private String requiredApproverRolesJson;

    /**
     * 当前申请状态。
     * 当前阶段主要使用 PENDING_APPROVAL、EXECUTED、REJECTED。
     */
    private String requestStatus;

    /**
     * 最终审批人 ID。
     */
    private Long approverId;

    /**
     * 最终审批人角色。
     */
    private String approverRole;

    /**
     * 审批模式。
     * 例如 DIRECT_ROLE、DELEGATED_ROLE。
     */
    private String approvalMode;

    /**
     * 如果本次属于委托代批，记录原始被委托的审批人 ID。
     */
    private Long delegatedFromApproverId;

    /**
     * 如果本次属于委托代批，记录原始被委托的审批角色。
     */
    private String delegatedFromApproverRole;

    /**
     * 审批意见。
     */
    private String approvalComment;

    /**
     * 审批完成时间。
     */
    private LocalDateTime approvedAt;

    /**
     * 审批通过后真正执行绑定替换的完成时间。
     */
    private LocalDateTime executedAt;

    /**
     * 执行结果摘要。
     * 这个字段主要服务于审批列表、审计排障和运营复盘。
     */
    private String executionSummary;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
