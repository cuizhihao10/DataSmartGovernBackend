package com.czh.datasmart.govern.datasource.support;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionApprovalDecision.java
 * @Version:1.0.0
 *
 * 权限变更审批决策结果。
 * 这个对象不是给前端直接返回的 DTO，而是审批服务内部在“校验审批资格”阶段使用的解释型结果。
 *
 * 它承载三类关键事实：
 * 1. 当前审批通过的是直接角色资格，还是委托资格；
 * 2. 当前申请要求的审批角色快照有哪些；
 * 3. 如果是委托代批，委托来源是谁、来源角色是什么。
 *
 * 把这些信息集中在一个对象里，能避免审批服务里散落很多临时变量，
 * 也更方便在后续审计、日志和返回视图里复用。
 */
@Value
@Builder
public class SyncPermissionApprovalDecision {

    /**
     * 最终命中的审批模式。
     */
    SyncPermissionApprovalMode approvalMode;

    /**
     * 当前申请单要求的审批角色快照。
     */
    List<String> requiredApproverRoles;

    /**
     * 如果是委托代批，这里记录委托来源审批人 ID。
     * 直接审批时为空。
     */
    Long delegatedFromApproverId;

    /**
     * 如果是委托代批，这里记录委托来源审批角色。
     * 直接审批时为空。
     */
    String delegatedFromApproverRole;
}
