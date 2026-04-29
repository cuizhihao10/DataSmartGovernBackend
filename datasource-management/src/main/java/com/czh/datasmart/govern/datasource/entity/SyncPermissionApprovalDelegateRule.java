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
 * @Description DataSmart Govern Backend - SyncPermissionApprovalDelegateRule.java
 * @Version:1.0.0
 *
 * 权限审批委托规则实体。
 * 这张表解决的是“原本应该由某个审批人处理的权限治理事项，在其请假、值班切换、组织变更时如何合法地转交给他人审批”。
 *
 * 当前重点覆盖的业务语义有：
 * 1. 委托作用在哪个租户范围下生效；
 * 2. 谁把自己的审批资格委托给了谁；
 * 3. 委托双方分别是什么角色；
 * 4. 委托什么时候开始、什么时候结束；
 * 5. 规则是否仍然启用。
 *
 * 这里暂时只处理“审批资格委托”这一件事，不混入普通任务代办、告警代收等其他授权语义，
 * 这样可以让这一层模型更稳定，也更方便后续和统一权限中心做映射。
 */
@Data
@TableName("sync_permission_approval_delegate_rule")
public class SyncPermissionApprovalDelegateRule {

    /**
     * 委托规则主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 委托生效的租户范围。
     * 约定 0 表示平台全局审批委托，非 0 表示某个租户下的审批委托。
     */
    private Long targetTenantId;

    /**
     * 原始审批人 ID。
     * 只有这个人原本具备的审批资格，才允许被显式委托出去。
     */
    private Long delegatorId;

    /**
     * 原始审批人角色。
     * 这里会被审批决策器拿来和“申请单要求的审批角色快照”做匹配。
     */
    private String delegatorRole;

    /**
     * 被委托审批的人 ID。
     */
    private Long delegateId;

    /**
     * 被委托审批的人角色。
     * 当前先要求被委托人也属于具备审批动作基础资格的角色，避免把高风险审批下放给普通角色。
     */
    private String delegateRole;

    /**
     * 委托开始时间。
     * 为空表示规则创建后立即生效。
     */
    private LocalDateTime effectiveFrom;

    /**
     * 委托结束时间。
     * 为空表示暂时没有结束时间，由人工禁用或后续再补截止日期。
     */
    private LocalDateTime effectiveTo;

    /**
     * 当前规则是否启用。
     * 禁用后规则保留在库中，便于后续审计谁曾经授权过谁。
     */
    private Boolean enabled;

    /**
     * 委托原因说明。
     * 常见场景如值班轮转、请假代班、组织临时调整。
     */
    private String delegateReason;

    /**
     * 规则创建人 ID。
     */
    private Long createdBy;

    /**
     * 规则最后更新人 ID。
     */
    private Long updatedBy;

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
