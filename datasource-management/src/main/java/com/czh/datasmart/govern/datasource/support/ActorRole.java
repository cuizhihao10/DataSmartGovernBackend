package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * @Author : Cui
 * @Date: 2026/4/19 20:58
 * @Description DataSmart Govern Backend - ActorRole.java
 * @Version:1.0.0
 *
 * 操作人角色枚举。
 * 当前仓库还没有把完整权限中心真正接进来，但数据同步控制面已经出现了明显的角色分层：
 * 1. 普通项目负责人希望能配置和运行自己负责的任务；
 * 2. 运营与租户管理员需要跨任务、跨项目做治理动作；
 * 3. 审计员需要看结构、看队列健康，但不应该直接改任务；
 * 4. 执行器或系统服务账号需要用机器身份认领任务、续租和回写执行结果。
 *
 * 因此这里先用本地枚举承载“最小可工作的权限矩阵”，让每个接口在 permission-admin
 * 模块真正接入前，就已经具备明确、可读、可演进的角色边界。
 *
 * 这些方法不是最终权限中心本身，而是 datasource-management 模块内部的显式策略层：
 * - 便于你从代码直接看出“谁可以做什么”；
 * - 便于后续把本地判断替换成统一权限服务时，有一层稳定语义可映射；
 * - 便于审计、审批、管理员强制控制和执行器回调这些动作在设计上先分开。
 */
public enum ActorRole {
    ORDINARY_USER,
    PROJECT_OWNER,
    OPERATOR,
    AUDITOR,
    TENANT_ADMINISTRATOR,
    PLATFORM_ADMINISTRATOR,
    SERVICE_ACCOUNT;

    /**
     * 管理员角色集合。
     * 这些角色拥有“跨任务、跨负责人”的强制治理能力，比如强制取消、强制重试、覆盖优先级等。
     */
    private static final EnumSet<ActorRole> ADMIN_ROLES =
            EnumSet.of(TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 审批角色集合。
     * 审批是带治理语义的动作，所以不开放给普通项目负责人和普通用户。
     */
    private static final EnumSet<ActorRole> APPROVAL_ROLES =
            EnumSet.of(OPERATOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 元数据发现允许角色。
     * 看结构虽然不像看样本数据那样敏感，但仍然会暴露表、字段、索引等内部信息。
     */
    private static final EnumSet<ActorRole> METADATA_DISCOVERY_ROLES =
            EnumSet.of(PROJECT_OWNER, OPERATOR, AUDITOR, TENANT_ADMINISTRATOR,
                    PLATFORM_ADMINISTRATOR, SERVICE_ACCOUNT);

    /**
     * 样本数据预览允许角色。
     * 样本数据比结构信息更敏感，因此权限必须比元数据发现更严格。
     */
    private static final EnumSet<ActorRole> SAMPLE_PREVIEW_ROLES =
            EnumSet.of(OPERATOR, AUDITOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 模板管理允许角色。
     * 模板定义直接影响后续任务行为，因此只开放给具备配置职责的角色。
     */
    private static final EnumSet<ActorRole> TEMPLATE_MANAGEMENT_ROLES =
            EnumSet.of(PROJECT_OWNER, OPERATOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 同步任务创建允许角色。
     * 普通用户和审计员不直接创建任务；项目负责人、运营和管理员可以。
     */
    private static final EnumSet<ActorRole> TASK_CREATION_ROLES =
            EnumSet.of(PROJECT_OWNER, OPERATOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 可以操作“自己负责任务”的角色。
     * 当前主要是项目负责人；他们更适合被限制在自己的责任范围内。
     */
    private static final EnumSet<ActorRole> OWNED_TASK_OPERATION_ROLES =
            EnumSet.of(PROJECT_OWNER);

    /**
     * 可以跨任务治理的角色。
     * 运营和管理员经常需要代替一线用户做排障、调度、补偿和治理动作。
     */
    private static final EnumSet<ActorRole> ANY_TASK_OPERATION_ROLES =
            EnumSet.of(OPERATOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 队列健康查看允许角色。
     * 队列健康主要用于运营、审计和管理员观察平台负载与积压状态。
     */
    private static final EnumSet<ActorRole> QUEUE_HEALTH_VIEW_ROLES =
            EnumSet.of(OPERATOR, AUDITOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 队列老化巡检允许角色。
     * 这类动作会修改任务上的人工关注标记，因此不开放给只读审计角色。
     */
    private static final EnumSet<ActorRole> QUEUE_AGING_SCAN_ROLES =
            EnumSet.of(OPERATOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 权限策略查看允许角色。
     * 这类能力既服务于管理员，也服务于审计和运营排障场景，
     * 因此保留“可查看但不一定可修改”的读权限角色。
     */
    private static final EnumSet<ActorRole> PERMISSION_POLICY_VIEW_ROLES =
            EnumSet.of(OPERATOR, AUDITOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 执行器与系统回调允许角色。
     * SERVICE_ACCOUNT 被纳入这里，是为了给未来真正的执行器节点留出机器身份接入空间。
     */
    private static final EnumSet<ActorRole> EXECUTOR_OPERATION_ROLES =
            EnumSet.of(OPERATOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR, SERVICE_ACCOUNT);

    public boolean canApprove() {
        return APPROVAL_ROLES.contains(this);
    }

    public boolean canForceOverride() {
        return ADMIN_ROLES.contains(this);
    }

    public boolean canDiscoverMetadata() {
        return METADATA_DISCOVERY_ROLES.contains(this);
    }

    public boolean canPreviewSampleRows() {
        return SAMPLE_PREVIEW_ROLES.contains(this);
    }

    public boolean canManageTemplates() {
        return TEMPLATE_MANAGEMENT_ROLES.contains(this);
    }

    /**
     * 是否允许创建同步任务。
     */
    public boolean canCreateSyncTasks() {
        return TASK_CREATION_ROLES.contains(this);
    }

    /**
     * 是否允许修改或操作自己负责的任务。
     */
    public boolean canOperateOwnedSyncTasks() {
        return OWNED_TASK_OPERATION_ROLES.contains(this);
    }

    /**
     * 是否允许跨任务、跨负责人做统一运营治理。
     */
    public boolean canOperateAnySyncTasks() {
        return ANY_TASK_OPERATION_ROLES.contains(this);
    }

    /**
     * 是否允许查看队列健康快照。
     */
    public boolean canInspectQueueHealth() {
        return QUEUE_HEALTH_VIEW_ROLES.contains(this);
    }

    /**
     * 是否允许执行队列老化巡检。
     */
    public boolean canScanQueueAging() {
        return QUEUE_AGING_SCAN_ROLES.contains(this);
    }

    /**
     * 是否允许查看本地权限策略快照。
     * 这里强调“查看”而不是“管理”，是为了让当前模块在还未接入统一权限中心前，
     * 就具备一套可供运营、审计、管理员共同理解的策略观察面。
     */
    public boolean canViewPermissionPolicies() {
        return PERMISSION_POLICY_VIEW_ROLES.contains(this);
    }

    /**
     * 是否允许认领待执行任务。
     */
    public boolean canClaimQueuedTasks() {
        return EXECUTOR_OPERATION_ROLES.contains(this);
    }

    /**
     * 是否允许上报执行器心跳。
     */
    public boolean canReportExecutionHeartbeat() {
        return EXECUTOR_OPERATION_ROLES.contains(this);
    }

    /**
     * 是否允许回写执行中的进度。
     * 这里与心跳权限保持一致，表示它们都属于“执行器平面”的动作。
     */
    public boolean canReportExecutionProgress() {
        return EXECUTOR_OPERATION_ROLES.contains(this);
    }

    /**
     * 是否允许回写执行完成或失败结果。
     * 结果回写会改变任务主状态，因此明确提供单独方法，便于未来进一步细化权限。
     */
    public boolean canReportExecutionResult() {
        return EXECUTOR_OPERATION_ROLES.contains(this);
    }

    public static ActorRole fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的角色类型: " + value));
    }
}
